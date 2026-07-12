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
    record CoocMap(int[] id, String[] form, long[] freq, double[][] coords, double[] cos2, double[] inertia) {}

    /**
     * The association measures {@code assoc} selects, i.e. the map algorithms.
     * All of them centre the table against the same rectangular
     * quasi-independence fit of {@link #ipf} — an uncentred matrix has a
     * dominant size axis and gives no usable map — and differ only in the three
     * choices that matter: the scale the contrast is measured on (count, log),
     * whether contrasts are clipped, and whether row profiles are rescaled by
     * their mass afterwards.
     *
     * <p>
     * {@link #CA} is the only member that sets {@code massScale}: it is
     * {@link #PEARSON} plus the {@code 1/√mass} step of textbook correspondence
     * analysis, which restores the χ² metric and throws rare terms to the rim.
     * Since the rescaling multiplies a whole row of coordinates by one constant,
     * it moves points but leaves {@code cos²} and the inertia spectrum
     * untouched — CA and Pearson share a spectrum and differ only in layout.
     * </p>
     *
     * <p>
     * {@link #LOGRATIO} is the only member not built cell-by-cell from
     * {@code (o, e)}: its centring is an additive fit over the whole table
     * ({@link #logRatio}). {@link #PPMI} and {@link #SPPMI} clip negative
     * contrasts to zero, which discards the anti-associations that carry much
     * of a two-dimensional map's geometry; they are here as the distributional
     * semantics baseline, not as map candidates. {@code SPPMI} reads its shift
     * from the {@code shift} parameter (Levy &amp; Goldberg's {@code k},
     * default 1, i.e. plain PPMI).
     * </p>
     */
    enum Assoc
    {
        /** Correspondence analysis: {@link #PEARSON} with the χ² mass rescaling of row profiles. */
        CA(true),
        /** Poisson deviance residual, bounded where Pearson explodes on small expectations. */
        G2(false),
        /** Freeman–Tukey, the other classic variance-stabilised count residual. */
        FT(false),
        /** Lewi's spectral map: log cells, double-centred by an additive row/column fit. */
        LOGRATIO(false),
        /** Pearson χ² residual, {@code (o − e)/√e}; favours well-attested contrasts. */
        PEARSON(false),
        /** Pointwise mutual information, {@code log(o/e)}, signed and unclipped. */
        PMI(false),
        /** Positive PMI, {@code max(0, log(o/e))}. */
        PPMI(false),
        /** Shifted positive PMI, {@code max(0, log(o/e) − log shift)}. */
        SPPMI(false);

        /** Whether row coordinates are divided by {@code √mass} after the SVD. */
        final boolean massScale;

        Assoc(final boolean massScale) {
            this.massScale = massScale;
        }

        /**
         * Resolves the {@code assoc} parameter, case-insensitively; an unknown
         * name falls back to {@link #G2} rather than failing the request, since
         * this is a display endpoint.
         *
         * @param name parameter value
         * @return the measure
         */
        static Assoc of(final String name) {
            if (name == null) return G2;
            for (final Assoc assoc : values()) {
                if (assoc.name().equalsIgnoreCase(name)) return assoc;
            }
            return G2;
        }
    }

    /**
     * A residual matrix ready for the SVD, with the per-row rescaling to apply
     * to the coordinates it produces. {@code massScale} is {@code null} for
     * every measure but {@link Assoc#CA}, so the common path allocates nothing.
     *
     * @param matrix    residual cells, {@code [row][col]}, structural zeros at 0
     * @param massScale factor applied to each row's coordinates after the SVD,
     *                  or {@code null} for no rescaling
     */
    record Residual(double[][] matrix, double[] massScale) {}


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
            topTerms.rank(scorer, terms+cols, TermFlag.NULL).promote(pivotIds, FREQ);
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
        meta.put("freqs", coocMat.toString());
        // Convert the collected counts into a normalized residual matrix, run
        // its SVD, and retain coordinates only for the displayed row terms.
        return coocMap(coocMat, topTerms, contentLexicon, pars);
    }

    /**
     * Builds the map from a collected row-by-column co-occurrence matrix:
     * normalizes the table and maps its row profiles.
     *
     * <p>
     * Square and rectangular matrices deliberately follow the same normalization
     * path. When both axes contain the same ids, the two-axis structural-zero
     * model reduces to the square quasi-independence model, so {@code cols=0}
     * reproduces the former symmetric implementation exactly. This avoids
     * changing the association measure merely because one feature column was
     * added. Self-term cells are structural zeros identified by equality of
     * ids, not by equality of ranks. Pearson and deviance residuals use the
     * fitted rectangular quasi-independence table. Log-ratio residuals use
     * separate additive row and column effects.
     * </p>
     *
     * @param coocMat row-by-column co-occurrence matrix
     * @param topTerms query-population term counts
     * @param lexicon term lexicon
     * @param pars request parameters
     * @return map for the row terms
     */
    private static CoocMap coocMap(
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
        double smooth = pars.getDouble(SMOOTH, 0.5d);
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
        final Assoc assoc = Assoc.of(pars.getString("assoc", Assoc.G2.name()));
        final Residual residual = residual(assoc, observed, admissible, rowSum, colSum, pars);

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
     * Builds the residual matrix the SVD reads, for the chosen measure — the
     * one place where map algorithms are selected, so a new one costs an
     * {@link Assoc} constant and, unless it is a per-cell function of
     * {@code (o, e)} (in which case {@link #residualCell} suffices), a branch
     * here.
     *
     * <p>
     * All measures but {@link Assoc#LOGRATIO} residualise cell by cell against
     * the fitted expectation, so the {@link #ipf} fit is shared. The mass
     * rescaling of {@link Assoc#CA} is returned rather than applied, because it
     * belongs to the coordinates, not to the matrix: applying it to the cells
     * would change the SVD, applying it to the coordinates is exactly the
     * {@code D_r^{-1/2}} of correspondence analysis. Masses are taken from the
     * smoothed margins, consistent with the rest of the fit.
     * </p>
     *
     * @param assoc      chosen measure
     * @param observed   smoothed counts, {@code [row][col]}
     * @param admissible cells in the model, {@code false} on structural zeros
     * @param rowSum     smoothed row margins
     * @param colSum     smoothed column margins
     * @param pars       request parameters, read for measure-specific knobs
     * @return the residual matrix and its row rescaling
     */
    private static Residual residual(
        final Assoc assoc,
        final double[][] observed,
        final boolean[][] admissible,
        final double[] rowSum,
        final double[] colSum,
        final HttpPars pars
    ) {
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        if (assoc == Assoc.LOGRATIO) {
            return new Residual(logRatio(observed, admissible), null);
        }

        final double shift = assoc == Assoc.SPPMI ? Math.max(1d, pars.getDouble("shift", 1d)) : 1d;
        final double[][] expected = ipf(rowSum, colSum, admissible);
        final double[][] matrix = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (!admissible[row][col]) {
                    continue;
                }
                final double value = residualCell(
                    assoc, observed[row][col], expected[row][col], shift);
                matrix[row][col] = Double.isFinite(value) ? value : 0d;
            }
        }

        if (!assoc.massScale) {
            return new Residual(matrix, null);
        }
        double total = 0d;
        for (final double sum : colSum) {
            total += sum;
        }
        final double[] massScale = new double[rowCount];
        for (int row = 0; row < rowCount; row++) {
            final double mass = total > 0d ? rowSum[row] / total : 0d;
            massScale[row] = mass > 0d ? 1d / Math.sqrt(mass) : 0d;
        }
        return new Residual(matrix, massScale);
    }

    /**
     * Runs SVD and builds row coordinates, representation quality, and inertia.
     *
     * <p>
     * The mass rescaling of {@link Assoc#CA}, when present, multiplies all of a
     * row's coordinates by one constant, so it moves the point without touching
     * {@code cos²} (a ratio of that row's own coordinates) or the inertia
     * spectrum (read from the singular values of the unscaled matrix). It is
     * applied before the sign fixing, which reads the largest coordinate.
     * </p>
     *
     * @param residual normalized row-by-column matrix with its row rescaling
     * @param freq display frequency by row rank
     * @param rowIds row ids by rank
     * @param lexicon term lexicon
     * @return map of the row terms
     */
    private static CoocMap svdMap(
        final Residual residual,
        final long[] freq,
        final int[] rowIds,
        final TermLexicon lexicon
    ) {
        final double[][] matrix = residual.matrix();
        final double[] massScale = residual.massScale();
        final int rowCount = matrix.length;
        final SingularValueDecomposition svd =
            new SingularValueDecomposition(new Array2DRowRealMatrix(matrix, false));
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
            if (massScale != null) {
                for (int axis = 0; axis < dims; axis++) {
                    coords[row][axis] *= massScale[row];
                }
            }
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
        final int[] id = new int[rowCount];
        for (int row = 0; row < rowCount; row++) {
            id[row] = rowIds[row];
            form[row] = lexicon.form(rowIds[row]);
        }
        return new CoocMap(id, form, freq, coords, cos2, inertia);
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
                    jw.name("id").value(map.id()[i]);
                    jw.name("form").value(map.form()[i]);
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
     * Cell residual of an observed count against its fitted expectation, for
     * every measure that is a function of {@code (o, e)} alone — all of them
     * but {@link Assoc#LOGRATIO}, whose centring is a fit over the whole table
     * ({@link #logRatio}) and which therefore never reaches this method.
     *
     * <p>
     * The count-scale residuals differ only in how they tame small
     * expectations: {@link Assoc#PEARSON} is {@code (o − e)/√e}, which explodes
     * there; {@link Assoc#G2} is the Poisson deviance
     * {@code sign(o − e)·√(2·(o·ln(o/e) − o + e))}, where the {@code − o + e}
     * term is what keeps zero cells finite (at {@code o = 0} the deviance is
     * {@code 2e}, residual {@code −√(2e)}); {@link Assoc#FT} is Freeman–Tukey,
     * {@code √o + √(o+1) − √(4e+1)}, the classic variance-stabilising
     * alternative. {@link Assoc#CA} is Pearson — correspondence analysis
     * differs from the spectral map only by the mass rescaling of the
     * coordinates, not by the cell.
     * </p>
     *
     * <p>
     * The log-scale residuals measure the same contrast multiplicatively:
     * {@link Assoc#PMI} is {@code log(o/e)}, signed; {@link Assoc#PPMI} and
     * {@link Assoc#SPPMI} clip it at zero, {@code SPPMI} after subtracting
     * {@code log shift}. Clipping discards the anti-associations, which is
     * defensible for retrieval and lossy for a map.
     * </p>
     *
     * @param assoc chosen measure
     * @param o     observed, smoothed count
     * @param e     expected count under the fitted model
     * @param shift the {@code k} of shifted PPMI; ignored by every other measure
     * @return the residual, 0 when {@code e} is not positive
     */
    private static double residualCell(
        final Assoc assoc,
        final double o,
        final double e,
        final double shift
    ) {
        if (e <= 0d) {
            return 0d;
        }
        switch (assoc) {
            case FT:
                return Math.sqrt(o) + Math.sqrt(o + 1d) - Math.sqrt(4d * e + 1d);
            case G2:
                final double deviance = 2d * ((o > 0d ? o * Math.log(o / e) : 0d) - o + e);
                return Math.copySign(Math.sqrt(Math.max(0d, deviance)), o - e);
            case PMI:
                return o > 0d ? Math.log(o / e) : 0d;
            case PPMI:
                return o > 0d ? Math.max(0d, Math.log(o / e)) : 0d;
            case SPPMI:
                return o > 0d ? Math.max(0d, Math.log(o / e) - Math.log(shift)) : 0d;
            default:
                return (o - e) / Math.sqrt(e);
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
