package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.ALIX_META;
import static com.github.oeuvres.alix.web.Pars.ALIX_PARS;

/**
 * Produces the two-dimensional factor map consumed by {@code alix-map.js}.
 *
 * <p>
 * Selected terms form the rows of a term-by-document contingency table. The
 * table is converted to association residuals, decomposed by exact or randomized
 * SVD, and projected onto the first two principal coordinates. The JSON contains
 * only the axis inertia, term coordinates, term frequency, per-axis
 * contributions, and each term's contribution to the displayed plane.
 * </p>
 *
 * <p>
 * Request parameters retained by this endpoint are {@code residual},
 * {@code geometry}, {@code minDocTerms}, {@code svd}, {@code svdDims},
 * {@code svdOversamples}, and {@code svdPowerIterations}. The randomized method
 * defaults to two retained axes because the client consumes only the first
 * factorial plane.
 * </p>
 */
public class OpClades extends Op
{
    /** Number of coordinates consumed by the map client. */
    private static final int MAP_DIMS = 2;

    /** Row geometry applied after singular-value weighting. */
    private enum Geometry
    {
        /** Apply inverse-square-root row-mass scaling. */
        CHI2,
        /** Preserve unscaled residual principal coordinates. */
        G2;
    }

    /** Singular-value decomposition implementation. */
    private enum SvdMethod
    {
        /** Full deterministic dense decomposition. */
        EXACT,
        /** Truncated randomized decomposition. */
        RANDOMIZED;
    }

    /** Contributions of rows to the displayed axes and plane. */
    private record ContributionData(
        double[][] axisPercent,
        double[] planePercent)
    {
    }

    /** Support-filtered matrix and retained source-row ranks. */
    private record MatrixFilterResult(
        int[][] frequencies,
        int[] rowRanks)
    {
    }

    /** Two-dimensional map values written to JSON. */
    private record TermMap(
        double[][] coordinates,
        double[] inertiaPercent,
        double[][] axisContributionPercent,
        double[] planeContributionPercent)
    {
    }

    /** Parameters controlling singular-value decomposition. */
    private record SvdConfig(
        SvdMethod method,
        int dimensions,
        int oversamples,
        int powerIterations)
    {
    }

    /**
     * Writes the selected-term factor map as compact JSON.
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
        final Map<Integer, Long> frequencyById = new LinkedHashMap<>();
        for (final TermEntry term : topTerms) {
            frequencyById.putIfAbsent(term.termId(), term.freq());
        }

        int[] rowIds = new int[frequencyById.size()];
        long[] rowFreq = new long[frequencyById.size()];
        int rank = 0;
        for (final Map.Entry<Integer, Long> entry : frequencyById.entrySet()) {
            rowIds[rank] = entry.getKey();
            rowFreq[rank] = entry.getValue();
            rank++;
        }

        final FixedBitSet liveDocs = liveDocs(index.reader());
        final int[][] source = termDocMatrix(
            index.reader(),
            topTerms.lexicon(),
            rowIds,
            liveDocs);
        final int minDocTerms = pars.getInt(
            "minDocTerms",
            new int[] { 1, Math.max(1, rowIds.length) },
            1);
        final MatrixFilterResult filtered = filterByDocumentSupport(
            source,
            minDocTerms);

        rowIds = selectRanks(rowIds, filtered.rowRanks());
        rowFreq = selectRanks(rowFreq, filtered.rowRanks());
        final int[][] frequencies = filtered.frequencies();
        final int documentCount = frequencies.length == 0
            ? 0
            : frequencies[0].length;
        if (rowIds.length < MAP_DIMS || documentCount < MAP_DIMS) {
            response.setStatus(400);
            meta.log("minDocTerms leaves fewer than two active terms or documents");
            AlixServlet.jsonError(request, response);
            return;
        }

        final Assoc association = pars.getEnum("residual", Assoc.PEARSON);
        final Geometry geometry = pars.getEnum("geometry", Geometry.CHI2);
        final TermMap map = termMap(frequencies, pars, association, geometry);

        try (JsonWriter json = new JsonWriter(response.getWriter())) {
            json.beginObject();
            json.name("meta");
            json.beginObject();
            meta.toJson(json, pars);
            json.endObject();
            json.name("data");
            writeMap(json, map, rowIds, rowFreq, topTerms.lexicon());
            json.endObject();
        }
    }

    /**
     * Computes per-axis and whole-plane contributions.
     *
     * <p>
     * Per-axis contributions sum to 100 percent independently for each axis.
     * Plane contributions use both displayed axes together and sum to 100
     * percent over all terms. They are therefore the appropriate values for
     * bubble colour density; {@code cos²} would instead measure the quality of
     * representation of one term on the plane.
     * </p>
     *
     * @param masses normalized observed row masses
     * @param coordinates unnormalised displayed coordinates
     * @return axis contributions followed by plane contributions
     */
    private static ContributionData contributions(
        final double[] masses,
        final double[][] coordinates)
    {
        final double[] axisEnergy = new double[MAP_DIMS];
        final double[] rowEnergy = new double[coordinates.length];
        double planeEnergy = 0d;

        for (int row = 0; row < coordinates.length; row++) {
            for (int axis = 0; axis < MAP_DIMS; axis++) {
                final double energy = masses[row]
                    * coordinates[row][axis]
                    * coordinates[row][axis];
                axisEnergy[axis] += energy;
                rowEnergy[row] += energy;
                planeEnergy += energy;
            }
        }

        final double[][] axisContribution =
            new double[coordinates.length][MAP_DIMS];
        final double[] planeContribution = new double[coordinates.length];
        for (int row = 0; row < coordinates.length; row++) {
            for (int axis = 0; axis < MAP_DIMS; axis++) {
                final double energy = masses[row]
                    * coordinates[row][axis]
                    * coordinates[row][axis];
                axisContribution[row][axis] = axisEnergy[axis] > 0d
                    ? 100d * energy / axisEnergy[axis]
                    : 0d;
            }
            planeContribution[row] = planeEnergy > 0d
                ? 100d * rowEnergy[row] / planeEnergy
                : 0d;
        }

        return new ContributionData(axisContribution, planeContribution);
    }

    /**
     * Decomposes a residual model according to the request configuration.
     *
     * @param model model whose residual matrix has been prepared
     * @param config decomposition configuration
     * @return decomposed model
     */
    private static ContingencySvd decompose(
        final ContingencySvd model,
        final SvdConfig config)
    {
        return switch (config.method()) {
            case EXACT -> model.decompose();
            case RANDOMIZED -> model.decompose(
                config.dimensions(),
                config.oversamples(),
                config.powerIterations());
        };
    }

    /**
     * Retains documents containing at least {@code minDocTerms} selected terms
     * and removes rows left without positive mass.
     *
     * @param source source term-by-document matrix
     * @param minDocTerms minimum number of distinct selected terms per document
     * @return filtered matrix and source-row ranks
     */
    private static MatrixFilterResult filterByDocumentSupport(
        final int[][] source,
        final int minDocTerms)
    {
        final int rows = source.length;
        final int cols = rows == 0 ? 0 : source[0].length;
        if (rows == 0 || cols == 0) {
            return new MatrixFilterResult(new int[0][0], new int[0]);
        }

        final boolean[] keepCol = new boolean[cols];
        int keptCols = 0;
        for (int col = 0; col < cols; col++) {
            int support = 0;
            for (int row = 0; row < rows; row++) {
                if (source[row][col] > 0) {
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
                if (keepCol[col] && source[row][col] > 0) {
                    keepRow[row] = true;
                    keptRows++;
                    break;
                }
            }
        }

        final int[][] filtered = new int[keptRows][keptCols];
        final int[] rowRanks = new int[keptRows];
        int filteredRow = 0;
        for (int row = 0; row < rows; row++) {
            if (!keepRow[row]) {
                continue;
            }
            rowRanks[filteredRow] = row;
            int filteredCol = 0;
            for (int col = 0; col < cols; col++) {
                if (keepCol[col]) {
                    filtered[filteredRow][filteredCol++] = source[row][col];
                }
            }
            filteredRow++;
        }
        return new MatrixFilterResult(filtered, rowRanks);
    }

    /**
     * Collects live global document identifiers for the current reader snapshot.
     *
     * @param reader index reader
     * @return bit set of live global document identifiers
     */
    private static FixedBitSet liveDocs(final IndexReader reader)
    {
        final FixedBitSet liveDocs = new FixedBitSet(reader.maxDoc());
        for (final var context : reader.leaves()) {
            final Bits leafLiveDocs = context.reader().getLiveDocs();
            if (leafLiveDocs == null) {
                liveDocs.set(
                    context.docBase,
                    context.docBase + context.reader().maxDoc());
                continue;
            }
            for (int localDocId = 0;
                localDocId < context.reader().maxDoc();
                localDocId++)
            {
                if (leafLiveDocs.get(localDocId)) {
                    liveDocs.set(context.docBase + localDocId);
                }
            }
        }
        return liveDocs;
    }

    /**
     * Normalizes every non-zero coordinate row to unit Euclidean length.
     *
     * @param coordinates coordinates modified in place
     */
    private static void normalizeRows(final double[][] coordinates)
    {
        for (final double[] row : coordinates) {
            double squared = 0d;
            for (final double coordinate : row) {
                squared += coordinate * coordinate;
            }
            if (!(squared > 0d)) {
                continue;
            }
            final double inverse = 1d / Math.sqrt(squared);
            for (int axis = 0; axis < row.length; axis++) {
                row[axis] *= inverse;
            }
        }
    }

    /**
     * Returns normalized row masses of a contingency table.
     *
     * @param table observed contingency table
     * @return row masses summing to one
     */
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

    /**
     * Multiplies every coordinate by a common factor.
     *
     * @param coordinates coordinates modified in place
     * @param factor common scale factor
     */
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

    /**
     * Selects integer values at retained source ranks.
     *
     * @param values source values
     * @param ranks retained source ranks
     * @return selected values
     */
    private static int[] selectRanks(final int[] values, final int[] ranks)
    {
        final int[] selected = new int[ranks.length];
        for (int rank = 0; rank < ranks.length; rank++) {
            selected[rank] = values[ranks[rank]];
        }
        return selected;
    }

    /**
     * Selects long values at retained source ranks.
     *
     * @param values source values
     * @param ranks retained source ranks
     * @return selected values
     */
    private static long[] selectRanks(final long[] values, final int[] ranks)
    {
        final long[] selected = new long[ranks.length];
        for (int rank = 0; rank < ranks.length; rank++) {
            selected[rank] = values[ranks[rank]];
        }
        return selected;
    }

    /**
     * Reads and bounds randomized-SVD parameters.
     *
     * @param table contingency table determining the rank limit
     * @param pars request parameters
     * @return decomposition configuration
     */
    private static SvdConfig svdConfig(
        final double[][] table,
        final HttpPars pars)
    {
        final int rankLimit = Math.min(table.length, table[0].length);
        final SvdMethod method = pars.getEnum("svd", SvdMethod.RANDOMIZED);
        final int dimensions = pars.getInt(
            "svdDims",
            new int[] { MAP_DIMS, rankLimit },
            MAP_DIMS);
        final int oversamples = pars.getInt(
            "svdOversamples",
            new int[] { 0, 100 },
            20);
        final int powerIterations = pars.getInt(
            "svdPowerIterations",
            new int[] { 0, 10 },
            4);
        return new SvdConfig(
            method,
            dimensions,
            oversamples,
            powerIterations);
    }

    /**
     * Builds the raw frequency matrix for the selected terms and live documents.
     *
     * @param reader index reader supplying term postings
     * @param lexicon term lexicon aligned with the reader
     * @param rowIds selected dense term identifiers
     * @param docFilter live document filter
     * @return term-by-document frequency matrix
     * @throws IOException if terms or postings cannot be read
     */
    private static int[][] termDocMatrix(
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

        final int[][] frequencies = new int[rowIds.length][docCount];
        final Terms terms = MultiTerms.getTerms(reader, lexicon.field());
        final TermsEnum termsEnum = terms == null ? null : terms.iterator();
        final BytesRefBuilder termBytes = new BytesRefBuilder();
        PostingsEnum postings = null;

        for (int row = 0; row < rowIds.length; row++) {
            if (termsEnum == null
                || !termsEnum.seekExact(lexicon.formBytes(rowIds[row], termBytes)))
            {
                continue;
            }
            postings = termsEnum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc())
            {
                if (docFilter.get(docId)) {
                    frequencies[row][docRanks[docId]] = postings.freq();
                }
            }
        }
        return frequencies;
    }

    /**
     * Builds the two-dimensional factor map.
     *
     * @param frequencies filtered term-by-document frequency matrix
     * @param pars request parameters
     * @param association residual association
     * @param geometry row geometry
     * @return compact map values
     */
    private static TermMap termMap(
        final int[][] frequencies,
        final HttpPars pars,
        final Assoc association,
        final Geometry geometry)
    {
        final double[][] table = toDoubleTable(frequencies);
        final ContingencySvd model = decompose(
            new ContingencySvd(table, null).residual(association),
            svdConfig(table, pars))
            .weightAxes(1d);
        final double[] masses = rowMasses(table);
        final double total = tableTotal(table);
        final boolean classical = association == Assoc.PEARSON
            && geometry == Geometry.CHI2;

        if (geometry == Geometry.CHI2) {
            double power=pars.getDouble("scalemass", 0.5);
            if (power > 0) model.scaleRowsByMass(power);
        }
        else if (geometry == Geometry.G2) {
            double power=pars.getDouble("scalemass", 0);
            if (power > 0) model.scaleRowsByMass(power);
        }
        final SvdLayout layout = model.project(MAP_DIMS);
        final double[][] coordinates = layout.coords();
        if (classical) {
            scaleCoordinates(coordinates, 1d / Math.sqrt(total));
        }

        final ContributionData contributions = contributions(masses, coordinates);

        final double[] inertia = new double[MAP_DIMS];
        for (int axis = 0;
            axis < Math.min(MAP_DIMS, layout.inertia().length);
            axis++)
        {
            inertia[axis] = layout.inertia()[axis];
        }

        return new TermMap(
            coordinates,
            inertia,
            contributions.axisPercent(),
            contributions.planePercent());
    }

    /**
     * Returns the total observed mass of a contingency table.
     *
     * @param table observed contingency table
     * @return total observed mass
     */
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
     * Copies integer frequencies into a double contingency table.
     *
     * @param frequencies integer frequency matrix
     * @return double frequency matrix
     */
    private static double[][] toDoubleTable(final int[][] frequencies)
    {
        final double[][] table = new double[frequencies.length][];
        for (int row = 0; row < frequencies.length; row++) {
            table[row] = new double[frequencies[row].length];
            for (int col = 0; col < frequencies[row].length; col++) {
                table[row][col] = frequencies[row][col];
            }
        }
        return table;
    }

    /**
     * Writes the compact map payload consumed by {@code alix-map.js}.
     *
     * @param json JSON writer
     * @param map factor map
     * @param rowIds term identifiers aligned with map rows
     * @param rowFreq term frequencies aligned with map rows
     * @param lexicon term lexicon
     * @throws IOException if writing fails
     */
    private static void writeMap(
        final JsonWriter json,
        final TermMap map,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon) throws IOException
    {
        json.beginObject();
        json.name("axes");
        json.beginObject();
        json.name("dim1_pct").value(round(map.inertiaPercent()[0], 1));
        json.name("dim2_pct").value(round(map.inertiaPercent()[1], 1));
        json.endObject();

        json.name("nodes");
        json.beginArray();
        for (int row = 0; row < map.coordinates().length; row++) {
            final double[] coordinates = map.coordinates()[row];
            json.beginObject();
            json.name("id").value(rowIds[row]);
            json.name("form").value(lexicon.form(rowIds[row]));
            json.name("freq").value(rowFreq[row]);
            json.name("x").value(round(coordinates[0], 4));
            json.name("y").value(round(coordinates[1], 4));
            json.name("contrib_pct");
            json.beginArray();
            json.value(round(map.axisContributionPercent()[row][0], 4));
            json.value(round(map.axisContributionPercent()[row][1], 4));
            json.endArray();
            json.name("plane_contrib_pct").value(
                round(map.planeContributionPercent()[row], 4));
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }
}
