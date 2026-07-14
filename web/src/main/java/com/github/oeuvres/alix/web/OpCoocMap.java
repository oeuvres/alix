package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.lucene.terms.TopTerms.TermValue.FREQ;
import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.Assoc;
import com.github.oeuvres.alix.maths.ContingencySvd.SvdLayout;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public final class OpCoocMap extends Op
{
    
    /**
     * Build the cooc matrix.
     * 
     * @param index
     * @param pars
     * @param meta
     * @return
     * @throws IOException
     */
    public static IntMatrixById coocMat(
        final LuceneIndex index,
        final HttpPars pars,
        final MetaUtil meta
    )
        throws IOException {
        final SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) return null;
        final Query filterQuery = filterQuery(index, pars);
        FlucText contentFluc = contentFluc(index, pars, meta);
        TermLexicon contentLexicon = contentFluc.termLexicon();
        TopTerms topTerms = contentFluc.topTerms();
        
        // pivotsIds
        final int[] pivotIds = contentFluc.termLexicon().termIds(spanQuery);
        // increment the topK of pivots
        final int terms = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);

        // same as for the span query parser
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            new DocSnippets(DocSnippets.Usage.POSITIONS, slop),
            filterQuery
        );
        
        final TopTerms.Population population = topTerms.beginPopulation();
        final TopCoocSnippets consumer = new TopCoocSnippets(
            contentFluc.termStats(),
            contentFluc.termRail(),
            left,
            right
        ).bindTo(population);
        consumer.bindTo(topTerms.buffers());
        walker.walk(consumer);
        consumer.subtractPivots(pivotIds); // should remove topTerms and simplify ranking
        consumer.complete();
        
        meta.put("pivotIds", pivotIds);
        meta.put("fieldWidth", contentFluc.termStats().fieldWidth());
        meta.put("fieldTokens", contentFluc.termStats().fieldTokens());
        meta.put("snippets", consumer.contextCount());
        meta.put("focusDocs", consumer.documentCount());
        meta.put("focusTokens", consumer.tokenCount());
        meta.put("hits", walker.hits());
        
        final TermFlag tflag = pars.getEnum(TFLAG, TermFlag.NULL);
        final TermFlag colflag = pars.getEnum("col-flag", TermFlag.NULL);
        final KeynessScorer scorer = tsort(pars);
        
        // get sorted rows, maybe filtered by a flag, will be the rows of interest to display
        // Should we filter pivots now or after?
        topTerms.rank(scorer, terms, tflag);
        final IntList termIds = new IntList(topTerms.size());
        BitSet rows = new BitSet(contentLexicon.vocabSize());
        String[] rowTopFreq = new String[terms];
        int i = 0;
        for (final TermEntry term : topTerms) {
            final int termId = term.termId();
            rows.set(termId);
            termIds.push(termId);
            rowTopFreq[i++] = term.form() + " (" + term.freq() + ") #" + term.termId();
        }
        meta.put("rowTopFreq", rowTopFreq);
        
        int[] rowIds = termIds.toUniq();

        // get more co-occurrents than the focus terms, without filtering flag, to add semantic signal
        // 0 = same as rows. Positive, cols added
        final int cols = pars.getInt("cols", new int[]{0, 200}, 0);
        final int[] colIds;
        if (cols == 0) {
            colIds = rowIds;
        }
        else {
            // keep same scorer? do raw sort add signal or noise?
            topTerms.rank(scorer, terms +cols, colflag); // TermFlag.NULL

            // here termIds contains rows, add other terms
            int k = 0;
            final String[] colForms = new String[cols];
            for (final TermEntry term : topTerms) {
                final int termId = term.termId();
                if (rows.get(termId)) continue;
                termIds.push(termId);
                colForms[k] = term.form() + " (" + term.freq() + " ; " + term.score() + ")";
                if (++k >= cols) break;
            }
            colIds = termIds.toUniq();
            meta.put("cols", colForms);
        }
        final IntMatrixById coocMat = new IntMatrixById(
            rowIds, 
            colIds,
            id -> contentLexicon.form(id)
        );
        final boolean directed = pars.getBoolean("directed", false);
        final CoocMatSnippets recorder = new CoocMatSnippets(coocMat, contentFluc.termRail(), left, right, directed);
        new SpanWalker(index.searcher(), spanQuery, new DocSnippets(DocSnippets.Usage.POSITIONS, slop), filterQuery).walk(recorder);
        return coocMat;
    }

    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final IntMatrixById coocMat = coocMat(index, pars, meta);
        final Writer writer = response.getWriter();
        if (coocMat == null) {
            meta.toString(writer, pars);
            return;
        }
        // final DecimalFormat fmt = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));
        final char sep = '\t';
        for (int colRank = 0; colRank < coocMat.colCount(); colRank++) {
            String label = coocMat.colLabelByRank(colRank);
            writer.append(sep).append(csvEscape(label));
        }
        writer.append('\n');
        for (int rowRank = 0; rowRank < coocMat.rowCount(); rowRank++) {
            String label = coocMat.rowLabelByRank(rowRank);
            writer.append(csvEscape(label));
            for (int colRank = 0; colRank < coocMat.colCount(); colRank++) {
                writer.append(sep).append("" + coocMat.countByRank(rowRank, colRank));
            }
            writer.append('\n');
        }


        /*
        for (String line: (String[])meta.get("rowTopFreq")) {
            writer.append(line + '\n');
        }*/
    }
    
    
    /**
     * Builds a semantic-map layout from HTTP-selected contingency-SVD options.
     *
     * <p>The upstream co-occurrence matrix is already fixed when this method is
     * called. The parameters therefore affect only smoothing, expectation,
     * association residuals, decomposition geometry, and emitted dimensions.</p>
     *
     * @param coocMat filled co-occurrence matrix
     * @param pars resolved HTTP parameters
     * @param meta response metadata collector
     * @return semantic-map layout
     */
    private static SvdLayout semanticMap(
        final IntMatrixById coocMat,
        final HttpPars pars,
        final MetaUtil meta
    ) {
        final ContingencySvd model = new ContingencySvd(coocMat);

        final double smooth = pars.getDouble("smooth", Double.NaN);
        if (Double.isNaN(smooth));
        else if (smooth < 0) model.smoothAuto();
        else model.smooth(smooth);

        final String expectation = pars.getString(
            "expect",
            "ipf",
            Set.of("ipf", "log")
        );
        if ("log".equals(expectation)) {
            model.expectLog();
        }
        else {
            model.expectIpf();
        }

        final String associationName = pars.getString(
            "assoc",
            "g2",
            Set.of("ft", "g2", "pearson", "pmi", "ppmi", "sppmi")
        );
        final Assoc association = Assoc.valueOf(associationName.toUpperCase(Locale.ROOT));
        final double shift = association == Assoc.SPPMI
            ? pars.getDouble("shift", 1d)
            : 1d;
        model.residual(association, shift);
        final double clip = pars.getDouble("clip", 0d);
        if (clip > 0d) {
            model.clipResiduals(clip);
        }
        model.decompose();
        final double weightAxes = pars.getDouble("weight-axes", 1);
        if (weightAxes > 0) model.weightAxes(weightAxes);

        final int dims = pars.getInt("dims", new int[] { 2, 50 }, 6);
        final SvdLayout map = model.project(dims);

        meta.put("svdFitConverged", model.fitConverged());
        meta.put("svdFitError", model.fitError());
        meta.put("svdFitIterations", model.fitIterations());
        meta.put("svdRank", map.inertia().length);
        if (!Double.isNaN(model.smoothK())) {
            meta.put("svdSmooth", model.smoothK());
        }

        return map;
    }

    /**
     * Writes the semantic-map response as JSON.
     *
     * @param index Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access or response writing fails
     */
    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    )
        throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final IntMatrixById coocMat = coocMat(index, pars, meta);
        final SvdLayout map = coocMat == null
            ? null
            : semanticMap(coocMat, pars, meta);

        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();

            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();

            if (map != null) {
                final int dims = map.coords().length == 0
                    ? 0
                    : map.coords()[0].length;
                final double[] spectrum = map.inertia();
                final double dim1 = spectrum.length > 0 ? spectrum[0] : 0d;
                final double dim2 = spectrum.length > 1 ? spectrum[1] : 0d;
                double emittedInertia = 0d;
                for (int axis = 0; axis < Math.min(dims, spectrum.length); axis++) {
                    emittedInertia += spectrum[axis];
                }

                jw.name("data");
                jw.beginObject();

                jw.name("axes");
                jw.beginObject();
                jw.name("dims").value(dims);
                jw.name("dim1_pct").value(round(dim1, 1));
                jw.name("dim2_pct").value(round(dim2, 1));
                jw.name("cum2_pct").value(round(dim1 + dim2, 1));
                jw.name("emitted_pct").value(round(emittedInertia, 1));
                jw.name("spectrum");
                jw.beginArray();
                for (final double pct : spectrum) {
                    jw.value(round(pct, 1));
                }
                jw.endArray();
                jw.endObject();

                jw.name("nodes");
                jw.beginArray();
                final int nodeCount = map.id().length;
                for (int node = 0; node < nodeCount; node++) {
                    final double[] coords = map.coords()[node];
                    final double x = coords.length > 0 ? coords[0] : 0d;
                    final double y = coords.length > 1 ? coords[1] : 0d;

                    jw.beginObject();
                    jw.name("id").value(map.id()[node]);
                    jw.name("form").value(map.label()[node]);
                    jw.name("freq").value(map.freq()[node]);
                    jw.name("x").value(round(x, 4));
                    jw.name("y").value(round(y, 4));
                    jw.name("cos2").value(round(map.cos2()[node], 4));
                    jw.name("coords");
                    jw.beginArray();
                    for (final double coordinate : coords) {
                        jw.value(round(coordinate, 4));
                    }
                    jw.endArray();
                    jw.endObject();
                }
                jw.endArray();

                jw.endObject();
            }

            jw.endObject();
        }
    }

}
