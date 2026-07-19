package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

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
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.Assoc;
import com.github.oeuvres.alix.maths.ContingencySvd.SvdLayout;
import com.github.oeuvres.alix.maths.Distances;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Maps selected terms over the full corpus by factorising association residuals
 * of their term-by-document table.
 *
 * <p>
 * The default map ({@code projection=G2_CA}) factorises signed square-root G²
 * deviance residuals; {@code projection=FT_CA} uses Freeman-Tukey
 * variance-stabilised residuals, and {@code projection=CA} is the classical
 * Pearson-residual correspondence-analysis control. All three share one
 * pipeline: fit a
 * quasi-independence expectation by IPF, form residuals, decompose, take
 * principal coordinates {@code U Sigma}, and scale rows by inverse square-root
 * mass. The JSON response mirrors the co-occurrence semantic-map endpoint (an
 * {@code axes} block plus {@code nodes}). {@code view=RADIAL} instead returns a
 * D2 radial layout built from six unscaled G² principal coordinates and soft
 * radial stress, its radius being mass-corrected source-space distinctiveness.
 * The {@code .csv} extension emits the raw term-by-document contingency table.
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
    /** Adam learning rate for the D2 radial stress optimizer. */
    private static final double RADIAL_LEARNING_RATE = 0.045d;

    /** Number of Adam iterations used by the D2 radial stress optimizer. */
    private static final int RADIAL_OPTIMIZER_ITERATIONS = 3500;

    /** Soft penalty retaining the source-space radial norm. */
    private static final double RADIAL_RADIUS_WEIGHT = 0.18d;

    /** Deterministic seed used for the radial angular initialization. */
    private static final long RADIAL_SEED = 7L;

    /** Standard deviation of the deterministic angular initialization jitter. */
    private static final double RADIAL_THETA_JITTER = 0.12d;

    /** Default number of unscaled G² principal coordinates retained by D2. */
    private static final int RADIAL_SVD_DIMS = 6;

    /** Table emitted by the {@code .csv} extension. */
    private enum CsvKind
    {
        /** Selected term-by-document raw contingency table. */
        CONTINGENCY;
    }

    /** JSON representations exposed by the endpoint. */
    private enum JsonView
    {
        /** Compact selected projection. */
        MAP,
        /** Compact D2 radial projection. */
        RADIAL;
    }

    /** Association residual family used to build the map. */
    private enum Projection
    {
        /** Pearson correspondence analysis of selected term rows. */
        CA,
        /** Freeman-Tukey residual factor analysis of selected term rows. */
        FT_CA,
        /** G² deviance-residual factor analysis of selected term rows. */
        G2_CA;
    }

    /** Raw term frequencies with document mapping. */
    private record TermDocMatrix(
        int[][] frequencies,
        int[] docIds
    ) {}

    /**
     * Final D2 radial coordinates aligned with the selected term rows.
     *
     * @param angles angular coordinates in radians
     * @param radii selected radial coordinates in {@code [0, 1]}
     */
    private record RadialMap(
        double[] angles,
        double[] radii
    ) {}

    /**
     * Projected map plus the diagnostics the {@link ContingencySvd} layout does
     * not provide.
     *
     * @param projection association family used
     * @param coordinates leading display dimensions
     * @param cos2 share of each row's squared norm on axes 0 and 1
     * @param cos3 share of each row's squared norm on axes 0, 1 and 2
     * @param inertia full singular-value inertia spectrum, in percent
     * @param massPercent row mass, in percent
     * @param squaredDistance squared distance of each row from the origin
     * @param contributionPercent per-displayed-axis inertia contribution, in
     *        percent
     */
    private record TermMap(
        Projection projection,
        double[][] coordinates,
        double[] cos2,
        double[] cos3,
        double[] inertia,
        double[] massPercent,
        double[] squaredDistance,
        double[][] contributionPercent
    ) {}

    /**
     * Per-displayed-axis inertia contribution of every row, in percent.
     */
    private static double[][] contributions(
        final double[] masses,
        final double[][] embedding,
        final int dims
    ) {
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
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final Writer writer = response.getWriter();

        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            writer.append("no term selection");
            return;
        }
        final IntList rowList = new IntList(topTerms.size());
        for (final TermEntry term : topTerms) {
            rowList.push(term.termId());
        }
        final int[] rowIds = rowList.toUniq();
        final TermLexicon lexicon = topTerms.lexicon();
        final FixedBitSet liveDocs = liveDocs(index.reader());

        switch (pars.getEnum("csv", CsvKind.CONTINGENCY)) {
            case CONTINGENCY -> writeContingencyCsv(
                writer,
                lexicon,
                rowIds,
                termDocMatrix(index.reader(), lexicon, rowIds, liveDocs)
            );
        }
    }

    /**
     * Writes the selected-term factor map as JSON.
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
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final JsonView view = pars.getEnum("view", JsonView.MAP);
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);

        int[] rowIds = null;
        long[] rowFreq = null;
        TermLexicon lexicon = null;
        TermStats termStats = null;
        TermMap map = null;
        RadialMap radial = null;

        if (topTerms == null) {
            response.setStatus(400);
        }
        else {
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
            lexicon = topTerms.lexicon();
            termStats = topTerms.termStats();

            final FixedBitSet liveDocs = liveDocs(index.reader());
            final TermDocMatrix matrix = termDocMatrix(
                index.reader(),
                lexicon,
                rowIds,
                liveDocs
            );
            meta.put("documents", liveDocs.cardinality());
            meta.put("documentUniverse", "full corpus");
            meta.put("focusRestricted", false);
            if (view != JsonView.RADIAL) {
                meta.put("source", "raw term-document frequencies");
            }

            if (view == JsonView.RADIAL) {
                radial = radialMap(
                    matrix,
                    meta,
                    pars.getInt("dims", new int[] { 2, 50 }, RADIAL_SVD_DIMS)
                );
                meta.put("color", "log2Lift");
                meta.put(
                    "colorMethod",
                    "log2((freq/focusTokens)/(fieldFreq/fieldTokens))"
                );
                meta.put("colorReference", 0d);
            }
            else {
                map = termMap(
                    matrix,
                    pars,
                    meta,
                    pars.getEnum("projection", Projection.G2_CA)
                );
            }
        }

        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();
            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();
            if (radial != null) {
                writeRadialData(
                    jw,
                    radial,
                    rowIds,
                    rowFreq,
                    lexicon,
                    termStats,
                    topTerms.tokens()
                );
            }
            else if (map != null) {
                writeMapData(
                    jw,
                    map,
                    rowIds,
                    rowFreq,
                    lexicon
                );
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
     * Builds the deterministic D2 radial layout from the selected contingency
     * table.
     *
     * <p>
     * The pipeline is fixed: signed square-root G² deviance residuals, six
     * unscaled principal coordinates {@code U_6 Sigma_6}, Euclidean distances
     * normalized by their pairwise mean and soft radial stress. Angles come from
     * the optimized positions; radii are the mass-corrected source norms. The
     * returned map contains only the final radius and angle required by the
     * client.
     * </p>
     *
     * @param matrix selected term-by-document contingency table
     * @param meta response metadata collector
     * @param requestedDims number of source dimensions requested
     * @return final radial coordinates aligned with the table rows
     */
    private static RadialMap radialMap(
        final TermDocMatrix matrix,
        final MetaUtil meta,
        final int requestedDims
    ) {
        final double[][] table = toDoubleTable(matrix.frequencies());
        final ContingencySvd model = new ContingencySvd(table, null)
            .residual(Assoc.G2);
        final SvdLayout layout = model.principalCoordinates(requestedDims);
        final double[][] source = layout.coords();
        final int size = source.length;
        final int sourceDims = size == 0 ? 0 : source[0].length;

        meta.put("view", "RADIAL");
        meta.put("radialMethod", "G2_SVD_SOFT_RADIAL_D2_V2");
        meta.put("profile", "signed sqrt G2 deviance residuals");
        meta.put("decomposition", "SVD");
        meta.put("coordinates", "unscaled U Sigma");
        meta.put("dims", sourceDims);
        meta.put("rowNormalization", "none");
        meta.put("distance", "Euclidean in unscaled G2 principal coordinates");
        meta.put("distanceNormalization", "mean pairwise");
        meta.put("projection", "soft radial stress");
        meta.put("radiusMethod", "mass-corrected source norm");
        meta.put("radiusMassCorrection", "inverse sqrt row mass");
        meta.put("radiusNormalization", "maximum");
        meta.put("lens", "none");
        meta.put("angleUnit", "radian");
        meta.put("angleRange", "[-pi,pi]");
        meta.put("radiusRange", "[0,1]");
        meta.put("svdRank", model.embedding().length == 0
            ? 0
            : model.embedding()[0].length);

        final double[] angles = new double[size];
        final double[] radii = new double[size];

        final double[] sourceRadii = new double[size];
        double sourceRadiusMax = 0d;
        for (int row = 0; row < size; row++) {
            double squared = 0d;
            for (final double coordinate : source[row]) {
                squared += coordinate * coordinate;
            }
            sourceRadii[row] = Math.sqrt(squared);
            sourceRadiusMax = Math.max(sourceRadiusMax, sourceRadii[row]);
        }
        final double[] masses = rowMasses(table);
        double distinctivenessMax = 0d;
        for (int row = 0; row < size; row++) {
            radii[row] = sourceRadii[row] / Math.sqrt(masses[row]);
            distinctivenessMax = Math.max(distinctivenessMax, radii[row]);
        }
        if (distinctivenessMax > 0d) {
            for (int row = 0; row < size; row++) {
                radii[row] /= distinctivenessMax;
            }
        }
        if (size < 2 || sourceDims == 0) {
            return new RadialMap(angles, radii);
        }
        if (!(sourceRadiusMax > 0d)) {
            return new RadialMap(angles, radii);
        }
        for (int row = 0; row < size; row++) {
            sourceRadii[row] /= sourceRadiusMax;
        }

        final double[][] distances = Distances.euclidean(source);
        double distanceSum = 0d;
        int distanceCount = 0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < row; col++) {
                distanceSum += distances[row][col];
                distanceCount++;
            }
        }
        final double distanceMean = distanceCount > 0
            ? distanceSum / distanceCount
            : 0d;
        if (!(distanceMean > 0d)) {
            return new RadialMap(angles, radii);
        }

        double distanceSquaredSum = 0d;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < row; col++) {
                distances[row][col] /= distanceMean;
                distances[col][row] = distances[row][col];
                distanceSquaredSum += distances[row][col] * distances[row][col];
            }
        }
        if (!(distanceSquaredSum > 0d)) {
            return new RadialMap(angles, radii);
        }

        final Random random = new Random(RADIAL_SEED);
        final double[][] positions = new double[size][2];
        for (int row = 0; row < size; row++) {
            final double sourceX = sourceDims > 0 ? source[row][0] : 0d;
            final double sourceY = sourceDims > 1 ? source[row][1] : 0d;
            final double theta = Math.atan2(sourceY, sourceX)
                + random.nextGaussian() * RADIAL_THETA_JITTER;
            positions[row][0] = sourceRadii[row] * Math.cos(theta);
            positions[row][1] = sourceRadii[row] * Math.sin(theta);
        }

        final double[][] firstMoment = new double[size][2];
        final double[][] secondMoment = new double[size][2];
        final double[][] bestPositions = new double[size][2];
        for (int row = 0; row < size; row++) {
            System.arraycopy(positions[row], 0, bestPositions[row], 0, 2);
        }

        double bestObjective = Double.POSITIVE_INFINITY;
        for (int iteration = 1; iteration <= RADIAL_OPTIMIZER_ITERATIONS; iteration++) {
            final double[][] gradient = new double[size][2];
            double stress = 0d;

            for (int row = 0; row < size; row++) {
                for (int col = 0; col < row; col++) {
                    final double deltaX = positions[row][0] - positions[col][0];
                    final double deltaY = positions[row][1] - positions[col][1];
                    final double projectedDistance = Math.max(
                        1e-9d,
                        Math.hypot(deltaX, deltaY)
                    );
                    final double error = projectedDistance - distances[row][col];
                    final double coefficient =
                        2d * error / (projectedDistance * distanceSquaredSum);

                    stress += error * error;
                    gradient[row][0] += coefficient * deltaX;
                    gradient[row][1] += coefficient * deltaY;
                    gradient[col][0] -= coefficient * deltaX;
                    gradient[col][1] -= coefficient * deltaY;
                }
            }
            stress /= distanceSquaredSum;

            double radiusPenalty = 0d;
            for (int row = 0; row < size; row++) {
                final double radius = Math.max(
                    1e-9d,
                    Math.hypot(positions[row][0], positions[row][1])
                );
                final double error = radius - sourceRadii[row];
                final double coefficient =
                    2d * RADIAL_RADIUS_WEIGHT * error / (size * radius);

                radiusPenalty += error * error;
                gradient[row][0] += coefficient * positions[row][0];
                gradient[row][1] += coefficient * positions[row][1];
            }

            final double objective = stress
                + RADIAL_RADIUS_WEIGHT * radiusPenalty / size;
            if (objective < bestObjective) {
                bestObjective = objective;
                for (int row = 0; row < size; row++) {
                    System.arraycopy(positions[row], 0, bestPositions[row], 0, 2);
                }
            }

            final double beta1Power = Math.pow(0.9d, iteration);
            final double beta2Power = Math.pow(0.999d, iteration);
            for (int row = 0; row < size; row++) {
                for (int axis = 0; axis < 2; axis++) {
                    firstMoment[row][axis] =
                        0.9d * firstMoment[row][axis]
                        + 0.1d * gradient[row][axis];
                    secondMoment[row][axis] =
                        0.999d * secondMoment[row][axis]
                        + 0.001d * gradient[row][axis] * gradient[row][axis];

                    final double correctedFirst =
                        firstMoment[row][axis] / (1d - beta1Power);
                    final double correctedSecond =
                        secondMoment[row][axis] / (1d - beta2Power);
                    positions[row][axis] -= RADIAL_LEARNING_RATE
                        * correctedFirst
                        / (Math.sqrt(correctedSecond) + 1e-8d);
                }
            }
        }

        for (int row = 0; row < size; row++) {
            final double modelRadius = Math.hypot(
                bestPositions[row][0],
                bestPositions[row][1]
            );
            angles[row] = modelRadius > 0d
                ? Math.atan2(bestPositions[row][1], bestPositions[row][0])
                : 0d;
        }
        return new RadialMap(angles, radii);
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

    /** Squared distance of every embedding row from the origin. */
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

    /**
     * Builds raw frequency rows for every retained document.
     *
     * @param reader index reader supplying term postings
     * @param lexicon term lexicon aligned with the reader
     * @param rowIds selected dense term ids
     * @param docFilter retained document dimensions
     * @return raw frequencies and compact document ids
     * @throws IOException if terms or postings cannot be read
     */
    private static TermDocMatrix termDocMatrix(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet docFilter
    ) throws IOException {
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
                && termsEnum.seekExact(lexicon.formBytes(rowIds[rowRank], termBytes))) {
                postings = termsEnum.postings(postings, PostingsEnum.FREQS);
                for (
                    int docId = postings.nextDoc();
                    docId != DocIdSetIterator.NO_MORE_DOCS;
                    docId = postings.nextDoc()
                ) {
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
     * contribution) are computed from the same full embedding. G² gives the
     * default signed square-root deviance residuals, Freeman-Tukey supplies a
     * variance-stabilised residual control, and Pearson supplies classical CA.
     * </p>
     */
    private static TermMap termMap(
        final TermDocMatrix matrix,
        final HttpPars pars,
        final MetaUtil meta,
        final Projection projection
    ) {
        final Assoc association = switch (projection) {
            case CA -> Assoc.PEARSON;
            case FT_CA -> Assoc.FT;
            case G2_CA -> Assoc.G2;
        };
        final double[][] table = toDoubleTable(matrix.frequencies());
        final ContingencySvd model = new ContingencySvd(table, null)
            .residual(association)
            .decompose()
            .weightAxes(1d)
            .scaleRowsByMass();

        final int requestedDims = pars.getInt("dims", new int[] { 2, 50 }, 6);
        final SvdLayout layout = model.project(requestedDims);
        final double[][] embedding = model.embedding();
        final double[][] coordinates = layout.coords();
        final int dims = coordinates.length == 0 ? 0 : coordinates[0].length;
        final double[] masses = rowMasses(table);

        meta.put("profile", switch (projection) {
            case CA -> "Pearson residuals";
            case FT_CA -> "Freeman-Tukey variance-stabilised residuals";
            case G2_CA -> "signed sqrt G2 deviance residuals";
        });
        meta.put("table", "selected terms only");
        meta.put("rowNormalization", "inverse sqrt row mass");
        meta.put("distance", switch (projection) {
            case CA -> "chi-square / Euclidean CA principal coordinates";
            case FT_CA -> "Euclidean in row-mass-scaled Freeman-Tukey residual coordinates";
            case G2_CA -> "Euclidean in row-mass-scaled G2 residual coordinates";
        });
        meta.put("projection", switch (projection) {
            case CA -> "correspondence analysis";
            case FT_CA -> "Freeman-Tukey residual factor analysis";
            case G2_CA -> "G2 residual factor analysis";
        });
        meta.put("association", association.toString());
        meta.put("rotation", "NONE");
        meta.put("svdAxisWeight", 1d);
        meta.put("svdRank", embedding.length == 0 ? 0 : embedding[0].length);
        meta.put("svdFitConverged", model.fitConverged());
        meta.put("svdFitError", model.fitError());
        meta.put("svdFitIterations", model.fitIterations());

        return new TermMap(
            projection,
            coordinates,
            layout.cos2(),
            cos3(embedding),
            layout.inertia(),
            massPercent(masses),
            squaredDistances(embedding),
            contributions(masses, embedding, dims)
        );
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
        throws IOException {
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
        jw.name("method").value(switch (map.projection()) {
            case CA -> "correspondence analysis";
            case FT_CA -> "Freeman-Tukey residual factor analysis";
            case G2_CA -> "G2 residual factor analysis";
        });
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
        jw.endObject();
    }

    /**
     * Writes the selected term-by-document contingency table as CSV. Columns
     * are documents holding at least one selected-term occurrence; the first
     * cell of each row is the term form.
     */
    private static void writeContingencyCsv(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final TermDocMatrix matrix
    ) throws IOException {
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
        final TermLexicon lexicon
    ) throws IOException {
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

    /**
     * Writes the compact radial node list.
     *
     * @param jw JSON writer
     * @param map final radial coordinates
     * @param rowIds selected term ids aligned with the radial rows
     * @param rowFreq selected term frequencies aligned with the radial rows
     * @param lexicon term lexicon
     * @param termStats corpus term statistics
     * @param focusTokens token count of the focus population
     * @throws IOException if the JSON response cannot be written
     */
    private static void writeRadialData(
        final JsonWriter jw,
        final RadialMap map,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon,
        final TermStats termStats,
        final long focusTokens
    ) throws IOException {
        jw.name("data");
        jw.beginObject();
        jw.name("nodes");
        jw.beginArray();
        final long fieldTokens = termStats.fieldTokens();
        for (int node = 0; node < map.radii().length; node++) {
            final int termId = rowIds[node];
            final long fieldFreq = termStats.termFreq(termId);
            double log2Lift = 0d;
            if (rowFreq[node] > 0L
                && fieldFreq > 0L
                && focusTokens > 0L
                && fieldTokens > 0L) {
                final double lift = (double) rowFreq[node]
                    * fieldTokens
                    / ((double) fieldFreq * focusTokens);
                log2Lift = Math.log(lift) / Math.log(2d);
            }
            jw.beginObject();
            jw.name("id").value(termId);
            jw.name("form").value(lexicon.form(termId));
            jw.name("freq").value(rowFreq[node]);
            jw.name("fieldFreq").value(fieldFreq);
            jw.name("log2Lift").value(round(log2Lift, 6));
            jw.name("radius").value(round(map.radii()[node], 6));
            jw.name("angle").value(round(map.angles()[node], 8));
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
    }

}
