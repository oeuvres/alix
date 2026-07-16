package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.IOUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Objects;

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
 * One binary sidecar file {@code <field>.stats} in the Lucene directory, version 3 layout:
 * </p>
 * <ol>
 * <li>4B magic {@code 0x46535453} ("FSTS")</li>
 * <li>4B format version {@code 3}</li>
 * <li>UTF-8 field name (4B length prefix + bytes)</li>
 * <li>4B {@code maxDoc}</li>
 * <li>{@code maxDoc × 4B docWidths}</li>
 * <li>{@code maxDoc × 4B docTokens}</li>
 * <li>4B {@code vocabSize}</li>
 * <li>{@code vocabSize × 4B termDocs}</li>
 * <li>{@code vocabSize × 8B termCounts}</li>
 * </ol>
 * <p>
 * Multi-byte values are big-endian through {@link DataOutputStream}.
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
    /** File magic: ASCII "FSTS". */
    private static final int MAGIC = 0x46535453;
    
    /** On-disk format version. */
    private static final int VERSION = 3;
    
    /** Lucene directory that contains both the index and the {@code <field>.stats} file. */
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
    private volatile IdfTermScorer termWeightsScorer;
    
    /**
     * Creates an immutable statistics object from already-loaded arrays and derived scalars.
     *
     * @param sideDir    Lucene directory that contains the index and the stats file
     * @param field      indexed field
     * @param maxDoc     Lucene document-address space size
     * @param docWidths  per-document widths by global doc id
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
     * Builds and persists the statistics file for one field from an already opened snapshot reader.
     * <p>
     * One unified per-document pass (positions + frequencies) produces both {@code docWidths}
     * and {@code docTokens}. A separate per-term pass produces {@code termDocs} and {@code termCounts}.
     * The file is written atomically via a temporary path.
     * </p>
     *
     * @param reader  snapshot reader
     * @param sideDir directory that will receive the {@code <field>.stats} file
     * @param field   indexed field name
     * @param report  progress reporter; may be {@code null}
     * @throws IOException if the field has no terms, if frequencies are unavailable,
     *                     if a target file already exists, or if writing fails
     */
    public static void build(final IndexReader reader, final Path sideDir, final String field, Report report)
        throws IOException
    {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;
        
        final int maxDoc = reader.maxDoc();
        final DocStats d = docStats(reader, field, report);
        final VocabCounts t = vocabCounts(reader, field, report);
        
        final Path statsPath = statsPath(sideDir, field);
        IOUtil.ensureAbsent(statsPath);
        final Path tmp = IOUtil.tmpPath(statsPath);
        IOUtil.ensureAbsent(tmp);
        
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW));
                DataOutputStream out = new DataOutputStream(os))
        {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            IOUtil.writeUtf8(out, field);
            
            out.writeInt(maxDoc);
            for (int w : d.docWidths)
                out.writeInt(w);
            for (int n : d.docTokens)
                out.writeInt(n);
            
            out.writeInt(t.vocabSize);
            for (int df : t.termDocs)
                out.writeInt(df);
            for (long tf : t.termCounts)
                out.writeLong(tf);
        } catch (IOException | RuntimeException e) {
            IOUtil.deleteIfExists(tmp);
            throw e;
        }
        try {
            IOUtil.moveTemp(tmp, statsPath);
        } catch (IOException | RuntimeException e) {
            IOUtil.deleteIfExists(tmp);
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
     * {@link IdfTermScorer#focus}. Each posting visit then tells the scorer whether the
     * document is in focus, and the scorer accumulates into focus or rest separately.
     * </p>
     *
     * @param reader    snapshot reader matching this FieldStats
     * @param scorer    scorer to weight terms
     * @param focusDocs optional focus bitset for contrastive scoring; {@code null} for non-contrastive
     * @return weight vector indexed by dense term id
     * @throws IOException           if term or postings iteration fails
     * @throws IllegalStateException if the field has no terms or no frequencies
     */
    public double[] buildTermWeights(final IndexReader reader, final IdfTermScorer scorer)
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
     * Give direct access to a vector, by internal Lucene docId, of “width”
     * (position indexed, even empty positions, for example, if stopwords have 
     * been removed).
     * 
     * @return
     */
    public int[] docWidths()
    {
        return docWidths;
    }
    
    /**
     * Redo the parse of index to get the width of docs, for TermRail.
     * 
     * @param reader
     * @param field
     * @param report
     * @return
     * @throws IOException
     */
    public static int[] docWidths(IndexReader reader, String field, Report report) throws IOException
    {
        return docStats(reader, field, report).docWidths();
    }
    
    /**
     * Returns {@code true} if the persisted statistics file for the field exists as a regular file.
     * Cheap presence test only; does not validate content.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code true} if {@code <field>.stats} exists
     */
    public static boolean exists(final Path indexDir, final String field)
    {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(statsPath(indexDir, field));
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
     * @return biggest with
     */
    public int maxWidth()
    {
        return maxWidth;
    }
    
    /**
     * Opens the persisted statistics for one field from a frozen Lucene directory.
     * <p>
     * Derives {@code width}, {@code tokens}, {@code docs} from the per-document arrays.
     * Validates the invariant {@code docTokens[d] ≤ docWidths[d]}; violations are reported
     * via {@code report.warn} but do not abort the open.
     * </p>
     *
     * @param reader  snapshot reader used to cross-check maxDoc
     * @param sideDir directory containing the stats file
     * @param field   indexed field name
     * @param report  progress reporter; may be {@code null}
     * @return opened immutable field statistics
     * @throws IOException if the file is missing, inconsistent, or unreadable
     */
    public static TermStats open(
        final IndexReader reader,
        final Path sideDir,
        final String field,
        Report report) throws IOException
    {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;
        
        final Path path = statsPath(sideDir, field);
        IOUtil.ensureRegularFile(path);
        
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ))))
        {
            final int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid stats file magic: " + path);
            }
            final int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported stats file version " + version + ": " + path);
            }
            final String fieldFound = IOUtil.readUtf8(in);
            if (!field.equals(fieldFound)) {
                throw new IOException(
                        "Field mismatch in stats file: requested '" + field + "', found '" + fieldFound + "'");
            }
            
            final int maxDoc = in.readInt();
            if (maxDoc != reader.maxDoc()) {
                throw new IOException(
                        "Read maxDoc=" + maxDoc + " inconsistent with IndexReader.maxDoc()=" + reader.maxDoc());
            }
            
            final int[] docWidths = new int[maxDoc];
            long width = 0L;
            int maxWidth = 0;
            for (int docId = 0; docId < maxDoc; docId++) {
                final int v = in.readInt();
                if (v < 0)
                    throw new IOException("Invalid docWidth=" + v + " for docId=" + docId);
                docWidths[docId] = v;
                width += v;
                maxWidth = Math.max(maxWidth, v);
            }
            
            final int[] docTokens = new int[maxDoc];
            long tokens = 0L;
            int docs = 0;
            int invariantViolations = 0;
            for (int docId = 0; docId < maxDoc; docId++) {
                final int v = in.readInt();
                if (v < 0)
                    throw new IOException("Invalid docTokens=" + v + " for docId=" + docId);
                docTokens[docId] = v;
                tokens += v;
                if (v > 0)
                    docs++;
                if (v > docWidths[docId])
                    invariantViolations++;
            }
            if (invariantViolations > 0) {
                report.warn("field=\"" + field + "\": "
                        + invariantViolations + " documents with docTokens > docWidths");
            }
            
            final int vocabSize = in.readInt();
            if (vocabSize < 0) {
                throw new IOException("Invalid vocabSize in stats file: " + vocabSize);
            }
            final int[] termDocs = new int[vocabSize];
            for (int termId = 0; termId < vocabSize; termId++) {
                termDocs[termId] = in.readInt();
            }
            final long[] termCounts = new long[vocabSize];
            for (int termId = 0; termId < vocabSize; termId++) {
                termCounts[termId] = in.readLong();
            }
            if (in.read() != -1) {
                throw new IOException("Trailing bytes in stats file: " + path);
            }
            
            return new TermStats(
                    sideDir,
                    field,
                    maxDoc,
                    docWidths,
                    maxWidth,
                    docTokens,
                    vocabSize,
                    termDocs,
                    termCounts,
                    docs,
                    width,
                    tokens);
        } catch (EOFException e) {
            throw new IOException("Truncated stats file: " + path, e);
        }
    }
    
    /**
     * Opens the field statistics, building the sidecar file first if it does not exist.
     *
     * @param reader  snapshot reader (used for building if needed and for maxDoc cross-check)
     * @param sideDir directory that may receive the sidecar file
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
     * Opens the field statistics, building the sidecar file first if it does not exist.
     *
     * @param reader  snapshot reader
     * @param sideDir directory that may receive the sidecar file
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
     * Collects per-term document frequencies and total occurrences in one pass over the
     * merged {@link TermsEnum}. Arrays are indexed by dense term id; id 0 is the sentinel.
     *
     * @param reader snapshot reader
     * @param field  indexed field name
     * @param report progress reporter; may be {@code null}
     * @return per-term statistics
     * @throws IOException              if term iteration fails or frequencies are unavailable
     * @throws IllegalArgumentException if the field does not exist or has no terms
     */
    public static VocabCounts vocabCounts(final IndexReader reader, final String field, Report report)
        throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        if (report == null)
            report = Report.ReportNull.INSTANCE;
        
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }
        report.setAttribute("field", field);
        
        final int vocabSize = vocabSize(terms, report);
        final int[] termDocs = new int[vocabSize];
        final long[] termCounts = new long[vocabSize];
        int termId = 1;
        final TermsEnum te = terms.iterator();
        while (te.next() != null) {
            if (termId >= vocabSize) {
                throw new IOException(
                        "Vocabulary size changed during FieldStats build for field '" + field
                                + "': seen more than " + vocabSize + " terms");
            }
            final int df = te.docFreq();
            if (df < 0) {
                throw new IOException("Negative docFreq for termId " + termId + " in field '" + field + "'");
            }
            termDocs[termId] = df;
            
            final long tf = te.totalTermFreq();
            if (tf < 0L) {
                throw new IOException(
                        "Term frequencies unavailable for field '" + field + "'");
            }
            termCounts[termId] = tf;
            termId++;
        }
        return new VocabCounts(vocabSize, termDocs, termCounts);
    }
    
    /**
     * Returns the cached corpus-level term weight for one term.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return cached weight, or {@code 0.0} for the absent-term sentinel (id 0)
     * @throws IllegalStateException    if {@link #termWeights(IndexReader, IdfTermScorer)} has not been called
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
     * Get reference to the last generated weights.
     * 
     * @return {@link #termWeights}
     */
    public double[] termWeightsRef()
    {
        return termWeights;
    }
    
    /**
     * Returns the cached term weight vector, computing it on first call or when the
     * scorer changes. Subsequent calls with an equivalent scorer return the same array.
     *
     * @param reader snapshot reader matching this FieldStats
     * @param scorer scorer used to weight terms
     * @return weight vector indexed by dense term id
     * @throws IOException if term iteration fails
     */
    public double[] termWeights(final IndexReader reader, final IdfTermScorer scorer) throws IOException
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
     * Path of the persisted statistics file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <field>.stats}
     */
    private static Path statsPath(final Path indexDir, final String field)
    {
        return indexDir.resolve(field + ".stats");
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
    }
}
