package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.IOUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Objects;
import java.util.Set;

import static com.github.oeuvres.alix.common.Names.ALIX_ID;

/**
 * Immutable reference statistics for one indexed field of one frozen Lucene directory.
 * <p>
 * The object stores per-document and per-term aggregate statistics aligned with the dense
 * {@code termId} order used by {@link TermLexicon} for the same field and snapshot.
 * </p>
 *
 * <h2>Stored per-document statistics</h2>
 * <ul>
 * <li><b>{@code docWidths[docId]}</b>: max position + 1 in the field for that document
 * (0 for documents without the field)</li>
 * <li><b>{@code docTokens[docId]}</b>: sum of term frequencies in the field for that document
 * (0 for documents without the field)</li>
 * </ul>
 * <p>
 * For fields where stop words are stripped at indexing time,
 * {@code docTokens[d] ≤ docWidths[d]}: positions are preserved across stripped tokens,
 * so the width exceeds the indexed-token count. BM25 and other density-based scorers
 * should use {@code docTokens}, not {@code docWidths}. Passage rendering and KWIC use widths.
 * </p>
 *
 * <h2>Stored per-term statistics</h2>
 * <ul>
 * <li><b>{@code termDocs[termId]}</b>: number of documents that contain the term</li>
 * <li><b>{@code termCounts[termId]}</b>: total occurrences of the term in the field</li>
 * </ul>
 *
 * <h2>Derived scalars</h2>
 * <p>
 * Computed in {@link #open} from the per-document arrays, therefore guaranteed consistent
 * with them:
 * </p>
 * <ul>
 * <li><b>{@code width}</b>: {@code Σ docWidths[d]}</li>
 * <li><b>{@code tokens}</b>: {@code Σ docTokens[d]}</li>
 * <li><b>{@code docs}</b>: count of {@code d} with {@code docTokens[d] > 0}</li>
 * </ul>
 *
 * <h2>Invariants (validated at open)</h2>
 * <ul>
 * <li>{@code docTokens[d] ≤ docWidths[d]} for every document. Violations are reported via
 * {@link Report#warn} but the file still loads.</li>
 * <li>{@code maxDoc} must match {@link IndexReader#maxDoc()} of the reader.</li>
 * </ul>
 *
 * <h2>Persistence format</h2>
 * <p>
 * Two UTF-8 TSV files in the Lucene directory, with header rows:
 * </p>
 * <ul>
 * <li>{@code <field>-terms.tsv}: {@code termId, term, df, tf} (term IDs start at 1).</li>
 * <li>{@code <field>-docs.tsv}: {@code docId, alix.id, docWidth, docTokens}.
 * All {@code docId} slots are present, including deleted documents.</li>
 * </ul>
 * <p>
 * Text cells use CSV-compatible quoting, with tab as the separator. Term ID 0
 * is the absent-term sentinel and is not written. Both files are specific to the
 * same frozen Lucene snapshot; their row identities are checked at open.
 * </p>
 *
 * <h2>Order compatibility</h2>
 * <p>
 * Per-term arrays follow the merged lexicographic {@link TermsEnum} order of the field,
 * matching the dense term ids assigned by {@link TermLexicon} from the same snapshot.
 * Per-document arrays are indexed by global {@code docId} in {@code [0, maxDoc)}.
 * </p>
 *
 * <h2>Performance model</h2>
 * <p>
 * {@link #open} loads all arrays into primitive heap arrays. Repeated per-document and
 * per-term lookups are faster on primitives than on memory-mapped buffers.
 * </p>
 *
 * <h2>Preconditions</h2>
 * <ul>
 * <li>The field must exist and have positions (needed for {@code docWidths}).</li>
 * <li>The field must store term frequencies (needed for {@code docTokens} and per-term stats).</li>
 * </ul>
 */
public final class TermStats
{
    /** Lucene directory that contains the index and the TSV files. */
    private final Path sideDir;
    
    /** Indexed field covered by these statistics. */
    private final String field;
    
    /** Lucene document-address space size for this frozen reader snapshot. */
    private final int maxDoc;
    
    /** Per-document field width (max position + 1), indexed by global doc id; 0 for docs without the field. */
    private final int[] docWidths;
    
    /** Width of the biggest document, useful to prepare a reusable vector by doc. */
    private final int maxWidth;
    
    /** Per-document token count, indexed by global doc id; 0 for docs without the field. */
    private final int[] docTokens;
    
    /** Number of distinct terms in the field (id 0 is the absent-term sentinel). */
    final int vocabSize;
    
    /** Per-term document frequencies, indexed by dense term id. */
    private final int[] termDocs;
    
    /** Per-term total occurrences in the field, indexed by dense term id. */
    protected final long[] termFreq;
    
    /** Number of documents that contain at least one indexed token of the field. Derived. */
    private final int fieldDocs;
    
    /** Sum of all {@code docWidths}: total position count across the field. Derived. */
    private final long fieldWidth;
    
    /** Sum of all {@code docTokens}: total indexed-token count across the field. Derived. */
    private final long fieldTokens;
    
    /**
     * Corpus-level term weight vector, indexed by dense term id.
     * {@code null} until {@link #termWeights} has been called.
     * Written once and then read-only; declared volatile for safe publication.
     */
    private volatile double[] termWeights;
    
    /** The scorer used to calculate {@link #termWeights}. */
    private volatile ThemeTerms termWeightsScorer;
    
    /**
     * Creates an immutable statistics object from already-loaded arrays and derived scalars.
     *
     * @param sideDir    Lucene directory that contains the index and the TSV files
     * @param field      indexed field
     * @param maxDoc     Lucene document-address space size
     * @param docWidths  per-document widths by global doc id
     * @param maxWidth   largest document width
     * @param docTokens  per-document token counts by global doc id
     * @param vocabSize  number of distinct terms (including id 0 sentinel)
     * @param termDocs   per-term document frequencies
     * @param termCounts per-term total occurrences
     * @param docs       derived: count of documents with at least one token
     * @param width      derived: sum of docWidths
     * @param tokens     derived: sum of docTokens
     */
    private TermStats(
            final Path sideDir,
            final String field,
            final int maxDoc,
            final int[] docWidths,
            final int maxWidth,
            final int[] docTokens,
            final int vocabSize,
            final int[] termDocs,
            final long[] termCounts,
            final int docs,
            final long width,
            final long tokens)
    {
        this.sideDir = sideDir;
        this.field = field;
        this.maxDoc = maxDoc;
        this.docWidths = docWidths;
        this.maxWidth = maxWidth;
        this.docTokens = docTokens;
        this.vocabSize = vocabSize;
        this.termDocs = termDocs;
        this.termFreq = termCounts;
        this.fieldDocs = docs;
        this.fieldWidth = width;
        this.fieldTokens = tokens;
    }
    
    /**
     * Approximate heap footprint of the numeric arrays only.
     * Excludes object headers, references, field string and JVM-specific overheads.
     *
     * @return approximate bytes used by per-doc and per-term arrays
     */
    public long arraysBytes()
    {
        return (long) docWidths.length * Integer.BYTES
                + (long) docTokens.length * Integer.BYTES
                + (long) termDocs.length * Integer.BYTES
                + (long) termFreq.length * Long.BYTES;
    }
    
    /**
     * Builds the two TSV statistics files for one indexed field.
     * <p>
     * Width and token counts are computed in one postings pass; document frequencies
     * and term totals are computed in a second pass. Existing TSV files are not replaced.
     * Each temporary file is moved into place after both have been written; if either
     * move fails, files moved by this invocation are removed.
     * </p>
     *
     * @param reader snapshot reader
     * @param sideDir directory that will receive the TSV files
     * @param field indexed field name
     * @param report progress reporter; may be {@code null}
     * @throws IOException if terms or positions are unavailable, either output already
     *                     exists, or a file cannot be written
     */
    public static void build(final IndexReader reader, final Path sideDir, final String field, Report report)
        throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;

        final Path docsPath = docsPath(sideDir, field);
        final Path termsPath = termsPath(sideDir, field);
        IOUtil.ensureAbsent(docsPath);
        IOUtil.ensureAbsent(termsPath);
        final Path docsTmp = IOUtil.tmpPath(docsPath);
        final Path termsTmp = IOUtil.tmpPath(termsPath);
        IOUtil.ensureAbsent(docsTmp);
        IOUtil.ensureAbsent(termsTmp);

        final DocStats docs = docStats(reader, field, report);
        final VocabCounts counts = vocabCounts(reader, field, report);
        boolean docsMoved = false;
        try {
            try (BufferedWriter out = Files.newBufferedWriter(
                    docsTmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                out.write("docId\talix.id\tdocWidth\tdocTokens\n");
                final StoredFields stored = reader.storedFields();
                final BitSet live = liveDocs(reader);
                int missingIds = 0;
                for (int docId = 0; docId < reader.maxDoc(); docId++) {
                    final String id = live.get(docId) ? documentId(stored, docId) : "";
                    if (live.get(docId) && id.isEmpty())
                        missingIds++;
                    out.write(Integer.toString(docId));
                    out.write('\t');
                    writeTsvCell(out, id);
                    out.write('\t');
                    out.write(Integer.toString(docs.docWidths[docId]));
                    out.write('\t');
                    out.write(Integer.toString(docs.docTokens[docId]));
                    out.write('\n');
                }
                if (missingIds > 0)
                    report.warn("field=\"" + field + "\": " + missingIds
                            + " live documents without stored alix.id");
            }

            try (BufferedWriter out = Files.newBufferedWriter(
                    termsTmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                out.write("termId\tterm\tdf\ttf\n");
                final Terms terms = MultiTerms.getTerms(reader, field);
                final TermsEnum iterator = terms.iterator();
                BytesRef term;
                int termId = 1;
                while ((term = iterator.next()) != null) {
                    if (termId >= counts.vocabSize)
                        throw new IOException("Vocabulary changed while writing TSV for field '" + field + "'");
                    out.write(Integer.toString(termId));
                    out.write('\t');
                    writeTsvCell(out, term.utf8ToString());
                    out.write('\t');
                    out.write(Integer.toString(counts.termDocs[termId]));
                    out.write('\t');
                    out.write(Long.toString(counts.termCounts[termId]));
                    out.write('\n');
                    termId++;
                }
                if (termId != counts.vocabSize)
                    throw new IOException("Vocabulary changed while writing TSV for field '" + field + "'");
            }

            IOUtil.moveTemp(docsTmp, docsPath);
            docsMoved = true;
            IOUtil.moveTemp(termsTmp, termsPath);
        } catch (IOException | RuntimeException e) {
            IOUtil.deleteIfExists(docsTmp);
            IOUtil.deleteIfExists(termsTmp);
            if (docsMoved)
                IOUtil.deleteIfExists(docsPath);
            throw e;
        }
    }

    /**
     * Computes a corpus-level weight for every vocabulary term.
     *
     * <p>
     * Uses {@link #docTokens} as the per-document length denominator so density-based
     * scorers (BM25) see indexed-token counts, not positions. {@code docWidths} would
     * distort length normalisation in corpora that strip stop words.
     * </p>
     *
     * <p>
     * Term-id assignment follows the merged {@link MultiTerms} lexicographic order used
     * by {@link TermLexicon}. Id 0 is reserved as the absent-term sentinel.
     * </p>
     *
     * <p>
     * When {@code focusDocs} is non-null, the focus subset's token total and document
     * count are derived from the bitset and {@link #docTokens}, then passed to
     * {@link ThemeTerms#focus}. Each posting visit then tells the scorer whether the
     * document is in focus, and the scorer accumulates into focus or rest separately.
     * </p>
     *
     * @param reader    snapshot reader matching these statistics
     * @param scorer    scorer to weight terms
     * @param focusDocs optional focus bitset for contrastive scoring; {@code null} for non-contrastive
     * @return weight vector indexed by dense term id
     * @throws IOException           if term or postings iteration fails
     * @throws IllegalStateException if the field has no terms or no frequencies
     */
    public double[] buildTermWeights(final IndexReader reader, final ThemeTerms scorer)
        throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(scorer, "scorer");
        
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalStateException(
                    "Field '" + field + "' has no terms; cannot build term weights");
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                    "Field '" + field + "' was not indexed with term frequencies");
        }
        
        scorer.corpus(fieldTokens, fieldDocs);
        final double[] weights = new double[vocabSize];
        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        
        while (tenum.next() != null) {
            if (termId >= vocabSize) {
                throw new IOException(
                        "Vocabulary size changed during buildTermWeights for field '" + field
                                + "': seen more than " + vocabSize + " terms");
            }
            scorer.termStart(termFreq[termId], termDocs[termId]);
            
            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings.nextDoc()) {
                final int freq = postings.freq();
                if (freq <= 0)
                    continue;
                scorer.termDocAdd(freq, docTokens[docId]);
            }
            
            weights[termId] = scorer.termScore();
            termId++;
        }
        return weights;
    }
    
    /**
     * Computes per-document widths and token counts in a single pass over the postings.
     * <p>
     * For each live document: {@code docWidths[d]} is the maximum position + 1,
     * {@code docTokens[d]} is the sum of {@code postings.freq()} across all terms of the field.
     * </p>
     *
     * @param reader snapshot reader
     * @param field  indexed field name
     * @param report progress reporter; may be {@code null}
     * @return per-document statistics
     * @throws IOException              if postings traversal fails
     * @throws IllegalArgumentException if the field has no positions or no frequencies
     */
    public static DocStats docStats(final IndexReader reader, final String field, Report report)
        throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("field is null or empty");
        }
        if (report == null)
            report = Report.ReportNull.INSTANCE;
        report.setAttribute("field", field);
        
        final int maxDoc = reader.maxDoc();
        final int[] docWidths = new int[maxDoc];
        final int[] docTokens = new int[maxDoc];
        
        int leafCount = 0;
        int leafWithFieldCount = 0;
        int docsWithTokens = 0;
        int maxWidth = 0;
        int deletedPostingsSkipped = 0;
        int weirdPositionCount = 0;
        long totalTokens = 0L;
        
        for (LeafReaderContext ctx : reader.leaves()) {
            leafCount++;
            final LeafReader leaf = ctx.reader();
            final Terms terms = leaf.terms(field);
            if (terms == null) {
                report.debug("field \"" + field + "\" absent in leaf at docBase=" + ctx.docBase);
                continue;
            }
            leafWithFieldCount++;
            if (!terms.hasPositions()) {
                throw new IllegalArgumentException(
                        "field \"" + field + "\" has no positions in leaf at docBase=" + ctx.docBase);
            }
            if (!terms.hasFreqs()) {
                throw new IllegalArgumentException(
                        "field \"" + field + "\" has no frequencies in leaf at docBase=" + ctx.docBase);
            }
            
            final Bits liveDocs = leaf.getLiveDocs();
            final TermsEnum tenum = terms.iterator();
            PostingsEnum pe = null;
            BytesRef term;
            
            while ((term = tenum.next()) != null) {
                pe = tenum.postings(pe, PostingsEnum.POSITIONS);
                for (int localDocId = pe.nextDoc(); localDocId != PostingsEnum.NO_MORE_DOCS; localDocId = pe
                        .nextDoc())
                {
                    if (liveDocs != null && !liveDocs.get(localDocId)) {
                        deletedPostingsSkipped++;
                        continue;
                    }
                    final int globalDocId = ctx.docBase + localDocId;
                    final int freq = pe.freq();
                    
                    int docMaxPos = -1;
                    int prevPos = -1;
                    int validPositions = 0;
                    for (int i = 0; i < freq; i++) {
                        final int pos = pe.nextPosition();
                        if (pos < 0) {
                            weirdPositionCount++;
                            report.warn("negative position on field \"" + field
                                    + "\", docId=" + globalDocId
                                    + ", term=" + term.utf8ToString()
                                    + ", pos=" + pos);
                            continue;
                        }
                        if (prevPos > pos) {
                            weirdPositionCount++;
                            report.warn("non-monotone positions on field \"" + field
                                    + "\", docId=" + globalDocId
                                    + ", term=" + term.utf8ToString()
                                    + ", prevPos=" + prevPos + ", pos=" + pos);
                        }
                        prevPos = pos;
                        if (pos > docMaxPos)
                            docMaxPos = pos;
                        validPositions++;
                    }
                    
                    if (validPositions > 0) {
                        if (docTokens[globalDocId] == 0)
                            docsWithTokens++;
                        docTokens[globalDocId] += validPositions;
                        totalTokens += validPositions;
                        
                        final int newWidth = docMaxPos + 1;
                        if (newWidth > docWidths[globalDocId]) {
                            docWidths[globalDocId] = newWidth;
                            if (newWidth > maxWidth)
                                maxWidth = newWidth;
                        }
                    }
                }
            }
        }
        
        report.info(
                "field=\"" + field + "\""
                        + ", leaves=" + leafCount
                        + ", leavesWithField=" + leafWithFieldCount
                        + ", docsWithTokens=" + docsWithTokens
                        + ", totalTokens=" + totalTokens
                        + ", maxWidth=" + maxWidth
                        + ", deletedPostingsSkipped=" + deletedPostingsSkipped
                        + ", weirdPositions=" + weirdPositionCount);
        
        return new DocStats(docWidths, docTokens);
    }
    
    /**
     * Returns the token count for one global Lucene document id.
     * Returns {@code 0} for documents that do not contain the field.
     *
     * @param docId global Lucene document id in {@code [0, maxDoc)}
     * @return number of indexed tokens in the field for that document, or {@code 0}
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docTokens(final int docId)
    {
        checkDocId(docId);
        return docTokens[docId];
    }
    
    /**
     * Returns a direct reference to the internal {@code docTokens} array.
     * Intended for hot loops (e.g. BM25 scoring) that cannot afford bounds checks.
     * Callers must not modify the returned array.
     *
     * @return reference to {@code docTokens}, indexed by global doc id
     */
    public int[] docTokens()
    {
        return docTokens;
    }
    
    /**
     * Returns the field width (max position + 1) for one global Lucene document id.
     * Returns {@code 0} for documents that do not contain the field.
     *
     * @param docId global Lucene document id in {@code [0, maxDoc)}
     * @return number of positions in the field for that document, or {@code 0}
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docWidth(final int docId)
    {
        checkDocId(docId);
        return docWidths[docId];
    }
    
    /**
     * Returns the internal per-document field-width array, indexed by global Lucene docId.
     * Widths include gaps left by stopwords removed during indexing.
     * Callers must not modify the returned array.
     *
     * @return the per-document width array
     */
    public int[] docWidths()
    {
        return docWidths;
    }
    
    /**
     * Recomputes document widths directly from indexed positions, for TermRail.
     *
     * @param reader snapshot reader
     * @param field indexed field name
     * @param report progress reporter; may be {@code null}
     * @return document widths by global Lucene document ID
     * @throws IOException if reading postings or positions fails
     */
    public static int[] docWidths(IndexReader reader, String field, Report report) throws IOException
    {
        return docStats(reader, field, report).docWidths();
    }
    
    /**
     * Returns {@code true} if both persisted TSV files for the field exist.
     * This is a presence check, not content validation.
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code true} if both TSV files exist
     */
    public static boolean exists(final Path indexDir, final String field)
    {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(docsPath(indexDir, field))
                && Files.isRegularFile(termsPath(indexDir, field));
    }

    /**
     * Returns the indexed field covered by these statistics.
     *
     * @return field name
     */
    public String field()
    {
        return field;
    }
    
    /**
     * Returns the number of documents that contain at least one indexed token of the field.
     *
     * @return document count
     */
    public int fieldDocs()
    {
        return fieldDocs;
    }
    
    /**
     * Returns the total indexed-token count across the field.
     * Derived at open as {@code Σ docTokens[d]}. This is the denominator
     * to use in density-based scoring such as BM25.
     *
     * @return total token count
     */
    public long fieldTokens()
    {
        return fieldTokens;
    }
    
    /**
     * Returns the total position count across the field (including stripped positions).
     * Derived at open as {@code Σ docWidths[d]}.
     *
     * @return total position count
     */
    public long fieldWidth()
    {
        return fieldWidth;
    }
    
    /**
     * Builds a global live-doc bitset aligned on top-level docIds.
     *
     * @param reader index reader
     * @return bitset of live doc ids
     * @throws IOException if leaf access fails
     */
    public static BitSet liveDocs(final IndexReader reader) throws IOException
    {
        final int maxDoc = reader.maxDoc();
        final BitSet live = new BitSet(maxDoc);
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            final Bits bits = leaf.getLiveDocs();
            final int leafMax = leaf.maxDoc();
            final int base = ctx.docBase;
            if (bits == null) {
                live.set(base, base + leafMax);
            } else {
                for (int segDoc = 0; segDoc < leafMax; segDoc++) {
                    if (bits.get(segDoc)) {
                        live.set(base + segDoc);
                    }
                }
            }
        }
        return live;
    }
    
    /**
     * Returns the Lucene document-address space size for this frozen reader snapshot.
     * Valid global document ids are in {@code [0, maxDoc())}.
     *
     * @return reader maxDoc
     */
    public int maxDoc()
    {
        return maxDoc;
    }
    
    /**
     * From the biggest document, returns the width in positions.
     *
     * @return largest width in positions
     */
    public int maxWidth()
    {
        return maxWidth;
    }
    
    /**
     * Loads the two TSV files into primitive arrays for the current Lucene snapshot.
     * <p>
     * Document and term IDs must be consecutive, and their textual identifiers must
     * agree with the reader. The sums and largest document width are derived during
     * loading. A document with more indexed tokens than positions produces a warning.
     * </p>
     *
     * @param reader snapshot reader used for consistency checks
     * @param sideDir directory containing the TSV files
     * @param field indexed field name
     * @param report progress reporter; may be {@code null}
     * @return loaded field statistics
     * @throws IOException if a file is missing, malformed or inconsistent with the reader
     */
    public static TermStats open(
        final IndexReader reader,
        final Path sideDir,
        final String field,
        Report report) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;

        final Path docsPath = docsPath(sideDir, field);
        final Path termsPath = termsPath(sideDir, field);
        IOUtil.ensureRegularFile(docsPath);
        IOUtil.ensureRegularFile(termsPath);

        final int maxDoc = reader.maxDoc();
        final int[] docWidths = new int[maxDoc];
        final int[] docTokens = new int[maxDoc];
        long width = 0L;
        long tokens = 0L;
        int maxWidth = 0;
        int docs = 0;
        int invariantViolations = 0;

        try (CSVReader csv = new CSVReader(docsPath).separator('\t')) {
            checkHeader(csv, "docId", ALIX_ID, "docWidth", "docTokens");
            final StoredFields stored = reader.storedFields();
            final BitSet live = liveDocs(reader);
            for (int docId = 0; docId < maxDoc; docId++) {
                if (!csv.readRow())
                    throw new IOException("Missing docId=" + docId + " in " + docsPath);
                checkColumns(csv, 4);
                if (parseInt(csv, 0, "docId") != docId)
                    throw new IOException("Out-of-order docId at " + docsPath + ':' + csv.getLineNo());
                final String expectedId = live.get(docId) ? documentId(stored, docId) : "";
                if (!expectedId.contentEquals(csv.getCell(1)))
                    throw new IOException("alix.id mismatch for docId=" + docId + " in " + docsPath);
                final int docWidth = parseInt(csv, 2, "docWidth");
                final int docTokenCount = parseInt(csv, 3, "docTokens");
                docWidths[docId] = docWidth;
                docTokens[docId] = docTokenCount;
                width += docWidth;
                tokens += docTokenCount;
                maxWidth = Math.max(maxWidth, docWidth);
                if (docTokenCount > 0)
                    docs++;
                if (docTokenCount > docWidth)
                    invariantViolations++;
                if (!live.get(docId) && (docWidth != 0 || docTokenCount != 0))
                    throw new IOException("Nonzero statistics for deleted docId=" + docId + " in " + docsPath);
            }
            if (csv.readRow())
                throw new IOException("Extra document rows in " + docsPath + " at line " + csv.getLineNo());
        }
        if (invariantViolations > 0) {
            report.warn("field=\"" + field + "\": "
                    + invariantViolations + " documents with docTokens > docWidths");
        }

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null)
            throw new IOException("Field without terms: " + field);
        final int vocabSize = vocabSize(terms, report);
        final int[] termDocs = new int[vocabSize];
        final long[] termCounts = new long[vocabSize];

        try (CSVReader csv = new CSVReader(termsPath).separator('\t')) {
            checkHeader(csv, "termId", "term", "df", "tf");
            final TermsEnum iterator = terms.iterator();
            for (int termId = 1; termId < vocabSize; termId++) {
                if (!csv.readRow())
                    throw new IOException("Missing termId=" + termId + " in " + termsPath);
                checkColumns(csv, 4);
                if (parseInt(csv, 0, "termId") != termId)
                    throw new IOException("Out-of-order termId at " + termsPath + ':' + csv.getLineNo());
                final BytesRef indexedTerm = iterator.next();
                if (indexedTerm == null || !indexedTerm.utf8ToString().contentEquals(csv.getCell(1)))
                    throw new IOException("Term mismatch for termId=" + termId + " in " + termsPath);
                final int df = parseInt(csv, 2, "df");
                final long tf = parseLong(csv, 3, "tf");
                if (df > reader.numDocs())
                    throw new IOException("df exceeds reader.numDocs() for termId=" + termId);
                if (!reader.hasDeletions() && (df != iterator.docFreq() || tf != iterator.totalTermFreq()))
                    throw new IOException("Statistics mismatch for termId=" + termId + " in " + termsPath);
                termDocs[termId] = df;
                termCounts[termId] = tf;
            }
            if (iterator.next() != null || csv.readRow())
                throw new IOException("Extra terms in " + termsPath + " or vocabulary changed");
        }

        return new TermStats(
                sideDir, field, maxDoc, docWidths, maxWidth, docTokens,
                vocabSize, termDocs, termCounts, docs, width, tokens);
    }

    /**
     * Opens the field statistics, building both TSV files first if they do not exist.
     *
     * @param reader  snapshot reader used for building and consistency checks
     * @param sideDir directory that may receive the TSV files
     * @param field   indexed field name
     * @return opened immutable field statistics
     * @throws IOException if building or opening fails
     */
    public static TermStats openOrBuild(final IndexReader reader, final Path sideDir, final String field)
        throws IOException
    {
        return openOrBuild(reader, sideDir, field, null);
    }
    
    /**
     * Opens the field statistics, building both TSV files first if they do not exist.
     *
     * @param reader  snapshot reader
     * @param sideDir directory that may receive the TSV files
     * @param field   indexed field name
     * @param report  progress reporter; may be {@code null}
     * @return opened immutable field statistics
     * @throws IOException if building or opening fails
     */
    public static TermStats openOrBuild(
        final IndexReader reader,
        final Path sideDir,
        final String field,
        final Report report) throws IOException
    {
        if (!exists(sideDir, field)) {
            if (Files.exists(docsPath(sideDir, field)) || Files.exists(termsPath(sideDir, field)))
                throw new IOException("Incomplete TSV statistics for field '" + field + "' in " + sideDir);
            build(reader, sideDir, field, report);
        }
        return open(reader, sideDir, field, report);
    }
    
    /**
     * Returns the directory from which these statistics were opened.
     *
     * @return Lucene directory path
     */
    public Path sideDir()
    {
        return sideDir;
    }
    
    /**
     * Returns the document frequency of one term.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return number of documents that contain the term
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public int termDocs(final int termId)
    {
        checkTermId(termId);
        return termDocs[termId];
    }
    
    /**
     * Returns a direct reference to the internal {@code  #termDocs} array.
     * Callers must not modify the returned array.
     *
     * @return reference to {@link #termDocs}, indexed by dense term id
     */
    public int[] termDocsRef()
    {
        return termDocs;
    }
    
    /**
     * Returns the total number of occurrences of one term in the field.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return total occurrences in the field
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public long termFreq(final int termId)
    {
        checkTermId(termId);
        return termFreq[termId];
    }
    
    /**
     * Returns a direct reference to the internal {@code termCounts} array.
     * Callers must not modify the returned array.
     *
     * @return reference to {@code termCounts}, indexed by dense term id
     */
    public long[] termFreqRef()
    {
        return termFreq;
    }
    
    /**
     * Returns the cached corpus-level term weight for one term.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return cached weight, or {@code 0.0} for the absent-term sentinel (id 0)
     * @throws IllegalStateException    if {@link #termWeights(IndexReader, ThemeTerms)} has not been called
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public double termWeight(final int termId)
    {
        final double[] w = termWeights;
        if (w == null) {
            throw new IllegalStateException(
                    "termWeights not built for field '" + field
                            + "'; call termWeights(reader, scorer) first");
        }
        checkTermId(termId);
        return w[termId];
    }
    
    /**
     * Returns the cached term weight vector, computing it on first call or when the
     * scorer changes. Subsequent calls with an equivalent scorer return the same array.
     *
     * @param reader snapshot reader matching these statistics
     * @param scorer scorer used to weight terms
     * @return weight vector indexed by dense term id
     * @throws IOException if term iteration fails
     */
    public double[] termWeights(final IndexReader reader, final ThemeTerms scorer) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(scorer, "scorer");
        if (termWeights != null && scorer.equals(termWeightsScorer)) {
            return termWeights;
        }
        termWeightsScorer = scorer;
        termWeights = buildTermWeights(reader, scorer);
        return termWeights;
    }
    
    /**
     * Get reference to the last generated weights.
     * 
     * @return {@link #termWeights}
     */
    public double[] termWeightsRef()
    {
        return termWeights;
    }
    
    /**
     * Collects document frequencies and term totals in merged lexicographic order.
     * Deleted documents are excluded from the counts, consistently with {@link #docStats}.
     *
     * @param reader snapshot reader
     * @param field indexed field name
     * @param report progress reporter; may be {@code null}
     * @return counts indexed by dense term ID, with ID 0 reserved
     * @throws IOException if postings cannot be read or frequencies are unavailable
     * @throws IllegalArgumentException if the field has no terms
     */
    public static VocabCounts vocabCounts(final IndexReader reader, final String field, Report report)
        throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null)
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        if (!terms.hasFreqs())
            throw new IllegalArgumentException("Term frequencies unavailable for field: " + field);
        report.setAttribute("field", field);

        final int vocabSize = vocabSize(terms, report);
        final int[] termDocs = new int[vocabSize];
        final long[] termCounts = new long[vocabSize];
        final boolean hasDeletions = reader.hasDeletions();
        final BitSet live = hasDeletions ? liveDocs(reader) : null;
        final TermsEnum iterator = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        while (iterator.next() != null) {
            if (termId >= vocabSize)
                throw new IOException("Vocabulary changed while collecting counts for field '" + field + "'");

            int df;
            long tf;
            if (hasDeletions) {
                df = 0;
                tf = 0L;
                postings = iterator.postings(postings, PostingsEnum.FREQS);
                for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS;
                        docId = postings.nextDoc()) {
                    if (live.get(docId)) {
                        df++;
                        tf += postings.freq();
                    }
                }
            } else {
                df = iterator.docFreq();
                tf = iterator.totalTermFreq();
            }
            if (df < 0 || tf < 0)
                throw new IOException("Invalid frequencies for termId=" + termId + " in field '" + field + "'");
            termDocs[termId] = df;
            termCounts[termId] = tf;
            termId++;
        }
        if (termId != vocabSize)
            throw new IOException("Vocabulary changed while collecting counts for field '" + field + "'");
        return new VocabCounts(vocabSize, termDocs, termCounts);
    }

    /**
     * Returns the vocabulary size, including the id 0 absent-term sentinel.
     *
     * @return number of distinct terms + 1
     */
    public int vocabSize()
    {
        return vocabSize;
    }
    
    /**
     * Resolves the vocabulary size of a field.
     * Uses {@link Terms#size()} if available, otherwise counts by iteration.
     * The returned value includes the id 0 sentinel (so real terms start at 1).
     *
     * @param reader index reader
     * @param field  indexed field name
     * @param report progress reporter; may be {@code null}
     * @return vocabulary size including sentinel
     * @throws IOException if term iteration fails
     */
    public static int vocabSize(final IndexReader reader, final String field, Report report) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            report.warn("No terms for field=" + field);
            return 0;
        }
        report.setAttribute("field", field);
        return vocabSize(terms, report);
    }
    
    /**
     * Per-document statistics produced in one unified pass.
     * Both arrays are indexed by global doc id, length {@code reader.maxDoc()}.
     *
     * @param docWidths max position + 1 per document (0 for docs without the field)
     * @param docTokens sum of term frequencies per document (0 for docs without the field)
     */
    public record DocStats(int[] docWidths, int[] docTokens)
    {
    }
    
    /**
     * Per-term statistics, indexed by dense term id.
     *
     * @param vocabSize  number of distinct terms including id 0 sentinel
     * @param termDocs   document frequency per term
     * @param termCounts total occurrences per term
     */
    public record VocabCounts(int vocabSize, int[] termDocs, long[] termCounts)
    {
    }
    
    /**
     * Verifies the number of columns in the current TSV record.
     *
     * @param csv reader positioned on a record
     * @param count required column count
     * @throws IOException if the count differs
     */
    private static void checkColumns(final CSVReader csv, final int count) throws IOException
    {
        if (csv.getCellCount() != count)
            throw new IOException("Expected " + count + " columns in " + csv.getSpec()
                    + " at line " + csv.getLineNo() + ", got " + csv.getCellCount());
    }

    /**
     * Validates a global Lucene document id against {@code maxDoc}.
     *
     * @param docId global doc id
     * @throws IllegalArgumentException if out of range
     */
    private void checkDocId(final int docId)
    {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                    "docId out of range: " + docId + " (maxDoc=" + maxDoc + ')');
        }
    }
    
    /**
     * Verifies the header and column names of a TSV file.
     *
     * @param csv positioned before the first row
     * @param names expected column names, in order
     * @throws IOException if the header is missing or differs
     */
    private static void checkHeader(final CSVReader csv, final String... names) throws IOException
    {
        if (!csv.readRow())
            throw new IOException("Missing TSV header in " + csv.getSpec());
        checkColumns(csv, names.length);
        for (int i = 0; i < names.length; i++) {
            if (!names[i].contentEquals(csv.getCell(i)))
                throw new IOException("Invalid TSV header in " + csv.getSpec()
                        + ": column " + (i + 1) + " must be '" + names[i] + "'");
        }
    }

    /**
     * Validates a dense term id against {@code vocabSize}.
     *
     * @param termId dense term id
     * @throws IllegalArgumentException if out of range
     */
    private void checkTermId(final int termId)
    {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException(
                    "termId out of range: " + termId + " (vocabSize=" + vocabSize + ')');
        }
    }
    
    /**
     * Returns the per-document TSV path for an indexed field.
     *
     * @param sideDir directory containing index sidecars
     * @param field indexed field name
     * @return path to the document TSV
     */
    private static Path docsPath(final Path sideDir, final String field)
    {
        return sideDir.resolve(field + "-docs.tsv");
    }

    /**
     * Returns the stored document identifier, or an empty string if unavailable.
     *
     * @param stored index reader's stored fields
     * @param docId global Lucene document ID
     * @return the stored {@code alix.id}, or an empty string
     * @throws IOException if stored fields cannot be retrieved
     */
    private static String documentId(final StoredFields stored, final int docId) throws IOException
    {
        final String id = stored.document(docId, Set.of(ALIX_ID)).get(ALIX_ID);
        return id == null ? "" : id;
    }

    /**
     * Reads a nonnegative integer from a TSV cell.
     *
     * @param csv reader positioned on a record
     * @param column zero-based column
     * @param name value name used for diagnostics
     * @return parsed integer
     * @throws IOException if the value is invalid
     */
    private static int parseInt(final CSVReader csv, final int column, final String name) throws IOException
    {
        final StringBuilder value = csv.getCell(column);
        try {
            final int number = Integer.parseInt(value, 0, value.length(), 10);
            if (number >= 0)
                return number;
        } catch (NumberFormatException e) {
            // Fall through to the contextual error below.
        }
        throw new IOException("Invalid " + name + " in " + csv.getSpec() + " at line " + csv.getLineNo()
                + ": '" + value + "'");
    }

    /**
     * Reads a nonnegative long from a TSV cell.
     *
     * @param csv reader positioned on a record
     * @param column zero-based column
     * @param name value name used for diagnostics
     * @return parsed long
     * @throws IOException if the value is invalid
     */
    private static long parseLong(final CSVReader csv, final int column, final String name) throws IOException
    {
        final StringBuilder value = csv.getCell(column);
        try {
            final long number = Long.parseLong(value, 0, value.length(), 10);
            if (number >= 0)
                return number;
        } catch (NumberFormatException e) {
            // Fall through to the contextual error below.
        }
        throw new IOException("Invalid " + name + " in " + csv.getSpec() + " at line " + csv.getLineNo()
                + ": '" + value + "'");
    }

    /**
     * Returns the per-term TSV path for an indexed field.
     *
     * @param sideDir directory containing index sidecars
     * @param field indexed field name
     * @return path to the terms TSV
     */
    private static Path termsPath(final Path sideDir, final String field)
    {
        return sideDir.resolve(field + "-terms.tsv");
    }

    /**
     * Resolves vocabulary size directly from a {@link Terms} instance.
     *
     * @param terms  Lucene terms
     * @param report progress reporter
     * @return vocabulary size including sentinel
     * @throws IOException if term iteration fails
     */
    private static int vocabSize(final Terms terms, final Report report) throws IOException
    {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(report, "report");
        long size = terms.size();
        if (size >= Integer.MAX_VALUE) {
            throw new ArrayIndexOutOfBoundsException(
                    "vocabSize=" + size + ", bigger than array size limit=" + Integer.MAX_VALUE);
        }
        if (size >= 0L) {
            return (int) size + 1;
        }
        report.debug("No terms.size() for field=" + report.getAttribute("field", "?"));
        size = 1L;
        final TermsEnum te = terms.iterator();
        for (BytesRef term = te.next(); term != null; term = te.next()) {
            size++;
        }
        if (size >= Integer.MAX_VALUE) {
            throw new ArrayIndexOutOfBoundsException(
                    "vocabSize=" + size + ", bigger than array size limit=" + Integer.MAX_VALUE);
        }
        if (size == 1L) {
            report.warn("No terms field=" + report.getAttribute("field", "?"));
        }
        return (int) size;
    }    /**
     * Writes a TSV text cell, quoting it if it contains control delimiters or quotes.
     *
     * @param out destination writer
     * @param value text to write
     * @throws IOException if writing fails
     */
    private static void writeTsvCell(final Writer out, final String value) throws IOException
    {
        if (value.indexOf('\t') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0 && value.indexOf('"') < 0) {
            out.write(value);
            return;
        }
        out.write('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"')
                out.write('"');
            out.write(c);
        }
        out.write('"');
    }
}
