package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermDocScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.SvdLayout;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exports selected terms as taxa, document-vector distances, and alternative
 * term maps over the full corpus.
 *
 * <p>
 * A previous experiment represented every term by its vector of Lucene BM25
 * document scores. It was rejected: BM25 is calibrated to rank documents for
 * one query term, but its scores have no demonstrated comparability across
 * different terms. The resulting term geometry was governed almost entirely
 * by corpus document frequency. Do not restore BM25 term profiles without an
 * explicit cross-term calibration model and evidence that it removes this
 * prevalence geometry.
 * </p>
 *
 * <p>
 * The default map first transforms raw term-document frequencies into
 * positive document-versus-rest G² profiles, then directly decomposes those
 * unnormalised profiles by SVD. Its row coordinates are
 * {@code U_k Sigma_k}. Absence and under-representation contribute zero rather
 * than negative evidence. The raw-frequency SVD and the earlier
 * positive-G², chord-distance, nonmetric-MDS path remain available as
 * {@code projection=SVD} and {@code projection=G2_NMDS} controls.
 * </p>
 */
public class OpClades extends Op
{
    /** Distance matrices that Alix computes in addition to raw characters. */
    private enum Distance
    {
        G2,
        OCHIAI;
    }

    /** HTTP serialization selected by the response extension. */
    private enum Format
    {
        NEXUS,
        CSV;
    }

    /** JSON representations exposed by the endpoint. */
    private enum JsonView
    {
        /** Selected projection with detailed term explanations. */
        DIAGNOSTIC,
        /** Compact selected projection. */
        MAP,
        /** Raw frequencies, positive-G² profiles, and document metadata. */
        MATRIX;
    }

    /** Alternative term-map experiments retained for direct comparison. */
    private enum Projection
    {
        /** Direct truncated SVD of unnormalised positive G² profiles. */
        G2_SVD,
        /** Positive document-vs-rest G², chord distance, and nonmetric MDS. */
        G2_NMDS,
        /** Direct truncated SVD of the raw term-document frequency table. */
        SVD;
    }

    /** Raw term frequencies and derived profiles with document mapping. */
    private record TermDocMatrix(
        int[][] frequencies,
        double[][] profiles,
        int[] docIds
    ) {}

    /** Projection-independent layout and fit diagnostics. */
    private record MapLayout(
        Projection projection,
        double[][] coordinates,
        double[] cos2,
        double[] inertia,
        double stress,
        int iterations,
        boolean converged
    ) {}

    /** Full-corpus vectors, distances, and projected map. */
    private record TermMapAnalysis(
        TermDocMatrix matrix,
        double[][] vectors,
        double[][] distances,
        MapLayout layout
    ) {}

    /** Result of a deterministic nonmetric multidimensional scaling fit. */
    record MdsLayout(
        double[][] coordinates,
        double stress,
        int iterations,
        boolean converged
    ) {}

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

    /**
     * Retains document columns that are not constant across selected terms.
     */
    private static FixedBitSet filterDocs(
        final FixedBitSet liveDocs,
        final FixedBitSet[] docPresence
    )
    {
        final FixedBitSet featDocs = new FixedBitSet(liveDocs.length());

        for (int docId = 0; docId < liveDocs.length(); docId++) {
            if (!liveDocs.get(docId)) {
                continue;
            }

            int present = 0;
            for (final FixedBitSet row : docPresence) {
                if (row.get(docId)) {
                    present++;
                }
            }

            if (present > 0 && present < docPresence.length) {
                featDocs.set(docId);
            }
        }
        return featDocs;
    }

    /**
     * Collects one document-presence bitset per selected term by walking its
     * postings directly.
     */
    private static FixedBitSet[] docPresence(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet liveDocs
    )
        throws IOException {
        final FixedBitSet[] rows = new FixedBitSet[rowIds.length];
        final Terms terms = MultiTerms.getTerms(reader, lexicon.field());
        final TermsEnum termsEnum = terms == null ? null : terms.iterator();
        final BytesRefBuilder termBytes = new BytesRefBuilder();
        PostingsEnum postings = null;

        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            final FixedBitSet docs = new FixedBitSet(reader.maxDoc());
            rows[rowRank] = docs;
            if (termsEnum == null
                || !termsEnum.seekExact(lexicon.formBytes(rowIds[rowRank], termBytes))) {
                continue;
            }
            postings = termsEnum.postings(postings, PostingsEnum.NONE);
            for (
                int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc()
            ) {
                if (liveDocs.get(docId)) {
                    docs.set(docId);
                }
            }
        }
        return rows;
    }

    /**
     * Builds raw frequency rows and positive-root G² profiles.
     *
     * <p>
     * G² is calculated by the shared keyness implementation with the document
     * as focus and the disjoint corpus remainder as reference. A coordinate is
     * {@code sqrt(G²)} only when the term is over-represented in the document;
     * otherwise it is zero. Consequently, absent terms and under-representation
     * never become negative evidence.
     * </p>
     *
     * @param reader    index reader supplying term postings
     * @param lexicon   term lexicon aligned with the reader
     * @param rowIds    selected dense term ids
     * @param docFilter retained document dimensions
     * @param termStats corpus, term and document statistics
     * @return raw frequencies, profile weights, and compact document ids
     * @throws IOException if terms or postings cannot be read
     */
    private static TermDocMatrix termDocMatrix(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet docFilter,
        final TermStats termStats
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
            final int docRank = docRanks[docId];
            if (docRank >= 0) {
                docIds[docRank] = docId;
            }
        }

        final int[][] frequencies = new int[rowIds.length][docCount];
        final double[][] profiles = new double[rowIds.length][docCount];
        final Terms terms = MultiTerms.getTerms(reader, lexicon.field());
        final TermsEnum termsEnum = terms == null ? null : terms.iterator();
        final BytesRefBuilder termBytes = new BytesRefBuilder();
        final int[] docTokens = termStats.docTokens();
        final TermDocScorer scorer = new TermDocScorer.Keyness(
            new KeynessScorer.G2()
        );
        PostingsEnum postings = null;

        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            final int termId = rowIds[rowRank];
            final TermDocScorer.Prepared prepared = scorer.prepare(
                termStats.fieldTokens(),
                termStats.fieldDocs(),
                termStats.termFreq(termId),
                termStats.termDocs(termId)
            );

            if (termsEnum == null
                || !termsEnum.seekExact(lexicon.formBytes(termId, termBytes))) {
                continue;
            }
            postings = termsEnum.postings(postings, PostingsEnum.FREQS);
            for (
                int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc()
            ) {
                if (docFilter.get(docId)) {
                    final int docRank = docRanks[docId];
                    final int frequency = postings.freq();
                    frequencies[rowRank][docRank] = frequency;
                    profiles[rowRank][docRank] = positiveRoot(
                        prepared.score(frequency, docTokens[docId])
                    );
                }
            }
        }
        return new TermDocMatrix(frequencies, profiles, docIds);
    }

    /**
     * Retains every live document containing at least one selected term.
     *
     * <p>
     * Unlike binary-character filtering, this profile filter retains
     * documents containing every selected term because their frequencies and
     * positive G² values may still discriminate the rows.
     * </p>
     *
     * @param liveDocs live documents in the reader snapshot
     * @param docPresence one presence row per selected term
     * @return union of selected-term document sets restricted to live documents
     */
    private static FixedBitSet unionDocs(
        final FixedBitSet liveDocs,
        final FixedBitSet[] docPresence
    ) {
        final FixedBitSet union = new FixedBitSet(liveDocs.length());
        for (final FixedBitSet row : docPresence) {
            union.or(row);
        }
        union.and(liveDocs);
        return union;
    }

    /**
     * Builds the selected full-corpus term map.
     *
     * @param reader index reader supplying postings
     * @param lexicon selected-term lexicon
     * @param rowIds selected dense term ids
     * @param docFilter retained document columns
     * @param termStats corpus and term statistics
     * @param pars resolved HTTP parameters
     * @param meta response metadata collector
     * @return frequencies, projection vectors, distances, and layout
     * @throws IOException if postings cannot be read
     */
    private static TermMapAnalysis termMapAnalysis(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet docFilter,
        final TermStats termStats,
        final HttpPars pars,
        final MetaUtil meta
    ) throws IOException {
        final TermDocMatrix matrix = termDocMatrix(
            reader,
            lexicon,
            rowIds,
            docFilter,
            termStats
        );
        final Projection projection = pars.getEnum(
            "projection",
            Projection.G2_SVD
        );

        meta.put("documents", docFilter.cardinality());
        meta.put("documentUniverse", "full corpus");
        meta.put("focusRestricted", false);
        meta.put("source", "raw term-document frequencies");
        return switch (projection) {
            case G2_SVD -> g2SvdAnalysis(matrix, pars, meta);
            case G2_NMDS -> g2NmdsAnalysis(matrix, pars, meta);
            case SVD -> rawSvdAnalysis(matrix, pars, meta);
        };
    }

    /** Builds the retained positive-G² chord NMDS experiment. */
    private static TermMapAnalysis g2NmdsAnalysis(
        final TermDocMatrix matrix,
        final HttpPars pars,
        final MetaUtil meta
    ) {
        final double[][] normalized = normalizeRows(matrix.profiles());
        final double[][] distances = chordDistances(normalized);
        final int starts = pars.getInt("mds-starts", new int[] { 1, 20 }, 4);
        final int maxIterations = pars.getInt(
            "mds-iterations",
            new int[] { 10, 5000 },
            1000
        );
        final double tolerance = pars.getDouble("mds-tolerance", 1e-7d);
        final MdsLayout map = nonmetricMds(
            distances,
            starts,
            maxIterations,
            tolerance
        );

        meta.put("profile", "sqrt positive document-vs-rest G2");
        meta.put("rowNormalization", "L2");
        meta.put("distance", "chord");
        meta.put("projection", "nonmetric MDS");
        meta.put("mdsStress", map.stress());
        meta.put("mdsIterations", map.iterations());
        meta.put("mdsConverged", map.converged());
        return new TermMapAnalysis(
            matrix,
            matrix.profiles(),
            distances,
            new MapLayout(
                Projection.G2_NMDS,
                map.coordinates(),
                null,
                null,
                map.stress(),
                map.iterations(),
                map.converged()
            )
        );
    }

    /** Builds direct principal coordinates from positive-G² profile rows. */
    private static TermMapAnalysis g2SvdAnalysis(
        final TermDocMatrix matrix,
        final HttpPars pars,
        final MetaUtil meta
    ) {
        return svdAnalysis(
            matrix,
            matrix.profiles(),
            Projection.G2_SVD,
            "sqrt positive document-vs-rest G2",
            "positive-G2 SVD",
            pars,
            meta
        );
    }

    /** Builds direct principal coordinates from raw frequency rows. */
    private static TermMapAnalysis rawSvdAnalysis(
        final TermDocMatrix matrix,
        final HttpPars pars,
        final MetaUtil meta
    ) {
        final double[][] frequencies = frequencyRows(matrix.frequencies());
        return svdAnalysis(
            matrix,
            frequencies,
            Projection.SVD,
            "raw term-document frequency",
            "raw-frequency SVD",
            pars,
            meta
        );
    }

    /** Decomposes an already prepared non-negative term-document matrix. */
    private static TermMapAnalysis svdAnalysis(
        final TermDocMatrix matrix,
        final double[][] vectors,
        final Projection projection,
        final String profileLabel,
        final String projectionLabel,
        final HttpPars pars,
        final MetaUtil meta
    ) {
        final ContingencySvd model = ContingencySvd.fromScores(vectors);
        final int dims = pars.getInt("dims", new int[] { 2, 50 }, 6);
        final SvdLayout map = model.principalCoordinates(dims);
        final double[][] distances = euclideanDistances(model.embedding());

        meta.put("profile", profileLabel);
        meta.put("rowNormalization", "none");
        meta.put("distance", "Euclidean in full SVD embedding");
        meta.put("projection", projectionLabel);
        meta.put("svdCentered", false);
        meta.put("svdAxisWeight", 1d);
        meta.put("svdRank", map.inertia().length);
        return new TermMapAnalysis(
            matrix,
            vectors,
            distances,
            new MapLayout(
                projection,
                map.coords(),
                map.cos2(),
                map.inertia(),
                Double.NaN,
                0,
                true
            )
        );
    }

    /** Converts integer frequency rows to the direct SVD input matrix. */
    private static double[][] frequencyRows(final int[][] frequencies)
    {
        final double[][] rows = new double[frequencies.length][];
        for (int row = 0; row < frequencies.length; row++) {
            rows[row] = new double[frequencies[row].length];
            for (int col = 0; col < frequencies[row].length; col++) {
                rows[row][col] = frequencies[row][col];
            }
        }
        return rows;
    }

    /**
     * Converts signed G² to a positive-root profile coordinate.
     *
     * @param signedG2 signed document-versus-rest G²
     * @return square root for positive finite scores, otherwise zero
     */
    static double positiveRoot(final double signedG2)
    {
        if (!(signedG2 > 0d) || !Double.isFinite(signedG2)) {
            return 0d;
        }
        return Math.sqrt(signedG2);
    }

    /**
     * Returns the share of squared row energy held by its largest coordinates.
     */
    private static double energyShare(final double[] row, final int count)
    {
        final double[] energy = new double[row.length];
        double total = 0d;
        for (int col = 0; col < row.length; col++) {
            energy[col] = row[col] * row[col];
            total += energy[col];
        }
        if (total <= 0d) {
            return 0d;
        }
        Arrays.sort(energy);
        double top = 0d;
        for (int rank = 0; rank < Math.min(count, energy.length); rank++) {
            top += energy[energy.length - 1 - rank];
        }
        return top / total;
    }

    /**
     * Returns the L2 norm of a vector.
     */
    private static double l2(final double[] row)
    {
        double squared = 0d;
        for (final double value : row) {
            squared += value * value;
        }
        return Math.sqrt(squared);
    }

    /** Returns term ranks ordered by increasing chord distance. */
    private static int[] nearest(
        final double[][] distances,
        final int row,
        final int count
    ) {
        final Integer[] ranks = new Integer[distances.length - 1];
        int cursor = 0;
        for (int other = 0; other < distances.length; other++) {
            if (other != row) {
                ranks[cursor++] = other;
            }
        }
        Arrays.sort(
            ranks,
            Comparator.comparingDouble(other -> distances[row][other])
        );
        final int size = Math.min(count, ranks.length);
        final int[] nearest = new int[size];
        for (int rank = 0; rank < size; rank++) {
            nearest[rank] = ranks[rank];
        }
        return nearest;
    }

    /**
     * Counts non-zero coordinates in a vector.
     */
    private static int nonZero(final double[] row)
    {
        int count = 0;
        for (final double value : row) {
            if (value != 0d) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns a request parameter or a fallback for absent and blank values.
     */
    private static String parameter(
        final HttpServletRequest request,
        final String name,
        final String fallback
    ) {
        final String value = request.getParameter(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Returns a stored string or numeric field as display text.
     */
    private static String storedText(
        final Document document,
        final String fieldName
    ) {
        final IndexableField field = document.getField(fieldName);
        if (field == null) {
            return null;
        }
        final String text = field.stringValue();
        if (text != null) {
            return text;
        }
        final Number number = field.numericValue();
        return number == null ? null : number.toString();
    }

    /** Writes one document and its corpus metadata. */
    private static void writeDocument(
        final JsonWriter jw,
        final StoredFields storedFields,
        final int docId,
        final int docTokens,
        final String titleField,
        final String yearField,
        final Integer rank
    ) throws IOException {
        final Document document = storedFields.document(docId);
        jw.beginObject();
        if (rank != null) {
            jw.name("rank").value(rank);
        }
        jw.name("id").value(docId);
        jw.name("tokens").value(docTokens);
        final String title = storedText(document, titleField);
        if (title != null) {
            jw.name("title").value(title);
        }
        final String year = storedText(document, yearField);
        if (year != null) {
            jw.name("year").value(year);
        }
        jw.endObject();
    }

    /**
     * Appends one safely quoted NEXUS label.
     */
    private static void appendNexusLabel(final Writer writer, final String label)
        throws IOException {
        writer.append('\'');
        for (int i = 0; i < label.length(); i++) {
            final char c = label.charAt(i);
            if (c == '\'') {
                writer.append('\'');
            }
            writer.append(c);
        }
        writer.append('\'');
    }

    /**
     * Writes a STANDARD binary CHARACTERS block. Columns follow Lucene
     * document-id order after constant-column filtering.
     */
    private static void writeCharacters(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet[] docPresence,
        final FixedBitSet docFilter
    )
        throws IOException {
        writer.append("BEGIN CHARACTERS;\n");
        writer.append("    DIMENSIONS NTAX=")
            .append(Integer.toString(rowIds.length))
            .append(" NCHAR=")
            .append(Integer.toString(docFilter.cardinality()))
            .append(";\n");
        writer.append("    FORMAT DATATYPE=STANDARD SYMBOLS=\"01\" LABELS=LEFT;\n");
        writer.append("    MATRIX\n");

        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            writer.append("        ");
            appendNexusLabel(writer, lexicon.form(rowIds[rowRank]));
            writer.append(' ');
            for (int docId = 0; docId < docFilter.length(); docId++) {
                if (docFilter.get(docId)) {
                    writer.append(docPresence[rowRank].get(docId) ? '1' : '0');
                }
            }
            writer.append('\n');
        }

        writer.append("    ;\n");
        writer.append("END;\n");
    }

    /**
     * Returns the chord distance associated with binary Ochiai similarity.
     *
     * <p>
     * For binary document vectors, Ochiai similarity is cosine similarity:
     * {@code |A intersection B| / sqrt(|A| |B|)}. Chord distance is the
     * Euclidean distance between the corresponding unit vectors:
     * {@code sqrt(2 - 2 * similarity)}.
     * </p>
     */
    static double ochiaiDistance(
        final FixedBitSet rowA,
        final FixedBitSet rowB,
        final int cardA,
        final int cardB
    ) {
        if (cardA == 0 || cardB == 0) {
            return cardA == cardB ? 0d : Math.sqrt(2d);
        }

        final long intersection = FixedBitSet.intersectionCount(rowA, rowB);
        final double similarity = intersection / Math.sqrt((double) cardA * cardB);
        return Math.sqrt(Math.max(0d, 2d - 2d * similarity));
    }

    /** Computes the full symmetric Ochiai chord-distance matrix. */
    static double[][] ochiaiDistances(
        final FixedBitSet[] docPresence
    ) {
        final int size = docPresence.length;
        final int[] cardinalities = new int[size];
        for (int rowRank = 0; rowRank < size; rowRank++) {
            cardinalities[rowRank] = docPresence[rowRank].cardinality();
        }

        // Compute each symmetric pair only once.
        final double[][] distances = new double[size][size];
        for (int rowRank = 0; rowRank < size; rowRank++) {
            for (int colRank = 0; colRank < rowRank; colRank++) {
                final double distance = ochiaiDistance(
                    docPresence[rowRank],
                    docPresence[colRank],
                    cardinalities[rowRank],
                    cardinalities[colRank]
                );
                distances[rowRank][colRank] = distance;
                distances[colRank][rowRank] = distance;
            }
        }
        return distances;
    }

    /**
     * L2-normalizes profile rows without changing the source matrix.
     *
     * @param rows term-by-document profile matrix
     * @return normalized matrix with one unit vector per non-zero row
     * @throws IllegalArgumentException if rows are ragged or contain a zero row
     */
    static double[][] normalizeRows(final double[][] rows)
    {
        if (rows.length == 0) {
            return new double[0][0];
        }
        final int width = rows[0].length;
        final double[][] normalized = new double[rows.length][width];
        for (int row = 0; row < rows.length; row++) {
            if (rows[row].length != width) {
                throw new IllegalArgumentException("Score matrix is ragged");
            }
            final double norm = l2(rows[row]);
            if (!(norm > 0d) || !Double.isFinite(norm)) {
                throw new IllegalArgumentException(
                    "Term profile row " + row + " has no finite positive norm"
                );
            }
            for (int col = 0; col < width; col++) {
                normalized[row][col] = rows[row][col] / norm;
            }
        }
        return normalized;
    }

    /**
     * Computes Euclidean chord distances between L2-normalized rows.
     *
     * @param normalized unit term-by-document vectors
     * @return full symmetric chord-distance matrix
     * @throws IllegalArgumentException if rows are ragged
     */
    static double[][] chordDistances(final double[][] normalized)
    {
        final int size = normalized.length;
        final int width = size == 0 ? 0 : normalized[0].length;
        final double[][] distances = new double[size][size];
        for (int row = 0; row < size; row++) {
            if (normalized[row].length != width) {
                throw new IllegalArgumentException("Normalized matrix is ragged");
            }
            for (int col = 0; col < row; col++) {
                double squared = 0d;
                for (int doc = 0; doc < width; doc++) {
                    final double delta = normalized[row][doc]
                        - normalized[col][doc];
                    squared += delta * delta;
                }
                final double distance = Math.sqrt(Math.max(0d, squared));
                distances[row][col] = distance;
                distances[col][row] = distance;
            }
        }
        return distances;
    }

    /** Computes full Euclidean distances between equal-width rows. */
    private static double[][] euclideanDistances(final double[][] rows)
    {
        final int size = rows.length;
        final int width = size == 0 ? 0 : rows[0].length;
        final double[][] distances = new double[size][size];
        for (int row = 0; row < size; row++) {
            if (rows[row].length != width) {
                throw new IllegalArgumentException("Embedding matrix is ragged");
            }
            for (int col = 0; col < row; col++) {
                double squared = 0d;
                for (int axis = 0; axis < width; axis++) {
                    final double delta = rows[row][axis] - rows[col][axis];
                    squared += delta * delta;
                }
                final double distance = Math.sqrt(Math.max(0d, squared));
                distances[row][col] = distance;
                distances[col][row] = distance;
            }
        }
        return distances;
    }

    /** Pair of rows ordered by its original dissimilarity. */
    private record MdsPair(int row, int col, double dissimilarity) {}

    /**
     * Fits a deterministic two-dimensional nonmetric MDS map by monotone
     * regression and SMACOF majorization.
     *
     * <p>
     * The first start uses classical scaling only as an initialization. Later
     * starts use a fixed pseudo-random sequence. The returned fit is the start
     * with the lowest normalized Kruskal stress.
     * </p>
     *
     * @param dissimilarities full symmetric distance matrix
     * @param starts deterministic starts to compare
     * @param maxIterations maximum SMACOF updates per start
     * @param tolerance relative stress convergence threshold
     * @return best two-dimensional layout
     */
    static MdsLayout nonmetricMds(
        final double[][] dissimilarities,
        final int starts,
        final int maxIterations,
        final double tolerance
    ) {
        if (starts < 1 || maxIterations < 1) {
            throw new IllegalArgumentException(
                "NMDS starts and iterations must be positive"
            );
        }
        if (!(tolerance > 0d && tolerance < 1d)) {
            throw new IllegalArgumentException(
                "NMDS tolerance must be strictly between 0 and 1"
            );
        }
        validateDistances(dissimilarities);
        final int size = dissimilarities.length;
        if (size == 0) {
            return new MdsLayout(new double[0][2], 0d, 0, true);
        }
        if (size == 1) {
            return new MdsLayout(new double[1][2], 0d, 0, true);
        }

        final MdsPair[] pairs = sortedPairs(dissimilarities);
        final Random random = new Random(0x4e4d44534cL);
        MdsLayout best = null;
        for (int start = 0; start < starts; start++) {
            final double[][] initial = start == 0
                ? classicalStart(dissimilarities)
                : randomStart(size, random);
            final MdsLayout candidate = fitNonmetricMds(
                initial,
                pairs,
                maxIterations,
                tolerance
            );
            if (best == null || candidate.stress() < best.stress()) {
                best = candidate;
            }
        }
        orient(best.coordinates());
        return best;
    }

    /** Validates the square symmetric dissimilarity matrix. */
    private static void validateDistances(final double[][] distances)
    {
        for (int row = 0; row < distances.length; row++) {
            if (distances[row].length != distances.length) {
                throw new IllegalArgumentException("Distance matrix is not square");
            }
            if (Math.abs(distances[row][row]) > 1e-12d) {
                throw new IllegalArgumentException("Distance diagonal is not zero");
            }
            for (int col = 0; col < row; col++) {
                final double value = distances[row][col];
                if (!Double.isFinite(value) || value < 0d) {
                    throw new IllegalArgumentException(
                        "Distances must be finite and non-negative"
                    );
                }
                if (Math.abs(value - distances[col][row]) > 1e-10d) {
                    throw new IllegalArgumentException(
                        "Distance matrix is not symmetric"
                    );
                }
            }
        }
    }

    /** Returns all lower-triangle pairs in stable dissimilarity order. */
    private static MdsPair[] sortedPairs(final double[][] distances)
    {
        final int size = distances.length;
        final MdsPair[] pairs = new MdsPair[size * (size - 1) / 2];
        int cursor = 0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < row; col++) {
                pairs[cursor++] = new MdsPair(row, col, distances[row][col]);
            }
        }
        Arrays.sort(
            pairs,
            Comparator.comparingDouble(MdsPair::dissimilarity)
                .thenComparingInt(MdsPair::row)
                .thenComparingInt(MdsPair::col)
        );
        return pairs;
    }

    /** Builds a two-axis classical-scaling initialization. */
    private static double[][] classicalStart(final double[][] distances)
    {
        final int size = distances.length;
        final double[] rowMeans = new double[size];
        double totalMean = 0d;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                rowMeans[row] += distances[row][col] * distances[row][col];
            }
            rowMeans[row] /= size;
            totalMean += rowMeans[row];
        }
        totalMean /= size;

        final double[][] gram = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                gram[row][col] = -0.5d * (
                    distances[row][col] * distances[row][col]
                    - rowMeans[row]
                    - rowMeans[col]
                    + totalMean
                );
            }
        }

        final double[][] vectors = new double[2][size];
        final double[][] coordinates = new double[size][2];
        for (int axis = 0; axis < 2; axis++) {
            final double[] vector = vectors[axis];
            for (int row = 0; row < size; row++) {
                vector[row] = Math.sin((row + 1d) * (axis + 1.61803398875d));
            }
            orthonormalize(vector, vectors, axis);
            for (int iteration = 0; iteration < 1000; iteration++) {
                final double[] next = multiply(gram, vector);
                orthonormalize(next, vectors, axis);
                if (dot(next, vector) < 0d) {
                    for (int row = 0; row < size; row++) {
                        next[row] = -next[row];
                    }
                }
                double change = 0d;
                for (int row = 0; row < size; row++) {
                    final double delta = next[row] - vector[row];
                    change += delta * delta;
                    vector[row] = next[row];
                }
                if (change < 1e-24d) {
                    break;
                }
            }
            final double eigenvalue = dot(vector, multiply(gram, vector));
            final double scale = Math.sqrt(Math.max(0d, eigenvalue));
            for (int row = 0; row < size; row++) {
                coordinates[row][axis] = scale * vector[row];
            }
        }
        standardize(coordinates);
        return coordinates;
    }

    /** Fits one NMDS start and retains its lowest-stress iterate. */
    private static MdsLayout fitNonmetricMds(
        final double[][] initial,
        final MdsPair[] pairs,
        final int maxIterations,
        final double tolerance
    ) {
        final int size = initial.length;
        double[][] coordinates = copy(initial);
        double[][] bestCoordinates = copy(initial);
        double bestStress = Double.POSITIVE_INFINITY;
        double previousStress = Double.POSITIVE_INFINITY;
        boolean converged = false;
        int iterations = 0;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            final double[] fitted = disparities(coordinates, pairs);
            final double[][] b = new double[size][size];
            for (int pair = 0; pair < pairs.length; pair++) {
                final MdsPair item = pairs[pair];
                final double distance = pointDistance(
                    coordinates[item.row()],
                    coordinates[item.col()]
                );
                final double ratio = distance > 1e-15d
                    ? fitted[pair] / distance
                    : 0d;
                b[item.row()][item.col()] = -ratio;
                b[item.col()][item.row()] = -ratio;
                b[item.row()][item.row()] += ratio;
                b[item.col()][item.col()] += ratio;
            }

            final double[][] next = new double[size][2];
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    next[row][0] += b[row][col] * coordinates[col][0] / size;
                    next[row][1] += b[row][col] * coordinates[col][1] / size;
                }
            }
            center(next);
            final double stress = normalizedStress(next, pairs);
            iterations = iteration;
            if (stress < bestStress) {
                bestStress = stress;
                bestCoordinates = copy(next);
            }
            if (Double.isFinite(previousStress)
                && Math.abs(previousStress - stress)
                    <= tolerance * Math.max(1d, previousStress)) {
                converged = true;
                break;
            }
            previousStress = stress;
            coordinates = next;
        }
        return new MdsLayout(bestCoordinates, bestStress, iterations, converged);
    }

    /** Fits monotone disparities to current distances using weighted PAVA. */
    private static double[] disparities(
        final double[][] coordinates,
        final MdsPair[] pairs
    ) {
        final int pairCount = pairs.length;
        final double[] values = new double[pairCount];
        final int[] groupByPair = new int[pairCount];
        final double[] groupMeans = new double[pairCount];
        final int[] groupWeights = new int[pairCount];
        int groups = 0;

        for (int pair = 0; pair < pairCount;) {
            final double dissimilarity = pairs[pair].dissimilarity();
            int end = pair;
            double sum = 0d;
            while (end < pairCount
                && Double.compare(pairs[end].dissimilarity(), dissimilarity) == 0) {
                sum += pointDistance(
                    coordinates[pairs[end].row()],
                    coordinates[pairs[end].col()]
                );
                groupByPair[end] = groups;
                end++;
            }
            groupMeans[groups] = sum / (end - pair);
            groupWeights[groups] = end - pair;
            groups++;
            pair = end;
        }

        final double[] blockMeans = new double[groups];
        final int[] blockWeights = new int[groups];
        final int[] blockEnds = new int[groups];
        int blocks = 0;
        for (int group = 0; group < groups; group++) {
            blockMeans[blocks] = groupMeans[group];
            blockWeights[blocks] = groupWeights[group];
            blockEnds[blocks] = group;
            blocks++;
            while (blocks > 1 && blockMeans[blocks - 2] > blockMeans[blocks - 1]) {
                final int weight = blockWeights[blocks - 2]
                    + blockWeights[blocks - 1];
                blockMeans[blocks - 2] = (
                    blockMeans[blocks - 2] * blockWeights[blocks - 2]
                    + blockMeans[blocks - 1] * blockWeights[blocks - 1]
                ) / weight;
                blockWeights[blocks - 2] = weight;
                blockEnds[blocks - 2] = blockEnds[blocks - 1];
                blocks--;
            }
        }

        int firstGroup = 0;
        for (int block = 0; block < blocks; block++) {
            for (int group = firstGroup; group <= blockEnds[block]; group++) {
                groupMeans[group] = blockMeans[block];
            }
            firstGroup = blockEnds[block] + 1;
        }
        double squared = 0d;
        for (int pair = 0; pair < pairCount; pair++) {
            values[pair] = groupMeans[groupByPair[pair]];
            squared += values[pair] * values[pair];
        }
        if (squared > 0d) {
            final double scale = Math.sqrt(pairCount / squared);
            for (int pair = 0; pair < pairCount; pair++) {
                values[pair] *= scale;
            }
        }
        return values;
    }

    /** Returns normalized Kruskal Stress-1 for current coordinates. */
    private static double normalizedStress(
        final double[][] coordinates,
        final MdsPair[] pairs
    ) {
        final double[] fitted = disparities(coordinates, pairs);
        double residual = 0d;
        double denominator = 0d;
        for (int pair = 0; pair < pairs.length; pair++) {
            final double distance = pointDistance(
                coordinates[pairs[pair].row()],
                coordinates[pairs[pair].col()]
            );
            final double delta = distance - fitted[pair];
            residual += delta * delta;
            denominator += fitted[pair] * fitted[pair];
        }
        return denominator > 0d ? Math.sqrt(residual / denominator) : 0d;
    }

    /** Returns deterministic random initial coordinates. */
    private static double[][] randomStart(final int size, final Random random)
    {
        final double[][] coordinates = new double[size][2];
        for (int row = 0; row < size; row++) {
            coordinates[row][0] = random.nextDouble() - 0.5d;
            coordinates[row][1] = random.nextDouble() - 0.5d;
        }
        standardize(coordinates);
        return coordinates;
    }

    /** Centers and scales coordinates to unit root-mean-square radius. */
    private static void standardize(final double[][] coordinates)
    {
        center(coordinates);
        double squared = 0d;
        for (final double[] point : coordinates) {
            squared += point[0] * point[0] + point[1] * point[1];
        }
        final double scale = squared > 0d
            ? Math.sqrt(coordinates.length / squared)
            : 1d;
        for (final double[] point : coordinates) {
            point[0] *= scale;
            point[1] *= scale;
        }
    }

    /** Centers coordinates on the origin. */
    private static void center(final double[][] coordinates)
    {
        double meanX = 0d;
        double meanY = 0d;
        for (final double[] point : coordinates) {
            meanX += point[0];
            meanY += point[1];
        }
        meanX /= coordinates.length;
        meanY /= coordinates.length;
        for (final double[] point : coordinates) {
            point[0] -= meanX;
            point[1] -= meanY;
        }
    }

    /** Rotates and reflects a fitted map into a stable display orientation. */
    private static void orient(final double[][] coordinates)
    {
        if (coordinates.length == 0) {
            return;
        }
        center(coordinates);
        double xx = 0d;
        double yy = 0d;
        double xy = 0d;
        for (final double[] point : coordinates) {
            xx += point[0] * point[0];
            yy += point[1] * point[1];
            xy += point[0] * point[1];
        }
        final double angle = 0.5d * Math.atan2(2d * xy, xx - yy);
        final double cosine = Math.cos(angle);
        final double sine = Math.sin(angle);
        for (final double[] point : coordinates) {
            final double x = cosine * point[0] + sine * point[1];
            final double y = -sine * point[0] + cosine * point[1];
            point[0] = x;
            point[1] = y;
        }
        reflectAxis(coordinates, 0);
        reflectAxis(coordinates, 1);
    }

    /** Reflects an axis so its largest absolute coordinate is positive. */
    private static void reflectAxis(final double[][] coordinates, final int axis)
    {
        int extreme = 0;
        for (int row = 1; row < coordinates.length; row++) {
            if (Math.abs(coordinates[row][axis])
                > Math.abs(coordinates[extreme][axis])) {
                extreme = row;
            }
        }
        if (coordinates[extreme][axis] < 0d) {
            for (final double[] point : coordinates) {
                point[axis] = -point[axis];
            }
        }
    }

    /** Multiplies a square matrix by a vector. */
    private static double[] multiply(final double[][] matrix, final double[] vector)
    {
        final double[] product = new double[vector.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int col = 0; col < vector.length; col++) {
                product[row] += matrix[row][col] * vector[col];
            }
        }
        return product;
    }

    /** Makes a vector unit length and orthogonal to preceding basis vectors. */
    private static void orthonormalize(
        final double[] vector,
        final double[][] basis,
        final int count
    ) {
        for (int rank = 0; rank < count; rank++) {
            final double projection = dot(vector, basis[rank]);
            for (int row = 0; row < vector.length; row++) {
                vector[row] -= projection * basis[rank][row];
            }
        }
        double norm = Math.sqrt(dot(vector, vector));
        if (!(norm > 1e-15d)) {
            Arrays.fill(vector, 0d);
            vector[count % vector.length] = 1d;
            norm = 1d;
        }
        for (int row = 0; row < vector.length; row++) {
            vector[row] /= norm;
        }
    }

    /** Returns a vector dot product. */
    private static double dot(final double[] a, final double[] b)
    {
        double product = 0d;
        for (int row = 0; row < a.length; row++) {
            product += a[row] * b[row];
        }
        return product;
    }

    /** Returns Euclidean distance between two display points. */
    private static double pointDistance(final double[] a, final double[] b)
    {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** Deep-copies a rectangular matrix. */
    private static double[][] copy(final double[][] source)
    {
        final double[][] copy = new double[source.length][];
        for (int row = 0; row < source.length; row++) {
            copy[row] = Arrays.copyOf(source[row], source[row].length);
        }
        return copy;
    }

    /**
     * Returns chord distance between two real-valued document vectors.
     *
     * <p>
     * The result is the Euclidean distance between the corresponding unit
     * vectors: {@code sqrt(2 - 2 * cosine)}.
     * </p>
     *
     * @param rowA first document vector
     * @param rowB second document vector
     * @return cosine chord distance
     * @throws IllegalArgumentException if vector lengths differ
     */
    static double cosineDistance(final double[] rowA, final double[] rowB)
    {
        if (rowA.length != rowB.length) {
            throw new IllegalArgumentException(
                "Vector lengths differ: " + rowA.length + " != " + rowB.length
            );
        }

        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int docId = 0; docId < rowA.length; docId++) {
            final double a = rowA[docId];
            final double b = rowB[docId];
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA == 0d || normB == 0d) {
            return normA == normB ? 0d : Math.sqrt(2d);
        }
        final double similarity = Math.max(
            -1d,
            Math.min(1d, dot / Math.sqrt(normA * normB))
        );
        return Math.sqrt(Math.max(0d, 2d - 2d * similarity));
    }

    /**
     * Computes a full symmetric cosine chord-distance matrix.
     *
     * @param termDocVectors document vectors indexed by selected-term rank
     * @return symmetric distance matrix
     */
    static double[][] cosineDistances(final double[][] termDocVectors)
    {
        final int size = termDocVectors.length;
        final double[][] distances = new double[size][size];
        for (int rowRank = 0; rowRank < size; rowRank++) {
            for (int colRank = 0; colRank < rowRank; colRank++) {
                final double distance = cosineDistance(
                    termDocVectors[rowRank],
                    termDocVectors[colRank]
                );
                distances[rowRank][colRank] = distance;
                distances[colRank][rowRank] = distance;
            }
        }
        return distances;
    }

    /** Writes a full square distance matrix as a NEXUS block. */
    private static void writeNexusDistances(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final double[][] distances,
        final Distance distance
    )
        throws IOException {
        final int size = rowIds.length;
        writer.append("BEGIN DISTANCES;\n");
        writer.append("    DIMENSIONS NTAX=")
            .append(Integer.toString(size))
            .append(";\n");
        writer.append("    FORMAT TRIANGLE=BOTH DIAGONAL LABELS=LEFT;\n");
        writer.append("    [DISTANCE=").append(distance.name()).append("]\n");
        writer.append("    MATRIX\n");

        for (int rowRank = 0; rowRank < size; rowRank++) {
            writer.append("        ");
            appendNexusLabel(writer, lexicon.form(rowIds[rowRank]));
            for (int colRank = 0; colRank < size; colRank++) {
                writer.append(' ')
                    .append(Double.toString(distances[rowRank][colRank]));
            }
            writer.append('\n');
        }

        writer.append("    ;\n");
        writer.append("END;\n");
    }

    /** Writes a full square distance matrix for SplitsTree's CSV importer. */
    private static void writeCsvDistances(
        final Writer writer,
        final TermLexicon lexicon,
        final int[] rowIds,
        final double[][] distances
    )
        throws IOException {
        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            writer.append(csvEscape(lexicon.form(rowIds[rowRank])));
            for (int colRank = 0; colRank < rowIds.length; colRank++) {
                writer.append(',')
                    .append(Double.toString(distances[rowRank][colRank]));
            }
            writer.append('\n');
        }
    }

    /**
     * Builds the selected term-by-document data once. NEXUS contains both raw
     * characters and distances; CSV contains the selected distance matrix.
     */
    private static void write(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Format format
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final Writer writer = response.getWriter();

        // Keep the same taxon selection and order source as the terms endpoint.
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            writer.append("Houston, on a un problème.");
            return;
        }
        // here get different stats used by distance algos
        final TermStats termStats = topTerms.termStats();

        final IntList rowList = new IntList(topTerms.size());
        for (final TermEntry term : topTerms) {
            rowList.push(term.termId());
        }
        final int[] rowIds = rowList.toUniq();
        final TermLexicon lexicon = topTerms.lexicon();
        final FixedBitSet liveDocs = liveDocs(index.reader());
        final FixedBitSet[] docPresence = docPresence(
            index.reader(),
            lexicon,
            rowIds,
            liveDocs
        );
        final FixedBitSet profileDocs = unionDocs(liveDocs, docPresence);
        final FixedBitSet featDocs = filterDocs(liveDocs, docPresence);

        // Binary characters omit constant columns; G² profiles keep the union.
        for (final FixedBitSet row : docPresence) {
            row.and(featDocs);
        }

        final Distance distance = pars.getEnum("distance", Distance.G2);
        final double[][] distances = switch (distance) {
            case G2 -> chordDistances(normalizeRows(termDocMatrix(
                index.reader(),
                lexicon,
                rowIds,
                profileDocs,
                termStats
            ).profiles()));
            case OCHIAI -> ochiaiDistances(docPresence);
        };

        switch (format) {
            case NEXUS -> {
                writer.append("#NEXUS\n\n");
                writeCharacters(writer, lexicon, rowIds, docPresence, featDocs);
                writer.append('\n');
                writeNexusDistances(
                    writer,
                    lexicon,
                    rowIds,
                    distances,
                    distance
                );
            }
            case CSV -> writeCsvDistances(
                writer,
                lexicon,
                rowIds,
                distances
            );
        }
    }

    /** Writes projection-specific axis and fit metadata. */
    private static void writeAxes(
        final JsonWriter jw,
        final MapLayout map
    ) throws IOException {
        jw.name("axes");
        jw.beginObject();
        final int dims = map.coordinates().length == 0
            ? 0
            : map.coordinates()[0].length;
        jw.name("dims").value(dims);
        switch (map.projection()) {
            case G2_NMDS -> {
                jw.name("method").value("nonmetric MDS");
                jw.name("distance").value("L2-normalized positive-G2 chord");
                jw.name("stress").value(round(map.stress(), 6));
                jw.name("iterations").value(map.iterations());
                jw.name("converged").value(map.converged());
            }
            case G2_SVD, SVD -> {
                final double[] inertia = map.inertia();
                final double dim1 = inertia.length > 0 ? inertia[0] : 0d;
                final double dim2 = inertia.length > 1 ? inertia[1] : 0d;
                double emitted = 0d;
                for (int axis = 0; axis < Math.min(dims, inertia.length); axis++) {
                    emitted += inertia[axis];
                }
                jw.name("method").value(
                    map.projection() == Projection.G2_SVD
                        ? "positive-G2 SVD"
                        : "raw-frequency SVD"
                );
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
            }
        }
        jw.endObject();
    }

    /**
     * Writes raw frequencies and derived profiles with document metadata.
     */
    private static void writeMatrixData(
        final JsonWriter jw,
        final IndexReader reader,
        final HttpServletRequest request,
        final TermMapAnalysis analysis,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon,
        final TermStats termStats
    ) throws IOException {
        final String titleField = parameter(request, "ftitle", "title");
        final String yearField = parameter(request, "fyear", "year");
        final StoredFields storedFields = reader.storedFields();
        final int[] docTokens = termStats.docTokens();
        final TermDocMatrix matrix = analysis.matrix();

        jw.name("data");
        jw.beginObject();
        jw.name("kind").value("term-document-frequency");
        jw.name("derivedProfile").value("sqrt-positive-document-vs-rest-G2");
        jw.name("rows").value(rowIds.length);
        jw.name("cols").value(matrix.docIds().length);

        jw.name("documents");
        jw.beginArray();
        for (int docRank = 0; docRank < matrix.docIds().length; docRank++) {
            final int docId = matrix.docIds()[docRank];
            writeDocument(
                jw,
                storedFields,
                docId,
                docTokens[docId],
                titleField,
                yearField,
                docRank
            );
        }
        jw.endArray();

        jw.name("terms");
        jw.beginArray();
        for (int row = 0; row < rowIds.length; row++) {
            final int termId = rowIds[row];
            final int corpusDf = termStats.termDocs(termId);
            jw.beginObject();
            jw.name("id").value(termId);
            jw.name("form").value(lexicon.form(termId));
            jw.name("freq").value(rowFreq[row]);
            jw.name("corpusTf").value(termStats.termFreq(termId));
            jw.name("corpusDf").value(corpusDf);
            jw.name("frequencies");
            jw.beginArray();
            for (final int frequency : matrix.frequencies()[row]) {
                jw.value(frequency);
            }
            jw.endArray();
            jw.name("profile");
            jw.beginArray();
            for (final double weight : matrix.profiles()[row]) {
                jw.value(weight);
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
    }

    /**
     * Writes the selected term map and optional diagnostics.
     */
    private static void writeMapData(
        final JsonWriter jw,
        final HttpPars pars,
        final TermMapAnalysis analysis,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon,
        final TermStats termStats,
        final boolean diagnostic
    ) throws IOException {
        final MapLayout map = analysis.layout();
        final double[][] vectors = analysis.vectors();
        final double[][] distances = analysis.distances();
        final int nearestCount = pars.getInt("nearest", new int[] { 1, 20 }, 5);

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
            if (map.cos2() != null) {
                jw.name("cos2").value(round(map.cos2()[node], 4));
            }
            jw.name("coords");
            jw.beginArray();
            for (final double coordinate : coords) {
                jw.value(round(coordinate, 4));
            }
            jw.endArray();

            if (diagnostic) {
                final int corpusDf = termStats.termDocs(termId);
                jw.name("corpusTf").value(termStats.termFreq(termId));
                jw.name("corpusDf").value(corpusDf);
                jw.name("vectorNonzeroDocs").value(nonZero(vectors[node]));
                jw.name("vectorNorm").value(round(l2(vectors[node]), 6));
                jw.name("top1Energy").value(round(energyShare(vectors[node], 1), 6));
                jw.name("top5Energy").value(round(energyShare(vectors[node], 5), 6));
                jw.name("top10Energy").value(round(energyShare(vectors[node], 10), 6));

                jw.name("nearest");
                jw.beginArray();
                for (final int other : nearest(distances, node, nearestCount)) {
                    jw.beginObject();
                    jw.name("id").value(rowIds[other]);
                    jw.name("form").value(lexicon.form(rowIds[other]));
                    jw.name("distance").value(round(distances[node][other], 6));
                    jw.endObject();
                }
                jw.endArray();
            }
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
    }

    /**
     * Writes a positive-G² SVD map or one of the retained control maps as JSON.
     *
     * <p>
     * The response shape matches the co-occurrence semantic-map endpoint:
     * axes contain projection diagnostics and nodes contain display
     * coordinates. The default {@code projection=G2_SVD} directly decomposes
     * unnormalised positive document-versus-rest G² profiles.
     * {@code projection=SVD} retains raw-frequency SVD as a control, while
     * {@code projection=G2_NMDS} retains the prior positive-G² chord experiment.
     * Parameter {@code view=diagnostic} adds term explanations;
     * {@code view=matrix} returns raw frequencies, derived profiles, and
     * document-column metadata. The default is {@code view=map}.
     * </p>
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
        if (view == JsonView.DIAGNOSTIC) {
            pars.getInt("nearest", new int[] { 1, 20 }, 5);
        }
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);

        int[] rowIds = null;
        long[] rowFreq = null;
        TermLexicon lexicon = null;
        TermStats termStats = null;
        TermMapAnalysis analysis = null;

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
            final FixedBitSet[] presence = docPresence(
                index.reader(),
                lexicon,
                rowIds,
                liveDocs
            );
            final FixedBitSet documents = unionDocs(liveDocs, presence);
            analysis = termMapAnalysis(
                index.reader(),
                lexicon,
                rowIds,
                documents,
                termStats,
                pars,
                meta
            );
        }

        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();

            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();

            if (analysis != null) {
                switch (view) {
                    case DIAGNOSTIC -> writeMapData(
                        jw,
                        pars,
                        analysis,
                        rowIds,
                        rowFreq,
                        lexicon,
                        termStats,
                        true
                    );
                    case MAP -> writeMapData(
                        jw,
                        pars,
                        analysis,
                        rowIds,
                        rowFreq,
                        lexicon,
                        termStats,
                        false
                    );
                    case MATRIX -> writeMatrixData(
                        jw,
                        index.reader(),
                        request,
                        analysis,
                        rowIds,
                        rowFreq,
                        lexicon,
                        termStats
                    );
                }
            }

            jw.endObject();
        }
    }

    @Override
    protected void txt(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        write(index, request, response, Format.NEXUS);
    }

    @Override
    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        write(index, request, response, Format.CSV);
    }
}
