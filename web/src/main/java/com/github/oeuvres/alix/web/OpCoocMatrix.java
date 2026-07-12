package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.lucene.terms.TopTerms.TermValue.FREQ;
import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.SingularValueDecomposition;
import org.hipparchus.special.Gamma;

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
 * With identical row and column vocabularies, the map follows the historical
 * symmetric geometry, so {@code directed} does not reach that geometry:
 * folding the directed matrix onto its transpose is exactly what symmetrisation
 * does, and the undirected factor of two washes out under the scale invariance
 * of correspondence-style analysis. It is not mass-rescaled Correspondence
 * Analysis: the {@code 1/√mass} step is omitted, which is what stops
 * low-frequency terms being flung to the rim and yields the more even spread.
 * </p>
 *
 * <p>
 * For square tables, the expectation model is symmetric quasi-independence, {@code e(a,b) =
 * γ(a)·γ(b)} on the off-diagonal cells, fitted by {@link #ipf} — the correct
 * treatment of the structural zeros on the diagonal, of which the plain margin
 * product {@code rowSum(a)·rowSum(b)/total} is only the zeroth iteration. Cells
 * are add-k smoothed before the fit, to
 * stop small-count Pearson residuals injecting noise inertia into the spectrum.
 * The k is fitted on the observed counts by empirical Bayes
 * ({@link #smoothFit}) unless the {@code smooth} parameter carries an explicit
 * non-negative value.
 * </p>
 *
 * <p>
 * Three residuals are exposed through {@code assoc}: {@code pearson} (default,
 * χ², variance-stabilised, favours well-attested contrasts), {@code g2}
 * (deviance, same family, tamer on small expectations) and {@code logratio}
 * (double-centred logs, Lewi's spectral map — multiplicative contrasts weigh
 * evenly, so rarer vocabulary carries more of the geometry). The first two
 * residualise against the multiplicative IPF fit cell by cell; the last centres
 * additively in log space over the whole table.
 * </p>
 *
 * <p>
 * Up to {@link #DIMS} axes are emitted, each with a deterministic sign (the
 * point of largest absolute coordinate is positive), so maps are visually
 * comparable across runs and parameter settings. Each node carries its
 * {@code cos²} in the drawn plane — the share of the node's own inertia the
 * first two axes capture — so a client can distinguish a genuinely neutral
 * central point from a badly represented one.
 * </p>
 */
public final class OpCoocMatrix extends Op
{

    /** Maximum number of axes emitted; clients cluster on all, draw the first two. */
    private static final int DIMS = 6;

    /**
     * Immutable result of a co-occurrence map: parallel arrays indexed by rank, plus
     * the inertia share of each axis. Produced by {@link OpCoocMatrix#compute} and
     * consumed by {@link CoocMatUtil}. Holds no Lucene or servlet state, so it is
     * trivially serialisable to any format.
     *
     * @param form    term form, by rank
     * @param freq    occurrence marginal f(a), by row rank; read from the
     *                historical diagonal in square mode and from the query population
     *                in rectangular mode
     * @param coords  coordinates, {@code coords[rank][axis]}, axes sign-fixed;
     *                the drawn plane is axes 0 and 1
     * @param cos2    per node, share of its inertia carried by the drawn plane,
     *                in {@code [0, 1]} — the representation-quality diagnostic
     * @param inertia percentage of total inertia carried by each axis
     */
    record CoocMap(String[] form, long[] freq, double[][] coords, double[] cos2, double[] inertia) {}


    /**
     * Runs the whole pipeline — term selection, span walk, smoothing, IPF fit,
     * residual matrix, SVD — and returns the layout, or {@code null} when there
     * is nothing to draw (no top terms, or no span query). Format-independent;
     * callers serialise the result. The SVD math is the version verified against
     * a numpy reference; it additionally keeps the diagonal occurrence marginal
     * as {@code freq} for display and the leading inertia shares for diagnostics
     * and axis captions.
     *
     * <p>
     * Order of operations is deliberate: fit k on the raw distinct-pair counts
     * when no explicit {@code smooth} was given ({@link #smoothFit}), smooth
     * every off-diagonal cell, take the margins of the smoothed table, fit γ
     * on those margins, then residualise each cell against
     * {@code γ(a)·γ(b)} — or, in {@code logratio}
     * mode, skip the multiplicative fit and centre additively in log space
     * ({@link #logRatio}). Smoothing only zero cells
     * would bias the margins; a flat add-k prior does not. {@code freq} is the
     * separate occurrence marginal read from the diagonal, which
     * {@code CoocMatSnippets} fills; it is not smoothed and not part of the
     * model. {@code pivotIds} is present in {@code meta} only in co-occurrence
     * mode, so its use is guarded.
     * </p>
     *
     * <p>
     * Post-SVD: the {@code cos²} denominator is the node's full inertia over all
     * axes (row norm of the residual matrix, an exact identity of the SVD), so
     * it is computed before truncation to {@link #DIMS}. Sign-fixing negates
     * whole columns, which changes no distance and no inertia.
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
        
        final TopCoocSnippets consumer = new TopCoocSnippets(
            contentFluc.termStats(),
            contentFluc.termRail(),
            left,
            right);
        consumer.bindTo(topTerms.buffers());
        walker.walk(consumer);
        topTerms.setTotals(consumer.coocTokens(), consumer.coocDocsTotal());
        meta.put("pivotIds", pivotIds);
        meta.put("fieldWidth", contentFluc.termStats().fieldWidth());
        meta.put("fieldTokens", contentFluc.termStats().fieldTokens());
        meta.put("focusTokens", consumer.coocTokens());
        meta.put("focusDocs", consumer.coocDocsTotal());
        meta.put("hits", walker.hits());
        
        final TermFlag tflag = pars.getEnum(TFLAG, TermFlag.NULL);
        final KeynessScorer scorer = tsort(pars);
        
        // get sorted rows, maybe filtered by a flag, will be the rows of interest to display
        // Should we filter pivots now or after?
        topTerms.rank(scorer, terms, tflag).promote(pivotIds, FREQ);
        final IntList termIds = new IntList(topTerms.size());
        BitSet rows = new BitSet(contentLexicon.vocabSize());
        for (final TermEntry term : topTerms) {
            final int termId = term.termId();
            // shall we cut pivotIds from rows for SVD? Does it add signal or noise?
            if (pivotIds != null && Arrays.binarySearch(pivotIds, termId) >= 0) continue;
            rows.set(termId);
            termIds.push(termId);
        }
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
            topTerms.rank(scorer, terms+cols, tflag).promote(pivotIds, FREQ);
            // here termIds contains rows, add other terms
            for (final TermEntry term : topTerms) {
                final int termId = term.termId();
                if (rows.get(termId)) continue;
                termIds.push(termId);
            }
            colIds = termIds.toUniq();
        }
        final IntMatrixById coocMat = new IntMatrixById(rowIds, colIds);
        final boolean directed = pars.getBoolean("directed", false);
        final CoocMatSnippets recorder = new CoocMatSnippets(coocMat, contentFluc.termRail(), left, right, directed);
        new SpanWalker(index.searcher(), spanQuery, new DocSnippets(DocSnippets.Usage.POSITIONS, slop), filterQuery).walk(recorder);
        
        // Convert the collected counts into a normalized residual matrix, run
        // its SVD, and retain coordinates only for the displayed row terms.
        // When both axes are identical, coocMap() deliberately delegates to the
        // former symmetric implementation so that cols=0 remains compatible.
        return coocMap(coocMat, topTerms, contentLexicon, pars, directed);
    }

    /**
     * Builds the map from a collected row-by-column co-occurrence matrix.
     *
     * <p>
     * Square and rectangular matrices deliberately follow the same normalization
     * path. When both axes contain the same ids, the two-axis structural-zero
     * model reduces to the square quasi-independence model. This avoids changing
     * the association measure merely because one feature column was added.
     * </p>
     *
     * @param coocMat collected co-occurrence matrix
     * @param topTerms query-population term counts
     * @param lexicon term lexicon
     * @param pars request parameters
     * @param directed retained for the collector API; normalization uses the
     *        cells as collected
     * @return co-occurrence map for the row terms
     */
    private static CoocMap coocMap(
        final IntMatrixById coocMat,
        final TopTerms topTerms,
        final TermLexicon lexicon,
        final HttpPars pars,
        final boolean directed
    ) {
        return coocMapRectangular(coocMat, topTerms, lexicon, pars);
    }

    /**
     * Normalizes a square or rectangular co-occurrence table and maps its row profiles.
     *
     * <p>
     * Self-term cells are structural zeros identified by equality of ids, not by
     * equality of ranks. Pearson and deviance residuals use the fitted rectangular
     * quasi-independence table. Log-ratio residuals use separate additive row and
     * column effects.
     * </p>
     *
     * @param coocMat row-by-column co-occurrence matrix
     * @param topTerms query-population term counts
     * @param lexicon term lexicon
     * @param pars request parameters
     * @return map for the row terms
     */
    private static CoocMap coocMapRectangular(
        final IntMatrixById coocMat,
        final TopTerms topTerms,
        final TermLexicon lexicon,
        final HttpPars pars
    ) {
        final int rowCount = coocMat.rowCount();
        final int colCount = coocMat.colCount();
        final long[] freq = new long[rowCount];
        final boolean[][] admissible = new boolean[rowCount][colCount];
        final double[][] observed = new double[rowCount][colCount];

        // Step 1. Copy raw counts. Row frequencies come from the query population
        // because a rectangular matrix has no diagonal-marginal convention.
        int maxCount = 0;
        long admissibleCells = 0L;
        long rawTotal = 0L;
        for (int row = 0; row < rowCount; row++) {
            final int rowId = coocMat.rowId(row);
            freq[row] = topTerms.termFreq(rowId);
            for (int col = 0; col < colCount; col++) {
                if (rowId == coocMat.colId(col)) {
                    continue;
                }
                admissible[row][col] = true;
                admissibleCells++;
                final int count = coocMat.countByRank(row, col);
                observed[row][col] = count;
                rawTotal += count;
                if (count > maxCount) {
                    maxCount = count;
                }
            }
        }

        // Step 2. Fit or read add-k smoothing over all admissible rectangular cells.
        double smooth = pars.getDouble(SMOOTH, 1d);
        if (smooth < 0d) {
            final long[] histogram = new long[maxCount + 1];
            for (int row = 0; row < rowCount; row++) {
                for (int col = 0; col < colCount; col++) {
                    if (!admissible[row][col]) {
                        continue;
                    }
                    histogram[(int) observed[row][col]]++;
                }
            }
            smooth = smoothFit(histogram, admissibleCells, rawTotal);
        }

        // Step 3. Smooth all admissible cells and compute both sets of margins.
        final double[] rowSum = new double[rowCount];
        final double[] colSum = new double[colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (!admissible[row][col]) {
                    continue;
                }
                observed[row][col] += smooth;
                rowSum[row] += observed[row][col];
                colSum[col] += observed[row][col];
            }
        }

        // Step 4. Remove row and column mass effects before SVD.
        final String assoc = pars.getString("assoc", "g2");
        final double[][] residual;
        if ("logratio".equals(assoc)) {
            residual = logRatio(observed, admissible);
        }
        else {
            final double[][] expected = ipf(rowSum, colSum, admissible);
            residual = new double[rowCount][colCount];
            for (int row = 0; row < rowCount; row++) {
                for (int col = 0; col < colCount; col++) {
                    if (!admissible[row][col]) {
                        continue;
                    }
                    final double value = residualCell(
                        assoc, observed[row][col], expected[row][col]);
                    residual[row][col] = Double.isFinite(value) ? value : 0d;
                }
            }
        }

        return svdMap(residual, freq, coocMat.rowIds(), lexicon);
    }

    /**
     * Fits a rectangular independence table while respecting structural zeros.
     *
     * @param rowSum target row margins
     * @param colSum target column margins
     * @param admissible cells included in the model
     * @return fitted expected counts
     */
    private static double[][] ipf(
        final double[] rowSum,
        final double[] colSum,
        final boolean[][] admissible
    ) {
        final int rowCount = rowSum.length;
        final int colCount = colSum.length;
        final double[][] expected = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (admissible[row][col]) {
                    expected[row][col] = 1d;
                }
            }
        }

        for (int iteration = 0; iteration < 500; iteration++) {
            double error = 0d;

            for (int row = 0; row < rowCount; row++) {
                double fitted = 0d;
                for (int col = 0; col < colCount; col++) {
                    fitted += expected[row][col];
                }
                if (fitted <= 0d) {
                    continue;
                }
                final double factor = rowSum[row] / fitted;
                for (int col = 0; col < colCount; col++) {
                    expected[row][col] *= factor;
                }
            }

            for (int col = 0; col < colCount; col++) {
                double fitted = 0d;
                for (int row = 0; row < rowCount; row++) {
                    fitted += expected[row][col];
                }
                if (fitted <= 0d) {
                    continue;
                }
                final double factor = colSum[col] / fitted;
                for (int row = 0; row < rowCount; row++) {
                    expected[row][col] *= factor;
                }
            }

            for (int row = 0; row < rowCount; row++) {
                double fitted = 0d;
                for (int col = 0; col < colCount; col++) {
                    fitted += expected[row][col];
                }
                error = Math.max(
                    error, Math.abs(fitted - rowSum[row]) / (rowSum[row] + 1e-12));
            }
            if (error < 1e-10) {
                break;
            }
        }
        return expected;
    }

    /**
     * Computes rectangular log-ratio residuals with separate row and column effects.
     *
     * @param observed positive smoothed counts
     * @param admissible cells included in the fit
     * @return double-centred logarithms
     */
    private static double[][] logRatio(
        final double[][] observed,
        final boolean[][] admissible
    ) {
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] alpha = new double[rowCount];
        final double[] beta = new double[colCount];

        for (int iteration = 0; iteration < 500; iteration++) {
            double error = 0d;

            for (int row = 0; row < rowCount; row++) {
                double sum = 0d;
                int count = 0;
                for (int col = 0; col < colCount; col++) {
                    if (!admissible[row][col] || observed[row][col] <= 0d) {
                        continue;
                    }
                    sum += Math.log(observed[row][col]) - beta[col];
                    count++;
                }
                if (count > 0) {
                    final double next = sum / count;
                    error = Math.max(error, Math.abs(next - alpha[row]));
                    alpha[row] = next;
                }
            }

            for (int col = 0; col < colCount; col++) {
                double sum = 0d;
                int count = 0;
                for (int row = 0; row < rowCount; row++) {
                    if (!admissible[row][col] || observed[row][col] <= 0d) {
                        continue;
                    }
                    sum += Math.log(observed[row][col]) - alpha[row];
                    count++;
                }
                if (count > 0) {
                    final double next = sum / count;
                    error = Math.max(error, Math.abs(next - beta[col]));
                    beta[col] = next;
                }
            }

            // Fix the arbitrary additive origin without changing alpha + beta.
            double mean = 0d;
            for (final double value : alpha) {
                mean += value;
            }
            mean /= rowCount;
            for (int row = 0; row < rowCount; row++) {
                alpha[row] -= mean;
            }
            for (int col = 0; col < colCount; col++) {
                beta[col] += mean;
            }

            if (error < 1e-10) {
                break;
            }
        }

        final double[][] residual = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (!admissible[row][col] || observed[row][col] <= 0d) {
                    continue;
                }
                residual[row][col] =
                    Math.log(observed[row][col]) - alpha[row] - beta[col];
            }
        }
        return residual;
    }

    /**
     * Runs SVD and builds row coordinates, representation quality, and inertia.
     *
     * @param residual normalized row-by-column matrix
     * @param freq display frequency by row rank
     * @param rowIds row ids by rank
     * @param lexicon term lexicon
     * @return map of the row terms
     */
    private static CoocMap svdMap(
        final double[][] residual,
        final long[] freq,
        final int[] rowIds,
        final TermLexicon lexicon
    ) {
        final int rowCount = residual.length;
        final SingularValueDecomposition svd =
            new SingularValueDecomposition(new Array2DRowRealMatrix(residual, false));
        final double[] singularValues = svd.getSingularValues();
        final RealMatrix u = svd.getU();

        // Step 5. Convert squared singular values to axis inertia percentages.
        double totalInertia = 0d;
        for (final double value : singularValues) {
            totalInertia += value * value;
        }
        final int inertiaLength = Math.min(10, singularValues.length);
        final double[] inertia = new double[inertiaLength];
        if (totalInertia > 0d) {
            for (int axis = 0; axis < inertiaLength; axis++) {
                inertia[axis] = 100d * singularValues[axis] * singularValues[axis]
                    / totalInertia;
            }
        }

        // Step 6. Principal row coordinates are U times the singular values.
        final int dims = Math.min(DIMS, singularValues.length);
        final double[][] coords = new double[rowCount][dims];
        final double[] cos2 = new double[rowCount];
        for (int row = 0; row < rowCount; row++) {
            double denominator = 0d;
            for (int axis = 0; axis < singularValues.length; axis++) {
                final double coordinate =
                    u.getEntry(row, axis) * singularValues[axis];
                denominator += coordinate * coordinate;
                if (axis < dims) {
                    coords[row][axis] = coordinate;
                }
            }
            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, dims); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }

        // Step 7. Fix each emitted axis sign deterministically.
        for (int axis = 0; axis < dims; axis++) {
            int greatest = 0;
            for (int row = 1; row < rowCount; row++) {
                if (Math.abs(coords[row][axis]) > Math.abs(coords[greatest][axis])) {
                    greatest = row;
                }
            }
            if (coords[greatest][axis] < 0d) {
                for (int row = 0; row < rowCount; row++) {
                    coords[row][axis] = -coords[row][axis];
                }
            }
        }

        // Step 8. Attach display forms to row ranks.
        final String[] form = new String[rowCount];
        for (int row = 0; row < rowCount; row++) {
            form[row] = lexicon.form(rowIds[row]);
        }
        return new CoocMap(form, freq, coords, cos2, inertia);
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
        final CoocMap map = compute(index, pars, meta);
        final Writer writer = response.getWriter();
        if (map == null) {
            meta.toString(writer, pars);
            return;
        }
        final DecimalFormat fmt = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));
        writer.append("form,freq,x,y,cos2\n");
        for (int i = 0; i < map.form().length; i++) {
            writer.append(csvCell(map.form()[i])).append(',')
                  .append(Long.toString(map.freq()[i])).append(',')
                  .append(fmt.format(map.coords()[i][0])).append(',')
                  .append(fmt.format(map.coords()[i][1])).append(',')
                  .append(fmt.format(map.cos2()[i])).append('\n');
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

    /**
     * Fits the symmetric quasi-independence model {@code e(a,b) = γ(a)·γ(b)},
     * {@code a ≠ b}, to the off-diagonal margins by iterative proportional
     * fitting, so the structural zeros on the diagonal no longer bias the
     * expected counts. The update is Gauss–Seidel — each new γ is used
     * immediately, with the running sum maintained incrementally — so a table of
     * this size converges in a handful of sweeps. Iteration zero (the
     * initialisation) reproduces the plain margin model
     * {@code e = rowSum(a)·rowSum(b)/total}. Rows with a zero margin get
     * {@code γ = 0} and drop out of the expectations.
     *
     * @param rowSum off-diagonal row sums of the smoothed symmetric matrix
     * @return the multipliers γ, indexed by rank
     */
    private static double[] ipf(final double[] rowSum) {
        final int n = rowSum.length;
        double total = 0d;
        for (final double m : rowSum) total += m;
        if (total <= 0d) return new double[n];
        final double[] gamma = new double[n];
        final double norm = Math.sqrt(total);
        for (int a = 0; a < n; a++) gamma[a] = rowSum[a] / norm;
        double gSum = 0d;
        for (final double g : gamma) gSum += g;
        for (int iter = 0; iter < 200; iter++) {
            double err = 0d;
            for (int a = 0; a < n; a++) {
                final double denom = gSum - gamma[a];
                if (denom <= 0d) continue;
                final double next = rowSum[a] / denom;
                err = Math.max(err, Math.abs(next - gamma[a]) / (next + 1e-12));
                gSum += next - gamma[a];
                gamma[a] = next;
            }
            if (err < 1e-10) break;
        }
        return gamma;
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
                final int dims = map.coords()[0].length;
                jw.name("data");
                jw.beginObject();
                jw.name("axes");
                jw.beginObject();
                jw.name("dims").value(dims);
                jw.name("dim1_pct").value(round(map.inertia()[0], 1));
                jw.name("dim2_pct").value(round(map.inertia()[1], 1));
                jw.name("cum2_pct").value(round(map.inertia()[0] + map.inertia()[1], 1));
                jw.name("spectrum");
                jw.beginArray();
                for (final double pct : map.inertia()) {
                    jw.value(round(pct, 1));
                }
                jw.endArray();
                jw.endObject();
                jw.name("nodes");
                jw.beginArray();
                for (int i = 0; i < map.form().length; i++) {
                    jw.beginObject();
                    jw.name("id").value(map.form()[i]);
                    jw.name("freq").value(map.freq()[i]);
                    jw.name("x").value(round(map.coords()[i][0], 4));
                    jw.name("y").value(round(map.coords()[i][1], 4));
                    jw.name("cos2").value(round(map.cos2()[i], 4));
                    jw.name("coords");
                    jw.beginArray();
                    for (int k = 0; k < dims; k++) {
                        jw.value(round(map.coords()[i][k], 4));
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

    /**
     * Log-ratio (spectral-map) residual matrix: the log of each off-diagonal
     * cell, double-centred by the least-squares fit of the additive
     * quasi-independence model {@code log o(a,b) ≈ α(a) + α(b)}, {@code a ≠ b}
     * — the exact additive analogue of the multiplicative fit in {@link #ipf},
     * solved by the same Gauss–Seidel scheme (the normal equation for each α,
     * others held fixed, is closed-form). Double-centring is what makes the log
     * usable as map geometry: it removes the size factor that dominates the SVD
     * of any near-non-negative matrix, and preserves the independence centring
     * that clipping (PPMI) destroys. Relative to the Pearson residual, all
     * multiplicative contrasts weigh the same regardless of expected count, so
     * rare-word oppositions carry more inertia — lexical specificity rather
     * than bulk co-occurrence.
     *
     * <p>
     * Cells must be positive to take the log; upstream add-k smoothing
     * guarantees that except at {@code smooth=0}, where non-positive cells are
     * excluded from the fit and residualised at 0 — treated as uninformative
     * rather than infinitely repelled. The fit is unweighted, consistent with
     * the omitted {@code 1/√mass} step of the rest of the class (Lewi's
     * spectral map rather than Greenacre's weighted log-ratio analysis).
     * </p>
     *
     * @param o smoothed off-diagonal counts, symmetric, zero diagonal
     * @return the double-centred log matrix, zero diagonal
     */
    private static double[][] logRatio(final double[][] o) {
        final int n = o.length;
        final double[] alpha = new double[n];
        final double[] rowSumL = new double[n];
        final int[] rowLen = new int[n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b || o[a][b] <= 0d) continue;
                rowSumL[a] += Math.log(o[a][b]);
                rowLen[a]++;
            }
        }
        for (int a = 0; a < n; a++) {
            if (rowLen[a] > 0) alpha[a] = rowSumL[a] / (2d * rowLen[a]);
        }
        for (int iter = 0; iter < 200; iter++) {
            double err = 0d;
            for (int a = 0; a < n; a++) {
                if (rowLen[a] == 0) continue;
                double cross = 0d;
                for (int b = 0; b < n; b++) {
                    if (b == a || o[a][b] <= 0d) continue;
                    cross += alpha[b];
                }
                final double next = (rowSumL[a] - cross) / rowLen[a];
                err = Math.max(err, Math.abs(next - alpha[a]));
                alpha[a] = next;
            }
            if (err < 1e-10) break;
        }
        final double[][] s = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a == b || o[a][b] <= 0d) continue;
                s[a][b] = Math.log(o[a][b]) - alpha[a] - alpha[b];
            }
        }
        return s;
    }

    /**
     * Signed cell residual of the observed count against the fitted expectation.
     * Pearson is {@code (o − e)/√e}; G² is the Poisson deviance residual
     * {@code sign(o − e)·√(2·(o·ln(o/e) − o + e))}, which stays bounded where the
     * Pearson residual explodes on small expectations. The {@code − o + e} term
     * is what keeps zero cells finite: at {@code o = 0} the deviance is
     * {@code 2e}, residual {@code −√(2e)}. {@code "logratio"} never reaches
     * this method — it is not a per-cell function of {@code (o, e)}, its
     * centring is a fit over the whole table, handled by {@link #logRatio}. Any
     * other {@code assoc} value is served as Pearson: only independence-centred
     * measures give a meaningful spectral map.
     *
     * @param assoc residual name, {@code "pearson"} or {@code "g2"}
     * @param o     observed, smoothed count
     * @param e     expected count under the fitted model
     * @return the signed residual, 0 when {@code e} is not positive
     */
    private static double residualCell(final String assoc, final double o, final double e) {
        if (e <= 0d) return 0d;
        if ("g2".equals(assoc)) {
            final double dev = 2d * ((o > 0d ? o * Math.log(o / e) : 0d) - o + e);
            return Math.copySign(Math.sqrt(Math.max(0d, dev)), o - e);
        }
        return (o - e) / Math.sqrt(e);
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
     * Empirical Bayes add-k: the concentration of a symmetric Dirichlet prior
     * over the off-diagonal cells, fitted on the raw counts by maximising the
     * Dirichlet-multinomial marginal likelihood (Minka's fixed point). The
     * numerator {@code Σ ψ(c + k) − ψ(k)} telescopes into partial harmonic
     * sums for integer counts, so one iteration costs {@code O(maxCount)} and
     * the whole fit a few dozen µs at map sizes — invisible next to the span
     * walk. The model treats cells as exchangeable, which co-occurrence cells
     * are not: the estimate is a data-driven default, not an oracle, and an
     * explicit non-negative {@code smooth} parameter bypasses it.
     *
     * @param hist  {@code hist[c]} = number of distinct off-diagonal cells with raw count {@code c}
     * @param cells number of distinct off-diagonal cells, {@code n·(n−1)/2}
     * @param total sum of the raw counts over those cells
     * @return the fitted k, clamped to {@code [0.001, 10]}; 0.5 (the Jeffreys
     *         prior) when there is nothing to fit
     */
    private static double smoothFit(final long[] hist, final long cells, final long total) {
        if (cells <= 0 || total <= 0) return 0.5;
        double k = 0.5;
        for (int iter = 0; iter < 100; iter++) {
            double num = 0d;
            double harmonic = 0d;
            for (int c = 1; c < hist.length; c++) {
                harmonic += 1d / (c - 1 + k);
                if (hist[c] > 0) num += hist[c] * harmonic;
            }
            final double den = cells * (Gamma.digamma(total + cells * k) - Gamma.digamma(cells * k));
            final double next = Math.min(10d, Math.max(0.001d, k * num / den));
            if (Math.abs(next - k) < 1e-6) return next;
            k = next;
        }
        return k;
    }

}
