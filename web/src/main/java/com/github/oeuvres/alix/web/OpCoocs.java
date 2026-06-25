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
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.util.AssociationMeasure;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OpCoocs extends Op
{
    /**
     * Two-dimensional layout of the node set by SVD of a residual matrix — the
     * spectral-map reading of the co-occurrence table. The matrix is symmetrized
     * as O(a,b) = countByRank(a,b) + countByRank(b,a) with a zeroed diagonal
     * (counts stay int; the analysis is scale-invariant, so the missing ÷2 is
     * irrelevant), each off-diagonal cell is turned into a residual by
     * {@code measure}, and the first {@code dims} left singular vectors scaled by
     * their singular values give the coordinates, indexed by rank.
     *
     * <p>
     * This is not mass-rescaled Correspondence Analysis: it omits the 1/√mass step,
     * which is exactly what stops low-frequency nodes being flung to the rim and
     * yields the more even spread. Pass {@link AssociationMeasure.Pearson} for the
     * χ²-residual map or {@link AssociationMeasure.LogLikelihood} for the G² one.
     * The seam is meaningful only for residuals centred on independence (these
     * two): a non-centred measure such as {@link AssociationMeasure.Raw} leaves
     * overall magnitude in the first axis. Non-finite cell scores are read as zero
     * so a stray −∞ cannot poison the decomposition.
     * </p>
     *
     * <p>
     * Each axis is fixed only up to sign, so the map may mirror between calls;
     * anchor it caller-side by flipping a column if a chosen term must fall on a
     * chosen side. Singular values come back descending, so axes 0 and 1 are the
     * two largest; the inertia caption of axis k is 100·σ_k²/Σσ².
     * </p>
     *
     * @param mat     co-occurrence counts over the node set
     * @param measure per-cell residual, centred on independence
     * @param dims    number of axes, {@code >= 1}
     * @return        coord[rank][axis], length() × dims
     */
    private static double[][] svd(
        final IntMatrixById mat,
        final AssociationMeasure measure,
        final int dims
    ) {
        final int n = mat.length();
        final long[][] o = new long[n][n];
        final long[] f = new long[n];
        long total = 0L;
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b) continue;
                final long v = (long) mat.countByRank(a, b) + mat.countByRank(b, a);
                o[a][b] = v;
                f[a] += v;
                total += v;
            }
        }

        final double[][] s = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b) continue;
                final double v = measure.score(o[a][b], f[a], f[b], total);
                s[a][b] = Double.isFinite(v) ? v : 0d;
            }
        }

        final SingularValueDecomposition svd =
            new SingularValueDecomposition(new Array2DRowRealMatrix(s, false));
        final double[] sv = svd.getSingularValues();
        final RealMatrix u = svd.getU();
        final double[][] coord = new double[n][dims];
        for (int a = 0; a < n; a++) {
            for (int k = 0; k < dims; k++) {
                coord[a][k] = u.getEntry(a, k) * sv[k];
            }
        }
        return coord;
    }
    
    @Override
    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    )
        throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        TopTerms topTerms = OpTerms.topTerms(index, pars, meta);

        final Writer writer = response.getWriter();
        if (topTerms == null) {
            meta.toString(writer, pars);
            return;
        }

        // topTerms may contain pivots
        int[] pivotIds = (int[]) meta.get("pivotIds");
        final IntList termIds = new IntList(topTerms.size());
        for (TermEntry term : topTerms) {
            final int termId = term.termId();
            if (Arrays.binarySearch(pivotIds, termId) >= 0)
                continue;
            termIds.push(termId);
        }
        final int terms = termIds.size();
        final boolean directed = pars.getBoolean("directed", false);

        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        Snippets snippets = new Snippets(Snippets.Usage.POSITIONS, slop);
        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        final String contentFname = pars.getString(FTEXT, index.content());
        final FlucText contentFluc = index.flucText(contentFname);

        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
        final IntMatrixById coocMat = new IntMatrixById(termIds);
        final CoocMatSnippets coocRecorder = new CoocMatSnippets(
                coocMat, contentFluc.termRail(), left, right, directed
        );

        if (spanQuery == null) {
            writer.append("TODO\n");
            meta.toString(writer, pars);
            return;
        }

        final SpanWalker walker = new SpanWalker(
                index.searcher(), spanQuery, snippets, filterQuery
        );
        walker.walk(coocRecorder);

        final AssociationMeasure scorer = switch (pars.getString("assoc", "raw"))
            {
            case "g2" -> new AssociationMeasure.LogLikelihood();
            case "ppmi" -> new AssociationMeasure.Ppmi();
            case "npmi" -> new AssociationMeasure.Npmi();
            case "logdice" -> new AssociationMeasure.LogDice();
            case "raw" -> new AssociationMeasure.Raw();
            default -> new AssociationMeasure.Raw();
            };

        final long[] rowMargin = new long[terms];
        final long[] colMargin = new long[terms];
        long total = 0L;
        for (int row = 0; row < terms; row++) {
            for (int col = 0; col < terms; col++) {
                if (row == col) continue; // diagonal is not part of the count
                final long v = coocMat.countByRank(row, col);
                rowMargin[row] += v;
                colMargin[col] += v;
                total += v;
            }
        }

        final DecimalFormat fmt = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));

        TermLexicon lexicon = contentFluc.termLexicon();
        for (int col = 0; col < terms; col++) {
            final int termId = coocMat.id(col);
            writer.append(',').append(lexicon.form(termId));
        }
        writer.append('\n');
        for (int row = 0; row < terms; row++) {
            final int termId = coocMat.id(row);
            writer.append(lexicon.form(termId));
            for (int col = 0; col < terms; col++) {
                double score;
                if (row == col)
                    score = 0;
                else
                    score = scorer.score(coocMat.countByRank(row, col), rowMargin[row], colMargin[col], total);
                if (score == Double.NEGATIVE_INFINITY) score = 0;
                writer.append(',').append(fmt.format(score));
            }
            writer.append('\n');
        }

    }

}
