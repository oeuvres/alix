package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Locale;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.SingularValueDecomposition;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.Snippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.util.AssociationMeasure;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /{index}/coocmatrix} — a two-dimensional map of the terms co-occurring
 * with a pivot query, by SVD of a residual matrix (the spectral-map reading of
 * the co-occurrence table). {@link #compute} runs the pipeline and returns a
 * {@link CoocMap}; {@link CoocMatUtil} serialises it as CSV or JSON.
 *
 * <p>
 * The map is always symmetric, so {@code directed} does not reach the geometry:
 * folding the directed matrix onto its transpose is exactly what symmetrisation
 * does, and the undirected factor of two washes out under the scale invariance
 * of correspondence-style analysis. It is not mass-rescaled Correspondence
 * Analysis: the {@code 1/√mass} step is omitted, which is what stops
 * low-frequency terms being flung to the rim and yields the more even spread.
 * </p>
 */
public final class OpCoocMatrix extends Op
{
    
    /**
     * Immutable result of a co-occurrence map: parallel arrays indexed by rank, plus
     * the inertia share of each axis. Produced by {@link OpCoocMatrix#compute} and
     * consumed by {@link CoocMatUtil}. Holds no Lucene or servlet state, so it is
     * trivially serialisable to any format.
     *
     * @param form    term form, by rank
     * @param freq    occurrence marginal f(a), by rank — the matrix diagonal filled
     *                by {@code CoocMatSnippets}, direction-independent
     * @param xy      coordinates, {@code xy[rank][axis]}
     * @param inertia percentage of total inertia carried by each axis
     */
    record CoocMap(String[] form, long[] freq, double[][] xy, double[] inertia) {}
     

    @Override
    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    )
        throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final CoocMap map = compute(index, pars, meta);
        final Writer writer = response.getWriter();
        if (map == null) {
            meta.toString(writer, pars);
            return;
        }
        final DecimalFormat fmt = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));
        writer.append("form,freq,x,y\n");
        for (int i = 0; i < map.form().length; i++) {
            writer.append(csvCell(map.form()[i])).append(',')
                  .append(Long.toString(map.freq()[i])).append(',')
                  .append(fmt.format(map.xy()[i][0])).append(',')
                  .append(fmt.format(map.xy()[i][1])).append('\n');
        }

    }
    
    /**
     * CSV-escapes a field per RFC 4180: quoted only when it contains a comma,
     * double quote, CR or LF, with internal quotes doubled.
     *
     * @param s field value
     * @return the field, quoted if it needs to be
     */
    private static String csvCell(final String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }


    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    )
        throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final CoocMap map = compute(index, pars, meta);
        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();
 
            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();
 
            if (map != null) {
                jw.name("data");
                jw.beginObject();
                jw.name("axes");
                jw.beginObject();
                jw.name("dim1_pct").value(round(map.inertia()[0], 1));
                jw.name("dim2_pct").value(round(map.inertia()[1], 1));
                jw.endObject();
                jw.name("nodes");
                jw.beginArray();
                for (int i = 0; i < map.form().length; i++) {
                    jw.beginObject();
                    jw.name("id").value(map.form()[i]);
                    jw.name("freq").value(map.freq()[i]);
                    jw.name("x").value(round(map.xy()[i][0], 4));
                    jw.name("y").value(round(map.xy()[i][1], 4));
                    jw.endObject();
                }
                jw.endArray();
                jw.endObject();
            }
 
            jw.endObject();
        }

    }
    
    /**
     * Rounds to {@code decimals} fractional digits, to keep serialised
     * coordinates short.
     *
     * @param v        value
     * @param decimals number of fractional digits
     * @return the rounded value
     */
    private static double round(final double v, final int decimals) {
        final double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }


    /**
     * Runs the whole pipeline — term selection, span walk, residual matrix, SVD —
     * and returns the layout, or {@code null} when there is nothing to draw (no
     * top terms, or no span query). Format-independent; callers serialise the
     * result. The SVD math is the version verified against a numpy reference; it
     * additionally keeps the diagonal occurrence marginal as {@code freq} for
     * display and the first two inertia shares for the axis captions.
     *
     * <p>
     * Margins for the residual are the off-diagonal row sums of the symmetrised
     * matrix (the table's own margins, a zeroed diagonal); {@code freq} is the
     * separate occurrence marginal read from the diagonal, which
     * {@code CoocMatSnippets} fills. {@code pivotIds} is present in {@code meta}
     * only in co-occurrence mode, so its use is guarded.
     * </p>
     *
     * @param index the index to read
     * @param pars  request parameters
     * @param meta  filled by {@link OpTerms#topTerms}; carries {@code pivotIds} in co-occurrence mode
     * @return      the layout, or {@code null}
     * @throws IOException if the index read fails
     */
    private static CoocMap compute(
        final LuceneIndex index,
        final HttpPars pars,
        final MetaUtil meta
    )
        throws IOException {
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        if (topTerms == null) return null;
        final SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) return null;

        final int[] pivotIds = (int[]) meta.get("pivotIds");
        final IntList termIds = new IntList(topTerms.size());
        for (final TermEntry term : topTerms) {
            final int termId = term.termId();
            if (pivotIds != null && Arrays.binarySearch(pivotIds, termId) >= 0) continue;
            termIds.push(termId);
        }

        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final Query filterQuery = filterQuery(index, pars);
        final String contentFname = pars.getString(FTEXT, index.content());
        final FlucText contentFluc = index.flucText(contentFname);
        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
        final boolean directed = pars.getBoolean("directed", false);

        final IntMatrixById coocMat = new IntMatrixById(termIds);
        final CoocMatSnippets recorder = new CoocMatSnippets(coocMat, contentFluc.termRail(), left, right, directed);
        new SpanWalker(index.searcher(), spanQuery, new Snippets(Snippets.Usage.POSITIONS, slop), filterQuery).walk(recorder);

        final int n = coocMat.length();
        final int dims = 2;
        final long[] freq = new long[n];
        final long[] rowSum = new long[n];
        final long[][] o = new long[n][n];
        long total = 0L;
        for (int a = 0; a < n; a++) {
            freq[a] = coocMat.countByRank(a, a);
            for (int b = 0; b < n; b++) {
                if (a == b) continue;
                final long v = (long) coocMat.countByRank(a, b) + coocMat.countByRank(b, a);
                o[a][b] = v;
                rowSum[a] += v;
                total += v;
            }
        }

        final AssociationMeasure residual = residual(pars);
        final double[][] s = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b) continue;
                final double v = residual.score(o[a][b], rowSum[a], rowSum[b], total);
                s[a][b] = Double.isFinite(v) ? v : 0d;
            }
        }

        final SingularValueDecomposition svd =
            new SingularValueDecomposition(new Array2DRowRealMatrix(s, false));
        final double[] sv = svd.getSingularValues();
        final RealMatrix u = svd.getU();
        double sumSq = 0d;
        for (final double x : sv) sumSq += x * x;
        final double[] inertia = new double[dims];
        for (int k = 0; k < dims; k++) inertia[k] = 100d * sv[k] * sv[k] / sumSq;
        final double[][] xy = new double[n][dims];
        for (int a = 0; a < n; a++)
            for (int k = 0; k < dims; k++)
                xy[a][k] = u.getEntry(a, k) * sv[k];

        final TermLexicon lexicon = contentFluc.termLexicon();
        final String[] form = new String[n];
        for (int a = 0; a < n; a++) form[a] = lexicon.form(coocMat.id(a));

        return new CoocMap(form, freq, xy, inertia);
    }

    /**
     * Residual measure for the map, from {@code assoc}. Only measures centred on
     * independence give a meaningful spectral map, so the choice is Pearson (χ²,
     * the default) or log-likelihood (G²); any other value falls back to Pearson.
     *
     * @param pars request parameters
     * @return the residual measure
     */
    private static AssociationMeasure residual(final HttpPars pars) {
        return switch (pars.getString("assoc", "pearson")) {
            case "g2" -> new AssociationMeasure.LogLikelihood();
            case "ppmi" -> new AssociationMeasure.Ppmi();
            case "npmi" -> new AssociationMeasure.Npmi();
            case "logdice" -> new AssociationMeasure.LogDice();
            case "raw" -> new AssociationMeasure.Raw();
            default -> new AssociationMeasure.Pearson();
        };
    }
}
