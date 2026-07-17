package com.github.oeuvres.alix.web;


import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.LuceneIndex;
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
 * Exports selected terms as taxa, document-vector distances, a centred BM25
 * term-by-document SVD map, and its score and decomposition diagnostics.
 */
public class OpClades extends Op
{
    /** Distance matrices that Alix computes in addition to raw characters. */
    private enum Distance
    {
        BM25,
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
        /** BM25 map with detailed term and axis explanations. */
        DIAGNOSTIC,
        /** Existing compact BM25 map. */
        MAP,
        /** Complete term-by-document BM25 matrix and column metadata. */
        MATRIX;
    }

    /** BM25 matrix with its compact-column to Lucene-document mapping. */
    private record TermDocMatrix(double[][] scores, int[] docIds) {}

    /** Decomposed BM25 matrix and its projected term layout. */
    private record Bm25Analysis(
        TermDocMatrix matrix,
        ContingencySvd model,
        SvdLayout layout
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
     * Builds one weighted document vector per selected term.
     *
     * <p>
     * Every retained document is first scored with a zero term frequency so
     * scorers for which absence is informative, such as signed keyness, remain
     * correct. Posting visits then replace the coordinates of documents that
     * contain the term.
     * </p>
     *
     * @param reader    index reader supplying term postings
     * @param lexicon   term lexicon aligned with the reader
     * @param rowIds    selected dense term ids
     * @param docFilter retained document dimensions
     * @param termStats corpus, term and document statistics
     * @param scorer    local term-document scorer
     * @return score matrix and compact-column document ids
     * @throws IOException if terms or postings cannot be read
     */
    private static TermDocMatrix termDocMatrix(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet docFilter,
        final TermStats termStats,
        final TermDocScorer scorer
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

        final double[][] rows = new double[rowIds.length][docCount];
        final Terms terms = MultiTerms.getTerms(reader, lexicon.field());
        final TermsEnum termsEnum = terms == null ? null : terms.iterator();
        final BytesRefBuilder termBytes = new BytesRefBuilder();
        final int[] docTokens = termStats.docTokens();
        PostingsEnum postings = null;

        for (int rowRank = 0; rowRank < rowIds.length; rowRank++) {
            final int termId = rowIds[rowRank];
            final double[] row = rows[rowRank];
            final TermDocScorer.Prepared prepared = scorer.prepare(
                termStats.fieldTokens(),
                termStats.fieldDocs(),
                termStats.termFreq(termId),
                termStats.termDocs(termId)
            );

            for (int docId = 0; docId < docFilter.length(); docId++) {
                if (docFilter.get(docId)) {
                    row[docRanks[docId]] = prepared.score(0, docTokens[docId]);
                }
            }

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
                    row[docRanks[docId]] = prepared.score(
                        postings.freq(),
                        docTokens[docId]
                    );
                }
            }
        }
        return new TermDocMatrix(rows, docIds);
    }

    /**
     * Retains every live document containing at least one selected term.
     *
     * <p>
     * Unlike binary-character filtering, this score-vector filter retains
     * documents containing every selected term because their frequencies and
     * BM25 values may still discriminate the rows.
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
     * Builds centred principal coordinates from the full BM25 score matrix.
     *
     * @param reader index reader supplying postings
     * @param lexicon selected-term lexicon
     * @param rowIds selected dense term ids
     * @param docFilter retained document columns
     * @param termStats corpus and term statistics
     * @param pars resolved HTTP parameters
     * @param meta response metadata collector
     * @return score matrix, decomposition, and projected layout
     * @throws IOException if postings cannot be read
     */
    private static Bm25Analysis bm25Analysis(
        final IndexReader reader,
        final TermLexicon lexicon,
        final int[] rowIds,
        final FixedBitSet docFilter,
        final TermStats termStats,
        final HttpPars pars,
        final MetaUtil meta
    ) throws IOException {
        final TermDocScorer scorer = new TermDocScorer.BM25();
        final TermDocMatrix matrix = termDocMatrix(
            reader,
            lexicon,
            rowIds,
            docFilter,
            termStats,
            scorer
        );
        final ContingencySvd model = ContingencySvd.fromScores(matrix.scores());
        model.centerColumns();
        model.decompose();

        final double weightAxes = pars.getDouble("weight-axes", 1d);
        if (weightAxes > 0d) {
            model.weightAxes(weightAxes);
        }
        final int dims = pars.getInt("dims", new int[] { 2, 50 }, 6);
        final SvdLayout map = model.project(dims);

        meta.put("documents", docFilter.cardinality());
        meta.put("score", scorer.toString());
        meta.put("svdCentered", true);
        meta.put("svdRank", map.inertia().length);
        return new Bm25Analysis(matrix, model, map);
    }

    /**
     * Returns Lucene documents matched by the focus query and optional filter.
     *
     * @param index Lucene index and searcher
     * @param pars resolved HTTP parameters
     * @return global document-id bitset
     * @throws IOException if query construction or collection fails
     */
    private static FixedBitSet focusDocs(
        final LuceneIndex index,
        final HttpPars pars
    ) throws IOException {
        final FixedBitSet docs = new FixedBitSet(index.reader().maxDoc());
        final SpanQuery span = spanQuery(index, pars);
        if (span == null) {
            return docs;
        }
        final Query filter = filterQuery(index, pars);
        final Query query;
        if (filter == null) {
            query = span;
        }
        else {
            query = new BooleanQuery.Builder()
                .add(span, Occur.MUST)
                .add(filter, Occur.FILTER)
                .build();
        }

        index.searcher().search(query, new SimpleCollector() {
            /** Current leaf's global document base. */
            private int docBase;

            @Override
            public void collect(final int localDocId)
            {
                docs.set(docBase + localDocId);
            }

            @Override
            protected void doSetNextReader(final LeafReaderContext context)
            {
                docBase = context.docBase;
            }

            @Override
            public ScoreMode scoreMode()
            {
                return ScoreMode.COMPLETE_NO_SCORES;
            }
        });
        return docs;
    }

    /**
     * Returns Lucene-compatible BM25 inverse document frequency.
     */
    private static double bm25Idf(final int corpusDocs, final int termDocs)
    {
        if (corpusDocs <= 0 || termDocs <= 0) {
            return 0d;
        }
        return Math.log(
            1d + (corpusDocs - termDocs + 0.5d) / (termDocs + 0.5d)
        );
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
     * Returns Euclidean distance between equal-length vectors.
     */
    private static double euclidean(final double[] a, final double[] b)
    {
        double squared = 0d;
        for (int axis = 0; axis < a.length; axis++) {
            final double delta = a[axis] - b[axis];
            squared += delta * delta;
        }
        return Math.sqrt(squared);
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

    /**
     * Returns term ranks ordered by increasing full-embedding distance.
     */
    private static int[] nearest(
        final double[][] embedding,
        final int row,
        final int count
    ) {
        final Integer[] ranks = new Integer[embedding.length - 1];
        int cursor = 0;
        for (int other = 0; other < embedding.length; other++) {
            if (other != row) {
                ranks[cursor++] = other;
            }
        }
        Arrays.sort(
            ranks,
            Comparator.comparingDouble(other -> euclidean(embedding[row], embedding[other]))
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
     * Returns the share of one row's full embedding represented by leading axes.
     */
    private static double represented(
        final double[] embedding,
        final int axes
    ) {
        double total = 0d;
        double emitted = 0d;
        for (int axis = 0; axis < embedding.length; axis++) {
            final double squared = embedding[axis] * embedding[axis];
            total += squared;
            if (axis < axes) {
                emitted += squared;
            }
        }
        return total > 0d ? emitted / total : 0d;
    }

    /**
     * Returns document ranks ordered by one right-singular-vector loading.
     */
    private static int[] topLoadingRanks(
        final double[][] rightVectors,
        final int axis,
        final int count,
        final boolean positive
    ) {
        final Integer[] ranks = new Integer[rightVectors.length];
        for (int rank = 0; rank < ranks.length; rank++) {
            ranks[rank] = rank;
        }
        Arrays.sort(ranks, (a, b) -> positive
            ? Double.compare(rightVectors[b][axis], rightVectors[a][axis])
            : Double.compare(rightVectors[a][axis], rightVectors[b][axis]));

        int size = 0;
        while (size < ranks.length && size < count) {
            final double loading = rightVectors[ranks[size]][axis];
            if ((positive && loading <= 0d) || (!positive && loading >= 0d)) {
                break;
            }
            size++;
        }
        final int[] top = new int[size];
        for (int rank = 0; rank < size; rank++) {
            top[rank] = ranks[rank];
        }
        return top;
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

    /**
     * Writes one document and optional axis loading.
     */
    private static void writeDocument(
        final JsonWriter jw,
        final StoredFields storedFields,
        final int docId,
        final int docTokens,
        final boolean focus,
        final String titleField,
        final String yearField,
        final Integer rank,
        final Double loading
    ) throws IOException {
        final Document document = storedFields.document(docId);
        jw.beginObject();
        if (rank != null) {
            jw.name("rank").value(rank);
        }
        jw.name("id").value(docId);
        jw.name("tokens").value(docTokens);
        jw.name("focus").value(focus);
        final String title = storedText(document, titleField);
        if (title != null) {
            jw.name("title").value(title);
        }
        final String year = storedText(document, yearField);
        if (year != null) {
            jw.name("year").value(year);
        }
        if (loading != null) {
            jw.name("loading").value(round(loading, 6));
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
        final FixedBitSet featDocs = filterDocs(liveDocs, docPresence);

        // Distances and exported characters must use exactly the same columns.
        for (final FixedBitSet row : docPresence) {
            row.and(featDocs);
        }

        final Distance distance = pars.getEnum("distance", Distance.BM25);
        final double[][] distances = switch (distance) {
            case BM25 -> cosineDistances(termDocMatrix(
                index.reader(),
                lexicon,
                rowIds,
                featDocs,
                termStats,
                new TermDocScorer.BM25()
            ).scores());
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

    /**
     * Writes decomposition-axis inertia metadata.
     */
    private static void writeAxes(
        final JsonWriter jw,
        final SvdLayout map
    ) throws IOException {
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

        jw.name("axes");
        jw.beginObject();
        jw.name("dims").value(dims);
        jw.name("dim1_pct").value(round(dim1, 1));
        jw.name("dim2_pct").value(round(dim2, 1));
        jw.name("cum2_pct").value(round(dim1 + dim2, 1));
        jw.name("emitted_pct").value(round(emittedInertia, 1));
        jw.name("spectrum");
        jw.beginArray();
        for (final double percent : spectrum) {
            jw.value(round(percent, 1));
        }
        jw.endArray();
        jw.endObject();
    }

    /**
     * Writes the complete BM25 matrix with term and document metadata.
     */
    private static void writeMatrixData(
        final JsonWriter jw,
        final IndexReader reader,
        final HttpServletRequest request,
        final Bm25Analysis analysis,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon,
        final TermStats termStats,
        final FixedBitSet[] presence,
        final FixedBitSet focusDocs
    ) throws IOException {
        final String titleField = parameter(request, "ftitle", "title");
        final String yearField = parameter(request, "fyear", "year");
        final StoredFields storedFields = reader.storedFields();
        final int[] docTokens = termStats.docTokens();
        final TermDocMatrix matrix = analysis.matrix();

        jw.name("data");
        jw.beginObject();
        jw.name("kind").value("bm25-matrix");
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
                focusDocs.get(docId),
                titleField,
                yearField,
                docRank,
                null
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
            jw.name("focusDf").value(
                FixedBitSet.intersectionCount(presence[row], focusDocs)
            );
            jw.name("idf").value(round(bm25Idf(termStats.fieldDocs(), corpusDf), 6));
            jw.name("scores");
            jw.beginArray();
            for (final double score : matrix.scores()[row]) {
                jw.value(score);
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
    }

    /**
     * Writes the BM25 map, optionally with term and document diagnostics.
     */
    private static void writeMapData(
        final JsonWriter jw,
        final IndexReader reader,
        final HttpServletRequest request,
        final HttpPars pars,
        final Bm25Analysis analysis,
        final int[] rowIds,
        final long[] rowFreq,
        final TermLexicon lexicon,
        final TermStats termStats,
        final FixedBitSet[] presence,
        final FixedBitSet focusDocs,
        final boolean diagnostic
    ) throws IOException {
        final SvdLayout map = analysis.layout();
        final double[][] embedding = analysis.model().embedding();
        final double[][] centered = analysis.model().residuals();
        final double[][] scores = analysis.matrix().scores();
        final int nearestCount = pars.getInt("nearest", new int[] { 1, 20 }, 5);

        jw.name("data");
        jw.beginObject();
        writeAxes(jw, map);

        jw.name("nodes");
        jw.beginArray();
        for (int node = 0; node < map.size(); node++) {
            final int termId = rowIds[node];
            final double[] coords = map.coords()[node];
            final double x = coords.length > 0 ? coords[0] : 0d;
            final double y = coords.length > 1 ? coords[1] : 0d;

            jw.beginObject();
            jw.name("id").value(termId);
            jw.name("form").value(lexicon.form(termId));
            jw.name("freq").value(rowFreq[node]);
            jw.name("x").value(round(x, 4));
            jw.name("y").value(round(y, 4));
            jw.name("cos2").value(round(map.cos2()[node], 4));
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
                jw.name("focusDf").value(
                    FixedBitSet.intersectionCount(presence[node], focusDocs)
                );
                jw.name("idf").value(round(
                    bm25Idf(termStats.fieldDocs(), corpusDf),
                    6
                ));
                jw.name("nonzeroDocs").value(nonZero(scores[node]));
                jw.name("uncenteredNorm").value(round(l2(scores[node]), 6));
                jw.name("centeredNorm").value(round(l2(centered[node]), 6));
                jw.name("top1Energy").value(round(energyShare(scores[node], 1), 6));
                jw.name("top5Energy").value(round(energyShare(scores[node], 5), 6));
                jw.name("top10Energy").value(round(energyShare(scores[node], 10), 6));
                jw.name("cos6").value(round(represented(embedding[node], 6), 4));

                jw.name("nearest");
                jw.beginArray();
                for (final int other : nearest(embedding, node, nearestCount)) {
                    jw.beginObject();
                    jw.name("id").value(rowIds[other]);
                    jw.name("form").value(lexicon.form(rowIds[other]));
                    jw.name("distance").value(round(
                        euclidean(embedding[node], embedding[other]),
                        6
                    ));
                    jw.endObject();
                }
                jw.endArray();
            }
            jw.endObject();
        }
        jw.endArray();

        if (diagnostic) {
            final String titleField = parameter(request, "ftitle", "title");
            final String yearField = parameter(request, "fyear", "year");
            final StoredFields storedFields = reader.storedFields();
            final int[] docTokens = termStats.docTokens();
            final int[] docIds = analysis.matrix().docIds();
            final double[][] right = analysis.model().rightVectors();
            final int axisCount = map.coords().length == 0
                ? 0
                : map.coords()[0].length;
            final int topDocs = pars.getInt("topdocs", new int[] { 1, 50 }, 10);

            jw.name("axisDocuments");
            jw.beginArray();
            for (int axis = 0; axis < axisCount; axis++) {
                jw.beginObject();
                jw.name("axis").value(axis + 1);

                jw.name("positive");
                jw.beginArray();
                for (final int docRank : topLoadingRanks(right, axis, topDocs, true)) {
                    final int docId = docIds[docRank];
                    writeDocument(
                        jw,
                        storedFields,
                        docId,
                        docTokens[docId],
                        focusDocs.get(docId),
                        titleField,
                        yearField,
                        docRank,
                        right[docRank][axis]
                    );
                }
                jw.endArray();

                jw.name("negative");
                jw.beginArray();
                for (final int docRank : topLoadingRanks(right, axis, topDocs, false)) {
                    final int docId = docIds[docRank];
                    writeDocument(
                        jw,
                        storedFields,
                        docId,
                        docTokens[docId],
                        focusDocs.get(docId),
                        titleField,
                        yearField,
                        docRank,
                        right[docRank][axis]
                    );
                }
                jw.endArray();
                jw.endObject();
            }
            jw.endArray();
        }
        jw.endObject();
    }

    /**
     * Writes a centred BM25 term-by-document SVD map as JSON.
     *
     * <p>
     * The response shape matches the co-occurrence semantic-map endpoint:
     * axes contain inertia diagnostics and nodes contain the first two display
     * coordinates together with every requested emitted coordinate. Parameter
     * {@code view=diagnostic} adds term and axis explanations;
     * {@code view=matrix} returns the complete BM25 matrix and document-column
     * metadata. The default is {@code view=map}.
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
            pars.getInt("topdocs", new int[] { 1, 50 }, 10);
        }
        final TopTerms topTerms = OpTerms.topTerms(index, pars, meta);

        int[] rowIds = null;
        long[] rowFreq = null;
        TermLexicon lexicon = null;
        TermStats termStats = null;
        FixedBitSet[] presence = null;
        FixedBitSet focusDocs = null;
        Bm25Analysis analysis = null;

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
            presence = docPresence(
                index.reader(),
                lexicon,
                rowIds,
                liveDocs
            );
            final FixedBitSet documents = unionDocs(liveDocs, presence);
            focusDocs = focusDocs(index, pars);
            analysis = bm25Analysis(
                index.reader(),
                lexicon,
                rowIds,
                documents,
                termStats,
                pars,
                meta
            );
            meta.put("diagnosticFocusDocs", focusDocs.cardinality());
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
                        index.reader(),
                        request,
                        pars,
                        analysis,
                        rowIds,
                        rowFreq,
                        lexicon,
                        termStats,
                        presence,
                        focusDocs,
                        true
                    );
                    case MAP -> writeMapData(
                        jw,
                        index.reader(),
                        request,
                        pars,
                        analysis,
                        rowIds,
                        rowFreq,
                        lexicon,
                        termStats,
                        presence,
                        focusDocs,
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
                        termStats,
                        presence,
                        focusDocs
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
