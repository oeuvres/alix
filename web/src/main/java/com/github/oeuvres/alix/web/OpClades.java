package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.Assoc;
import com.github.oeuvres.alix.maths.ContingencySvd.SvdLayout;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Maps selected terms over the full corpus by factorising association residuals
 * of their term-by-document table.
 *
 * <p>
 * The {@code residual} parameter selects Pearson, signed square-root G², or
 * Freeman-Tukey residuals. The {@code geometry} parameter selects chi-square or
 * cosine row geometry. The pipeline fits the independence expectation by IPF,
 * forms residuals, decomposes them, and retains leading principal coordinates
 * {@code U_k Sigma_k}. The default map is classical correspondence analysis:
 * Pearson residuals, chi-square row geometry, and the first factorial plane.
 * The optional {@code minDocTerms} parameter retains only documents containing
 * at least that many distinct selected terms; its default is 1. The
 * {@code .csv} extension emits the raw term-by-document contingency table.
 * </p>
 *
 * <h2>Rejected experiments</h2>
 * <p>
 * These routes were tried and removed; they are recorded so they are not
 * restored without a model that addresses why they failed.
 * </p>
 * <ul>
 * <li>BM25 term profiles: term geometry was governed almost entirely by corpus
 * document frequency, because BM25 scores are not comparable across query
 * terms. Needs an explicit cross-term calibration before reuse.</li>
 * <li>Uncentred decomposition of raw frequencies or positive document G²:
 * produced a prevalence axis.</li>
 * <li>L2 / chord / NMDS on positive G²: gave rare one-document profiles the
 * same geometric weight as well-supported terms.</li>
 * <li>Hidden {@code OTHER} background row with explicit zero-mass barycentre
 * projection: statistically defensible but the invisible row displaced the
 * visible terms and degraded the semantic map.</li>
 * <li>Locality-preserving projection (LPP) of the leading factors: its
 * k-nearest-neighbour graph changed discontinuously as terms were added and
 * truncation discarded coordinates needed downstream.</li>
 * <li>Server-side Varimax / display-axis Varimax rotation: superseded by the
 * client-side axis alignment in {@code alix-map.js}; removed from this
 * endpoint.</li>
 * <li>Polar / radial map ({@code view=RADIAL}): a disc has only two degrees of
 * freedom, so the radius either duplicated the factorial plane, when the angle
 * was read from axes 1 and 2, or contracted the trailing dimensions into an
 * unreadable length. Every attempt to give the radius independent meaning
 * required either a stress optimiser or a display law, both of which turn the
 * map into something other than a reading of the table. The orthogonal
 * factorial plane is strictly better for the one task that worked, reading
 * clusters, and wastes no space on an inscribed disc. Do not restore without a
 * genuine third channel for the radius.</li>
 * </ul>
 *
 * <p>
 * Binary term-distance squares (Ochiai, gene-sharing) previously exported here
 * were moved to {@link com.github.oeuvres.alix.maths.BinaryDistance}; this
 * endpoint now works on counts only.
 * </p>
 */
public class OpClades extends Op
{
    /** Table emitted by the {@code .csv} extension. */
    private enum CsvKind
    {
        /** Selected term-by-document raw contingency table. */
        CONTINGENCY,
        /** Singular-axis and cumulative retained-inertia table. */
        INERTIA;
    }
    

    /** Row geometry applied to retained principal coordinates. */
    private enum Geometry
    {
        /** Apply inverse-square-root row-mass chi-square scaling. */
        CHI2,
        /** Normalize retained row coordinates to unit length. */
        COSINE,
        /** Preserve unscaled retained coordinates in signed G² residual space. */
        G2;
    }
    
    /** Residual family applied before singular value decomposition. */
    private enum Residual
    {
        /** Freeman-Tukey variance-stabilised residual. */
        FT,
        /** Signed square-root G² deviance residual. */
        G2,
        /** Pearson standardized residual. */
        PEARSON;
    }
    
    /** Raw term frequencies with document mapping. */
    private record TermDocMatrix(
            int[][] frequencies,
            int[] docIds)
    {
    }
    
    /** Support-filtered matrix plus retained ranks in the original row list. */
    private record MatrixFilterResult(
            TermDocMatrix matrix,
            int[] rowRanks,
            int retainedDocuments)
    {
    }
    
    /**
     * Projected map plus the diagnostics the {@link ContingencySvd} layout does
     * not provide.
     *
     * @param residual            residual family used before decomposition
     * @param geometry            row geometry used after dimensional truncation
     * @param coordinates         leading display dimensions
     * @param cos2                share of each row's squared norm on axes 0 and 1
     * @param cos3                share of each row's squared norm on axes 0, 1 and 2
     * @param inertia             full singular-value inertia spectrum, in percent
     * @param massPercent         row mass, in percent
     * @param squaredDistance     squared distance of each row from the origin
     * @param contributionPercent per-displayed-axis inertia contribution, in
     *                            percent
     * @param eigenvalues         classical CA eigenvalues, or an empty array
     * @param trace               classical CA total inertia, or {@link Double#NaN}
     * @param chiSquare           Pearson independence statistic, or {@link Double#NaN}
     * @param degreesFreedom      Pearson independence degrees of freedom, or zero
     */
    private record TermMap(
            Residual residual,
            Geometry geometry,
            double[][] coordinates,
            double[] cos2,
            double[] cos3,
            double[] inertia,
            double[] massPercent,
            double[] squaredDistance,
            double[][] contributionPercent,
            double[] eigenvalues,
            double trace,
            double chiSquare,
            int degreesFreedom)
    {
    }
    
    /** Returns the number of document columns with positive selected-term mass. */
    private static int activeDocumentCount(final int[][] frequencies)
    {
        if (frequencies.length == 0) {
            return 0;
        }
        int count = 0;
        for (int col = 0; col < frequencies[0].length; col++) {
            boolean active = false;
            for (final int[] row : frequencies) {
                if (row[col] > 0) {
                    active = true;
                    break;
                }
            }
            if (active) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Retains documents containing at least {@code minDocTerms} distinct
     * selected terms, then removes term rows left with zero mass.
     *
     * <p>
     * This is the sparse-table preparation recommended by Lebart and Salem:
     * support counts distinct selected forms, not their occurrence totals.
     * </p>
     */
    private static MatrixFilterResult filterByDocumentSupport(
        final TermDocMatrix source,
        final int minDocTerms)
    {
        final int[][] frequencies = source.frequencies();
        final int rows = frequencies.length;
        final int cols = source.docIds().length;
        if (rows == 0 || cols == 0) {
            return new MatrixFilterResult(
                    new TermDocMatrix(new int[0][0], new int[0]),
                    new int[0],
                    0);
        }
        
        final boolean[] keepCol = new boolean[cols];
        int keptCols = 0;
        for (int col = 0; col < cols; col++) {
            int support = 0;
            for (int row = 0; row < rows; row++) {
                if (frequencies[row][col] > 0) {
                    support++;
                }
            }
            if (support >= minDocTerms) {
                keepCol[col] = true;
                keptCols++;
            }
        }
        
        final boolean[] keepRow = new boolean[rows];
        int keptRows = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (keepCol[col] && frequencies[row][col] > 0) {
                    keepRow[row] = true;
                    keptRows++;
                    break;
                }
            }
        }
        
        final int[] rowRanks = new int[keptRows];
        final int[] docIds = new int[keptCols];
        final int[][] filtered = new int[keptRows][keptCols];
        int filteredCol = 0;
        for (int col = 0; col < cols; col++) {
            if (keepCol[col]) {
                docIds[filteredCol++] = source.docIds()[col];
            }
        }
        int filteredRow = 0;
        for (int row = 0; row < rows; row++) {
            if (!keepRow[row]) {
                continue;
            }
            rowRanks[filteredRow] = row;
            filteredCol = 0;
            for (int col = 0; col < cols; col++) {
                if (keepCol[col]) {
                    filtered[filteredRow][filteredCol++] = frequencies[row][col];
                }
            }
            filteredRow++;
        }
        return new MatrixFilterResult(
                new TermDocMatrix(filtered, docIds),
                rowRanks,
                keptCols);
    }
    
    /** Selects integer values at retained original ranks. */
    private static int[] selectRanks(final int[] values, final int[] ranks)
    {
        final int[] selected = new int[ranks.length];
        for (int rank = 0; rank < ranks.length; rank++) {
            selected[rank] = values[ranks[rank]];
        }
        return selected;
    }
    
    /** Selects long values at retained original ranks. */
    private static long[] selectRanks(final long[] values, final int[] ranks)
    {
        final long[] selected = new long[ranks.length];
        for (int rank = 0; rank < ranks.length; rank++) {
            selected[rank] = values[ranks[rank]];
        }
        return selected;
    }
    
    /** Returns the contingency association corresponding to a residual mode. */
    private static Assoc association(final Residual residual)
    {
        return switch (residual) {
            case FT -> Assoc.FT;
            case G2 -> Assoc.G2;
            case PEARSON -> Assoc.PEARSON;
        };
    }
    
    /**
     * Per-displayed-axis inertia contribution of every row, in percent.
     */
    private static double[][] contributions(
        final double[] masses,
        final double[][] embedding,
        final int dims)
    {
        final double[] axisEnergy = new double[dims];
        for (int row = 0; row < embedding.length; row++) {
            for (int axis = 0; axis < dims; axis++) {
                axisEnergy[axis] += masses[row]
                        * embedding[row][axis] * embedding[row][axis];
            }
        }
        final double[][] contributions = new double[embedding.length][dims];
        for (int row = 0; row < embedding.length; row++) {
            for (int axis = 0; axis < dims; axis++) {
                contributions[row][axis] = axisEnergy[axis] > 0d
                        ? 100d * masses[row]
                                * embedding[row][axis] * embedding[row][axis]
                                / axisEnergy[axis]
                        : 0d;
            }
        }
        return contributions;
    }
    
    /**
     * Share of each row's squared embedding norm held by its first three axes.
     */
    private static double[] cos3(final double[][] embedding)
    {
        final double[] quality = new double[embedding.length];
        for (int row = 0; row < embedding.length; row++) {
            double shown = 0d;
            double total = 0d;
            for (int axis = 0; axis < embedding[row].length; axis++) {
                final double squared = embedding[row][axis] * embedding[row][axis];
                total += squared;
                if (axis < 3) {
                    shown += squared;
                }
            }
            quality[row] = total > 0d ? shown / total : 0d;
        }
        return quality;
    }
    
    @Override
    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response) throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final Writer writer = response.getWriter();
        
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            meta.log("[no term selection]");
            AlixServlet.jsonError(request, response);
            return;
        }
        final TermLexicon lexicon = topTerms.lexicon();
        topTerms.rank(
                pars.getInt("terms", new int[] { 2, 1000 }, 100),
                pars.getEnum("tflag", TermFlag.NULL));
        final IntList rowList = new IntList(topTerms.size());
        for (final TermEntry term : topTerms) {
            rowList.push(term.termId());
        }
        final int[] rowIds = rowList.toUniq();
        final FixedBitSet liveDocs = liveDocs(index.reader());
        final int minDocTerms = pars.getInt(
                "minDocTerms",
                new int[] { 1, Math.max(1, rowIds.length) },
                1);
        final MatrixFilterResult filtered = filterByDocumentSupport(
                termDocMatrix(index.reader(), lexicon, rowIds, liveDocs),
                minDocTerms);
        final int[] activeRowIds = selectRanks(rowIds, filtered.rowRanks());
        final CsvKind csvKind = request.getParameter("csv") == null
                ? hasSvdCsvParameters(request) ? CsvKind.INERTIA : CsvKind.CONTINGENCY
                : pars.getEnum("csv", CsvKind.CONTINGENCY);
        
        switch (csvKind) {
            case CONTINGENCY -> writeContingencyCsv(
                    writer,
                    lexicon,
                    activeRowIds,
                    filtered.matrix());
            case INERTIA -> {
                final Residual residual = pars.getEnum(
                        "residual", Residual.PEARSON);
                final Geometry geometry = pars.getEnum(
                        "geometry", Geometry.CHI2);
                writeInertiaCsv(
                        writer,
                        filtered.matrix(),
                        residual,
                        geometry);
            }
        }
    }
    
    /**
     * Writes the selected-term factor map as JSON.
     *
     * @param index    Lucene index
     * @param request  HTTP request
     * @param response HTTP response
     * @throws IOException if index access or response writing fails
     */
    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response) throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        
        if (topTerms == null) {
            response.setStatus(400);
            meta.log("[no term selection]");
            AlixServlet.jsonError(request, response);
            return;
        }
        
        final TermLexicon lexicon = topTerms.lexicon();
        
        int[] rowIds = null;
        long[] rowFreq = null;
        TermMap map = null;
        
        final Map<Integer, Long> frequencyById = new HashMap<>();
        final IntList rowList = new IntList(topTerms.size());
        for (final TermEntry term : topTerms) {
            rowList.push(term.termId());
            frequencyById.put(term.termId(), term.freq());
        }
        rowIds = rowList.toUniq();
        rowFreq = new long[rowIds.length];
        for (int row = 0; row < rowIds.length; row++) {
            rowFreq[row] = frequencyById.getOrDefault(rowIds[row], 0L);
        }
        
        final int selectedTermsBeforeSupportFilter = rowIds.length;
        final FixedBitSet liveDocs = liveDocs(index.reader());
        final TermDocMatrix rawMatrix = termDocMatrix(
                index.reader(),
                lexicon,
                rowIds,
                liveDocs);
        final int activeDocumentsBeforeSupportFilter = activeDocumentCount(
                rawMatrix.frequencies());
        final int minDocTerms = pars.getInt(
                "minDocTerms",
                new int[] { 1, Math.max(1, rowIds.length) },
                1);
        final MatrixFilterResult filtered = filterByDocumentSupport(
                rawMatrix,
                minDocTerms);
        final TermDocMatrix matrix = filtered.matrix();
        rowIds = selectRanks(rowIds, filtered.rowRanks());
        rowFreq = selectRanks(rowFreq, filtered.rowRanks());
        
        meta.put("documents", liveDocs.cardinality());
        meta.put(
                "activeDocumentsBeforeSupportFilter",
                activeDocumentsBeforeSupportFilter);
        meta.put("minDocTerms", minDocTerms);
        meta.put("activeDocuments", filtered.retainedDocuments());
        meta.put(
                "supportFilteredDocuments",
                activeDocumentsBeforeSupportFilter - filtered.retainedDocuments());
        meta.put(
                "zeroMassDocuments",
                liveDocs.cardinality() - activeDocumentsBeforeSupportFilter);
        meta.put(
                "selectedTermsBeforeSupportFilter",
                selectedTermsBeforeSupportFilter);
        meta.put("activeTerms", rowIds.length);
        meta.put(
                "supportFilteredTerms",
                selectedTermsBeforeSupportFilter - rowIds.length);
        meta.put("documentUniverse", "full corpus");
        meta.put("focusRestricted", false);
        meta.put("source", "raw term-document frequencies");
        
        final Residual residual = pars.getEnum("residual", Residual.PEARSON);
        final Geometry geometry = pars.getEnum("geometry", Geometry.CHI2);
        
        if (rowIds.length < 2 || filtered.retainedDocuments() < 2) {
            response.setStatus(400);
            meta.log("minDocTerms leaves fewer than two active terms or documents");
            AlixServlet.jsonError(request, response);
            return;
        }
        map = termMap(
                matrix,
                pars,
                meta,
                residual,
                geometry);
        
        try (JsonWriter jw = new JsonWriter(response.getWriter())) {
            jw.beginObject();
            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();
            if (map != null) {
                writeMapData(
                        jw,
                        map,
                        rowIds,
                        rowFreq,
                        lexicon);
            }
            jw.endObject();
        }
    }
    
    /**
     * Collects the live global document ids of the reader snapshot.
     */
    private static FixedBitSet liveDocs(final IndexReader reader)
    {
        final FixedBitSet liveDocs = new FixedBitSet(reader.maxDoc());
        for (final var context : reader.leaves()) {
            final Bits leafLiveDocs = context.reader().getLiveDocs();
            if (leafLiveDocs == null) {
                liveDocs.set(context.docBase, context.docBase + context.reader().maxDoc());
                continue;
            }
            for (int localDocId = 0; localDocId < context.reader().maxDoc(); localDocId++) {
                if (leafLiveDocs.get(localDocId)) {
                    liveDocs.set(context.docBase + localDocId);
                }
            }
        }
        return liveDocs;
    }
    
    /** Row mass in percent, aligned with {@code masses}. */
    private static double[] massPercent(final double[] masses)
    {
        final double[] percent = masses.clone();
        for (int row = 0; row < percent.length; row++) {
            percent[row] *= 100d;
        }
        return percent;
    }
    
    /**
     * Converts singular values into their full inertia spectrum in percent.
     *
     * @param singularValues singular values in descending order
     * @return squared singular values normalized to a total of 100 percent
     */
    private static double[] inertiaPercent(final double[] singularValues)
    {
        final double[] percent = new double[singularValues.length];
        double total = 0d;
        for (int axis = 0; axis < singularValues.length; axis++) {
            percent[axis] = singularValues[axis] * singularValues[axis];
            total += percent[axis];
        }
        if (!(total > 0d)) {
            return percent;
        }
        for (int axis = 0; axis < percent.length; axis++) {
            percent[axis] *= 100d / total;
        }
        return percent;
    }
    
    /**
     * Returns whether an implicit CSV request carries factor-analysis parameters.
     *
     * <p>
     * This permits changing only the extension of a map JSON URL to
     * {@code .csv}: the same request then emits the complete retained-inertia
     * curve. A CSV URL containing only term-selection parameters keeps the raw
     * contingency-table default. The explicit {@code csv=CONTINGENCY} and
     * {@code csv=INERTIA} values override this inference.
     * </p>
     *
     * @param request current HTTP request
     * @return {@code true} when factor-analysis parameters are present
     */
    private static boolean hasSvdCsvParameters(final HttpServletRequest request)
    {
        return request.getParameter("dims") != null
                || request.getParameter("residual") != null
                || request.getParameter("geometry") != null;
    }
    
    /**
     * Returns each row's share of the squared retained {@code U Sigma} norm.
     * This is the joint counterpart of correspondence-analysis contributions:
     * it is computed before cosine normalization and therefore remains
     * comparable across geometries.
     */
    private static double[] retainedContributionPercent(
        final double[][] retainedSource)
    {
        final double[] percent = new double[retainedSource.length];
        double total = 0d;
        for (int row = 0; row < retainedSource.length; row++) {
            for (final double coordinate : retainedSource[row]) {
                percent[row] += coordinate * coordinate;
            }
            total += percent[row];
        }
        if (!(total > 0d)) {
            return percent;
        }
        for (int row = 0; row < percent.length; row++) {
            percent[row] *= 100d / total;
        }
        return percent;
    }
    
    /** Returns normalized observed row masses of a contingency table. */
    private static double[] rowMasses(final double[][] table)
    {
        final double[] masses = new double[table.length];
        double total = 0d;
        for (int row = 0; row < table.length; row++) {
            for (final double value : table[row]) {
                masses[row] += value;
            }
            total += masses[row];
        }
        if (!(total > 0d)) {
            throw new IllegalArgumentException("term table has no positive mass");
        }
        for (int row = 0; row < masses.length; row++) {
            masses[row] /= total;
        }
        return masses;
    }
    
    /** Returns a human-readable description of a residual mode. */
    private static String residualLabel(final Residual residual)
    {
        return switch (residual) {
            case FT -> "Freeman-Tukey variance-stabilised residuals";
            case G2 -> "signed sqrt G2 deviance residuals";
            case PEARSON -> "Pearson standardized residuals";
        };
    }
    
    /** Multiplies every coordinate by a common factor. */
    private static void scaleCoordinates(
        final double[][] coordinates,
        final double factor)
    {
        for (final double[] row : coordinates) {
            for (int axis = 0; axis < row.length; axis++) {
                row[axis] *= factor;
            }
        }
    }
    
    /** Multiplies every value by a common factor. */
    private static void scaleValues(final double[] values, final double factor)
    {
        for (int index = 0; index < values.length; index++) {
            values[index] *= factor;
        }
    }
    
        
    private static double[] squaredDistances(final double[][] embedding)
    {
        final double[] squared = new double[embedding.length];
        for (int row = 0; row < embedding.length; row++) {
            double sum = 0d;
            for (final double value : embedding[row]) {
                sum += value * value;
            }
            squared[row] = sum;
        }
        return squared;
    }
    
    /** Returns the total observed mass of a contingency table. */
    private static double tableTotal(final double[][] table)
    {
        double total = 0d;
        for (final double[] row : table) {
            for (final double value : row) {
                total += value;
            }
        }
        return total;
    }
    
    /**
     * Builds raw frequency rows for every retained document.
     *
     * @param reader    index reader supplying term postings
     * @param lexicon   term lexicon aligned with the reader
     * @param rowIds    selected dense term ids
     * @param docFilter retained document dimensions
     * @return raw frequencies and compact document ids
     * @throws IOException if terms or postings cannot be read
     */
    private static TermDocMatrix termDocMatrix(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet docFilter) throws IOException
    {
        final int[] docRanks = new int[reader.maxDoc()];
        Arrays.fill(docRanks, -1);
        int docCount = 0;
        for (int docId = 0; docId < docFilter.length(); docId++) {
            if (docFilter.get(docId)) {
                docRanks[docId] = docCount++;
            }
        }
        
        final int[] docIds = new int[docCount];
        for (int docId = 0; docId < docRanks.length; docId++) {
            if (docRanks[docId] >= 0) {
                docIds[docRanks[docId]] = docId;
            }
        }
        
        final int[][] frequencies = new int[rowIds.length][docCount];
        final Terms terms = MultiTerms.getTerms(reader, lexicon.field());
        final TermsEnum termsEnum = terms == null ? null : terms.iterator();
        final BytesRefBuilder termBytes = new BytesRefBuilder();
        PostingsEnum postings = null;
        
        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            if (termsEnum != null
                    && termsEnum.seekExact(lexicon.formBytes(rowIds[rowRank], termBytes)))
            {
                postings = termsEnum.postings(postings, PostingsEnum.FREQS);
                for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings
                        .nextDoc())
                {
                    if (docFilter.get(docId)) {
                        frequencies[rowRank][docRanks[docId]] = postings.freq();
                    }
                }
            }
        }
        return new TermDocMatrix(frequencies, docIds);
    }
    
    /**
     * Factorises association residuals of the selected term-document table.
     *
     * <p>
     * Coordinates, {@code cos2} and the inertia spectrum come from the
     * {@link ContingencySvd} principal-coordinate layout; {@code cos3} and the
     * correspondence-analysis diagnostics (mass, origin distance, per-axis
     * contribution) are computed from the same full embedding. The residual
     * family is applied before decomposition. Chi-square geometry applies
     * inverse-square-root row-mass scaling; with Pearson residuals this is
     * classical correspondence analysis. Cosine geometry instead normalizes
     * every row after dimensional truncation.
     * </p>
     */
    private static TermMap termMap(
        final TermDocMatrix matrix,
        final HttpPars pars,
        final MetaUtil meta,
        final Residual residual,
        final Geometry geometry)
    {
        final Assoc association = association(residual);
        final double[][] table = toDoubleTable(matrix.frequencies());
        final ContingencySvd model = new ContingencySvd(table, null)
                .residual(association)
                .decompose()
                .weightAxes(1d);
        
        final int requestedDims = pars.getInt("dims", new int[] { 2, 50 }, 2);
        final SvdLayout layout = switch (geometry) {
            case CHI2 -> {
                model.scaleRowsByMass();
                yield model.project(requestedDims);
            }
            case COSINE -> model.projectNormalized(requestedDims);
            case G2 -> model.project(requestedDims);
        };
        final double[][] embedding = model.embedding();
        final double[][] coordinates = layout.coords();
        final int dims = coordinates.length == 0 ? 0 : coordinates[0].length;
        final double[] masses = rowMasses(table);
        final double total = tableTotal(table);
        final boolean classical = residual == Residual.PEARSON
                && geometry == Geometry.CHI2;
        final double[] squaredDistance = squaredDistances(embedding);
        double[] eigenvalues = new double[0];
        double trace = Double.NaN;
        double chiSquare = Double.NaN;
        int degreesFreedom = 0;
        
        if (classical) {
            scaleCoordinates(coordinates, 1d / Math.sqrt(total));
            scaleValues(squaredDistance, 1d / total);
            
            final int rank = embedding.length == 0 ? 0 : embedding[0].length;
            final double[] singularValues = model.singularValues();
            eigenvalues = new double[rank];
            trace = 0d;
            for (int axis = 0; axis < rank; axis++) {
                eigenvalues[axis] = singularValues[axis]
                        * singularValues[axis] / total;
                trace += eigenvalues[axis];
            }
            chiSquare = total * trace;
            degreesFreedom = Math.max(0, table.length - 1)
                    * Math.max(0, activeDocumentCount(matrix.frequencies()) - 1);
        }
        
        meta.put("profile", residualLabel(residual));
        meta.put("residual", residual.toString());
        meta.put("geometry", geometry.toString());
        meta.put("method", classical
                ? "correspondence analysis"
                : "residual factor analysis");
        meta.put("table", "query-selected terms x active documents");
        meta.put("vocabularyConditioning", "selected terms only");
        meta.put("rowNormalization", switch (geometry) {
            case CHI2 -> "inverse sqrt row mass";
            case COSINE -> "unit length after dimensional truncation";
            case G2 -> "none";
        });
        meta.put("distance", switch (geometry) {
            case CHI2 -> residual == Residual.PEARSON
                    ? "chi-square distance between term profiles"
                    : "chi-square-scaled distance in residual principal coordinates";
            case COSINE -> "cosine chord distance in retained principal coordinates";
            case G2 -> residual == Residual.G2
                    ? "Euclidean distance in retained signed sqrt G2 residual coordinates"
                    : "Euclidean distance in retained unscaled residual coordinates";
        });
        meta.put("projection", classical
                ? requestedDims == 2
                        ? "first factorial plane"
                        : "leading factorial axes"
                : "leading residual factors");
        meta.put("coordinateNormalization", classical
                ? "classical CA"
                : "none");
        meta.put("association", association.toString());
        meta.put("rotation", "NONE");
        meta.put("svdAxisWeight", 1d);
        meta.put("svdRank", embedding.length == 0 ? 0 : embedding[0].length);
        meta.put("svdFitConverged", model.fitConverged());
        meta.put("svdFitError", model.fitError());
        meta.put("svdFitIterations", model.fitIterations());
        
        return new TermMap(
                residual,
                geometry,
                coordinates,
                layout.cos2(),
                cos3(embedding),
                layout.inertia(),
                massPercent(masses),
                squaredDistance,
                contributions(masses, embedding, dims),
                eigenvalues,
                trace,
                chiSquare,
                degreesFreedom);
    }
    
    /** Copies raw selected-term frequencies into a double contingency table. */
    private static double[][] toDoubleTable(final int[][] frequencies)
    {
        final double[][] table = new double[frequencies.length][];
        for (int row = 0; row < frequencies.length; row++) {
            final int[] source = frequencies[row];
            final double[] target = new double[source.length];
            for (int col = 0; col < source.length; col++) {
                target[col] = source[col];
            }
            table[row] = target;
        }
        return table;
    }
    
    /** Writes the axis and fit metadata block. */
    private static void writeAxes(final JsonWriter jw, final TermMap map)
        throws IOException
    {
        jw.name("axes");
        jw.beginObject();
        final int dims = map.coordinates().length == 0
                ? 0
                : map.coordinates()[0].length;
        final double[] inertia = map.inertia();
        final double dim1 = inertia.length > 0 ? inertia[0] : 0d;
        final double dim2 = inertia.length > 1 ? inertia[1] : 0d;
        double emitted = 0d;
        for (int axis = 0; axis < Math.min(dims, inertia.length); axis++) {
            emitted += inertia[axis];
        }
        
        jw.name("dims").value(dims);
        jw.name("method").value(map.eigenvalues().length > 0
                ? "correspondence analysis"
                : residualLabel(map.residual()) + " / " + map.geometry());
        jw.name("rotation").value("NONE");
        jw.name("dim1_pct").value(round(dim1, 1));
        jw.name("dim2_pct").value(round(dim2, 1));
        jw.name("cum2_pct").value(round(dim1 + dim2, 1));
        jw.name("emitted_pct").value(round(emitted, 1));
        jw.name("spectrum");
        jw.beginArray();
        for (final double percent : inertia) {
            jw.value(round(percent, 1));
        }
        jw.endArray();
        if (map.eigenvalues().length > 0) {
            jw.name("eigenvalues");
            jw.beginArray();
            for (final double eigenvalue : map.eigenvalues()) {
                jw.value(round(eigenvalue, 8));
            }
            jw.endArray();
            jw.name("trace").value(round(map.trace(), 8));
            jw.name("chi2").value(round(map.chiSquare(), 4));
            jw.name("degreesFreedom").value(map.degreesFreedom());
        }
        jw.endObject();
    }
    
    /**
     * Writes the complete singular-axis and cumulative retained-inertia curve.
     *
     * <p>
     * The decomposition is performed once. Producing all cumulative values is
     * then linear in the available rank.
     * Geometry is written as request context but does not alter this spectrum,
     * because CHI2, COSINE, and G2 row geometry are applied after the residual
     * matrix has been decomposed.
     * </p>
     *
     * @param writer   response writer
     * @param matrix   selected term-by-document contingency table
     * @param residual residual family applied before decomposition
     * @param geometry requested post-decomposition row geometry
     * @throws IOException if the CSV response cannot be written
     */
    private static void writeInertiaCsv(
        final Writer writer,
        final TermDocMatrix matrix,
        final Residual residual,
        final Geometry geometry) throws IOException
    {
        final double[][] table = toDoubleTable(matrix.frequencies());
        final ContingencySvd model = new ContingencySvd(table, null)
                .residual(association(residual))
                .decompose()
                .weightAxes(1d);
        final double[] spectrum = inertiaPercent(model.singularValues());
        final int rank = spectrum.length;
        double retained = 0d;
        
        writer.append(
                "residual,geometry,rank,dims,axisInertia_pct,retainedInertia_pct\n");
        for (int axis = 0; axis < rank; axis++) {
            retained += spectrum[axis];
            writer.append(residual.toString()).append(',')
                    .append(geometry.toString()).append(',')
                    .append(Integer.toString(rank)).append(',')
                    .append(Integer.toString(axis + 1)).append(',')
                    .append(Double.toString(round(spectrum[axis], 6))).append(',')
                    .append(Double.toString(round(retained, 6))).append('\n');
        }
    }
    
    /**
     * Writes the selected term-by-document contingency table as CSV.
     *
     * @param writer  response writer
     * @param lexicon selected term lexicon
     * @param rowIds  selected term ids aligned with the matrix rows
     * @param matrix  selected term-by-document contingency table
     * @throws IOException if the CSV response cannot be written
     */
    private static void writeContingencyCsv(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final TermDocMatrix matrix) throws IOException
    {
        final int[][] frequencies = matrix.frequencies();
        final int[] docIds = matrix.docIds();
        final boolean[] keep = new boolean[docIds.length];
        for (int col = 0; col < docIds.length; col++) {
            for (int row = 0; row < frequencies.length; row++) {
                if (frequencies[row][col] > 0) {
                    keep[col] = true;
                    break;
                }
            }
        }
        
        writer.append("form");
        for (int col = 0; col < docIds.length; col++) {
            if (keep[col]) {
                writer.append(',').append(Integer.toString(docIds[col]));
            }
        }
        writer.append('\n');
        
        for (int row = 0; row < rowIds.length; row++) {
            writer.append(csvEscape(lexicon.form(rowIds[row])));
            for (int col = 0; col < docIds.length; col++) {
                if (keep[col]) {
                    writer.append(',')
                            .append(Integer.toString(frequencies[row][col]));
                }
            }
            writer.append('\n');
        }
    }
    
    /**
     * Writes the selected term map.
     */
    private static void writeMapData(
        final JsonWriter jw,
        final TermMap map,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon) throws IOException
    {
        jw.name("data");
        jw.beginObject();
        writeAxes(jw, map);
        
        jw.name("nodes");
        jw.beginArray();
        for (int node = 0; node < map.coordinates().length; node++) {
            final int termId = rowIds[node];
            final double[] coords = map.coordinates()[node];
            
            jw.beginObject();
            jw.name("id").value(termId);
            jw.name("form").value(lexicon.form(termId));
            jw.name("freq").value(rowFreq[node]);
            jw.name("x").value(round(coords.length > 0 ? coords[0] : 0d, 4));
            jw.name("y").value(round(coords.length > 1 ? coords[1] : 0d, 4));
            jw.name("cos2").value(round(map.cos2()[node], 4));
            jw.name("cos3").value(round(map.cos3()[node], 4));
            
            final double[] contributions = map.contributionPercent()[node];
            double contribution2 = 0d;
            for (int axis = 0; axis < Math.min(2, contributions.length); axis++) {
                contribution2 += contributions[axis];
            }
            jw.name("mass_pct").value(round(map.massPercent()[node], 6));
            jw.name("dist2").value(round(map.squaredDistance()[node], 6));
            jw.name("contrib2_pct").value(round(contribution2, 6));
            jw.name("contrib_pct");
            jw.beginArray();
            for (final double contribution : contributions) {
                jw.value(round(contribution, 6));
            }
            jw.endArray();
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
    
        
}
