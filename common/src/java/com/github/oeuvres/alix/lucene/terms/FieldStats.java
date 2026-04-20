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
import org.apache.lucene.util.FixedBitSet;

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
 * The object stores aggregate statistics aligned with the dense {@code termId} order used by
 * {@link TermLexicon} for the same field and snapshot.
 * </p>
 *
 * <h2>Stored statistics</h2>
 * <ul>
 * <li><b>{@code fieldDocs}</b>: number of documents that contain at least one term in the field</li>
 * <li><b>{@code maxDoc}</b>: Lucene document-address space size for the frozen reader snapshot</li>
 * <li><b>{@code fieldTokens}</b>: total number of tokens in the field</li>
 * <li><b>{@code termDocs[termId]}</b>: number of documents that contain the term</li>
 * <li><b>{@code termFreqs[termId]}</b>: total number of occurrences of the term in the field</li>
 * <li><b>{@code docWidths[docId]}</b>: exact token count of the field for one Lucene document id</li>
 * </ul>
 *
 * <h2>Intended use</h2>
 * <p>
 * This class represents the immutable reference population for one field. It is intended to be
 * combined with query-subset statistics such as {@code TermStats} in order to compute:
 * </p>
 * <ul>
 * <li>subset-vs-reference tests such as G-test or log-likelihood ratio,</li>
 * <li>relative frequencies and keyness measures,</li>
 * <li>tf-idf-like weighting that needs field-level document frequency,</li>
 * <li>dispersion and other field-wide statistics that need exact per-document token counts,</li>
 * <li>other derived per-term statistics over a frozen corpus snapshot.</li>
 * </ul>
 *
 * <h2>Exact document lengths</h2>
 * <p>
 * Exact field lengths are derived directly from the postings of the indexed field.
 * For each live document, {@code docWidths[docId]} is the sum of all term frequencies of that
 * field in that document.
 * </p>
 * <p>
 * This avoids any dependency on a separate length field and guarantees consistency with
 * {@code fieldTokens}, {@code termFreqs} and {@code termDocs}.
 * </p>
 *
 * <h2>Persistence format</h2>
 * <p>
 * The statistics are persisted in one binary sidecar file located directly in the Lucene directory:
 * </p>
 * <ul>
 * <li><b>{@code &lt;field&gt;.stats}</b></li>
 * </ul>
 * <p>
 * Binary layout, in order:
 * </p>
 * <ol>
 * <li>4-byte magic ({@code 0x46535453}, ASCII "FSTS")</li>
 * <li>4-byte format version</li>
 * <li>4-byte UTF-8 field-name byte length, followed by the field-name UTF-8 bytes</li>
 * <li>4-byte {@code maxDoc}</li>
 * <li>{@code maxDoc} times 4-byte {@code docWidth} (indexed by global doc id)</li>
 * <li>4-byte {@code vocabSize}</li>
 * <li>4-byte {@code fieldDocs}</li>
 * <li>8-byte {@code fieldTokens}</li>
 * <li>{@code vocabSize} times 4-byte {@code termDoc} (document frequency per term id)</li>
 * <li>{@code vocabSize} times 8-byte {@code termFreq} (total frequency per term id)</li>
 * </ol>
 * <p>
 * Multi-byte values are written in big-endian order through {@link DataOutputStream}.
 * </p>
 *
 * <h2>Order compatibility</h2>
 * <p>
 * The persisted term arrays follow Lucene's merged lexicographic {@link TermsEnum} order for the field.
 * This is the same order used by {@link TermLexicon} when it assigns dense term ids, provided both
 * artifacts were written from the same frozen index snapshot.
 * </p>
 * <p>
 * The persisted document-width array is indexed by Lucene global {@code docId} in the same frozen
 * reader snapshot, on the range {@code [0, maxDoc)}.
 * </p>
 *
 * <h2>Performance model</h2>
 * <p>
 * The persisted file is a compact binary artifact on disk, but {@link #open(IndexReader, Path, String)}
 * loads the numeric arrays into ordinary heap arrays. This is intentional: repeated per-term scoring
 * and repeated {@code docId -> docWidth} lookup are typically faster and more predictable on plain
 * primitive arrays than on memory-mapped buffers.
 * </p>
 *
 * <h2>Preconditions</h2>
 * <ul>
 * <li>The field must exist and have terms.</li>
 * <li>The field must store term frequencies. If frequencies are omitted at index time,
 * total term frequencies are unavailable and this class cannot be built.</li>
 * </ul>
 */
public final class FieldStats
{
    /** File magic: ASCII "FSTS". */
    private static final int MAGIC = 0x46535453;
    
    /** On-disk format version. */
    private static final int VERSION = 2;
    
    /** Lucene directory that contains both the index and the {@code <field>.stats} file. */
    private final Path sideDir;
    
    /** Per-document field width (max position + 1), indexed by global doc id; 0 for docs without the field. */
    private final int[] docWidths;

    /** Indexed field covered by these statistics. */
    private final String field;
    
    /** Number of documents that contain at least one term in the field. */
    private final int docs;

    /** Sum of all {@code docWidths}: total position count across the field. */
    private final long width;

    /** Total number of tokens in the field. */
    private final long tokens;

    /** Lucene document-address space size for this frozen reader snapshot. */
    private final int maxDoc;
    
    /** Per-term document frequencies, indexed by dense term id. */
    private final int[] termDocs;

    /** Per-term total occurrences in the field, indexed by dense term id. */
    protected final long[] termCounts;
    
    /**
     * Corpus-level term weight vector, indexed by dense term id.
     * {@code null} until {@link #buildWeights} has been called.
     * Written once and then read-only; declared volatile for safe publication.
     */
    private volatile double[] termWeights;
    /** The scorer used to calculate termeights */
    private volatile TermScorer termWeightsScorer;
    /** Number of distinct terms in the field. */
    final int vocabSize;
    
    /**
     * Creates an immutable statistics object from already-loaded arrays.
     *
     * @param sideDir      Lucene directory that contains the index and the stats file
     * @param field        indexed field
     * @param maxDoc       Lucene document-address space size
     * @param docWidths    exact field token counts by global doc id
     * @param width   sum of all docWidths
     * @param vocabSize    number of distinct terms
     * @param docs    number of documents that contain the field
     * @param tokens  total number of tokens in the field
     * @param termDocs     per-term document frequencies
     * @param termFreqs    per-term total term frequencies
     */
    private FieldStats(
            final Path sideDir,
            final String field,
            final int maxDoc,
            final int[] docWidths,
            final long width,
            final int vocabSize,
            final int docs,
            final long tokens,
            final int[] termDocs,
            final long[] termFreqs
    ) {
        this.sideDir = sideDir;
        this.field = field;
        this.maxDoc = maxDoc;
        this.docWidths = docWidths;
        this.width = width;
        this.vocabSize = vocabSize;
        this.docs = docs;
        this.tokens = tokens;
        this.termDocs = termDocs;
        this.termCounts = termFreqs;
    }
    
    /**
     * Returns an approximate heap footprint of the numeric arrays only.
     * <p>
     * This excludes object headers, references, the field string and JVM-specific overheads.
     * </p>
     *
     * @return approximate bytes used by {@code docWidths}, {@code termDocs} and {@code termFreqs}
     */
    public long arraysBytes()
    {
        return (long) docWidths.length * Integer.BYTES
                + (long) termDocs.length * Integer.BYTES
                + (long) termCounts.length * Long.BYTES
                ;
    }
    
    /**
     * Builds the statistics file for one field from an already opened snapshot reader.
     * <p>
     * This overload is useful when the caller already controls which Lucene snapshot is being read.
     * The file is written atomically via a temporary path: the final {@code <field>.stats} file
     * appears only after the stream is fully closed and flushed.
     * </p>
     *
     * @param reader   snapshot reader that defines the field statistics
     * @param sideDir  directory that will receive the {@code <field>.stats} file
     * @param field    indexed field name
     * @param report   progress reporter; may be {@code null}
     * @throws IOException if the field has no terms, if term frequencies are unavailable,
     *                     if a target file already exists, or if writing fails
     */
    public static void build(final IndexReader reader, final Path sideDir, final String field, Report report) throws IOException
    {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        if (report == null) report = Report.ReportNull.INSTANCE;
        // gather data
        final int maxDoc = reader.maxDoc();
        final int[] docWidths = docWidths(reader, field, report);
        final ByTermStats byTerm = termStats(reader, field, report);
        // write to tmp, close stream fully, then rename atomically
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
            // by-doc stats
            out.writeInt(maxDoc);
            for (int docWidth : docWidths) {
                out.writeInt(docWidth);
            }
            // by-term stats
            out.writeInt(byTerm.vocabSize);
            out.writeInt(byTerm.fieldDocs);
            out.writeLong(byTerm.fieldTokens);
            for (int termDoc : byTerm.termDocs) {
                out.writeInt(termDoc);
            }
            for (long termFreq : byTerm.termFreqs) {
                out.writeLong(termFreq);
            }
            // stream is flushed and closed by try-with-resources before the rename below
        } catch (IOException | RuntimeException e) {
            IOUtil.deleteIfExists(tmp);
            throw e;
        }
        // rename only after the stream is fully closed and flushed
        try {
            IOUtil.moveTemp(tmp, statsPath);
        } catch (IOException | RuntimeException e) {
            IOUtil.deleteIfExists(tmp);
            throw e;
        }
    }
    
    /**
     * Builds the per-document width array for one field from a snapshot reader.
     * <p>
     * For each live document, the width is defined as the highest position index seen across
     * all terms of the field, plus one. Documents without the field retain their default value
     * of {@code 0}.
     * </p>
     *
     * @param reader  snapshot reader
     * @param field   indexed field name
     * @param report  progress reporter; may be {@code null}
     * @return array of length {@code reader.maxDoc()}, indexed by global doc id
     * @throws IOException              if term or position iteration fails
     * @throws IllegalArgumentException if the field exists but has no positions
     */
    public static int[] docWidths(final IndexReader reader, final String field, Report report)
        throws IOException
    {
        if (reader == null) {
            throw new NullPointerException("reader is null");
        }
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("field is null or empty");
        }
        if (report == null) {
            report = Report.ReportNull.INSTANCE;
        }
        
        final int[] posLen = new int[reader.maxDoc()];
        // default 0 is exactly the desired convention
        
        int leafCount = 0;
        int leafWithFieldCount = 0;
        int docsWithPosLen = 0;
        int deletedPostingsSkipped = 0;
        int weirdPositionCount = 0;
        int maxPosLen = 0;
        
        for (LeafReaderContext ctx : reader.leaves()) {
            leafCount++;
            final LeafReader leaf = ctx.reader();
            final Terms terms = Terms.getTerms(leaf, field); // EMPTY if absent
            
            if (terms == null) {
                report.debug("field \"" + field + "\" absent in leaf at docBase=" + ctx.docBase);
                continue;
            }
            leafWithFieldCount++;
            
            if (!terms.hasPositions()) {
                final String msg = "field \"" + field + "\" has no positions in leaf at docBase=" + ctx.docBase;
                report.error(msg);
                throw new IllegalArgumentException(msg);
            }
            
            final Bits liveDocs = leaf.getLiveDocs();
            final TermsEnum te = terms.iterator();
            PostingsEnum pe = null;
            
            for (BytesRef term = te.next(); term != null; term = te.next()) {
                pe = te.postings(pe, PostingsEnum.POSITIONS);
                
                for (int localDocId = pe.nextDoc(); localDocId != PostingsEnum.NO_MORE_DOCS; localDocId = pe
                        .nextDoc())
                {
                    if (liveDocs != null && !liveDocs.get(localDocId)) {
                        deletedPostingsSkipped++;
                        continue;
                    }
                    
                    final int freq = pe.freq();
                    int docMaxPosForThisTerm = -1;
                    int prevPos = -1;
                    
                    for (int i = 0; i < freq; i++) {
                        final int pos = pe.nextPosition();
                        
                        if (pos < 0) {
                            weirdPositionCount++;
                            report.warn(
                                    "negative position on field \"" + field
                                            + "\", docId=" + (ctx.docBase + localDocId)
                                            + ", term=" + term.utf8ToString()
                                            + ", pos=" + pos);
                            continue;
                        }
                        
                        if (prevPos > pos) {
                            weirdPositionCount++;
                            report.warn(
                                    "non-monotone positions on field \"" + field
                                            + "\", docId=" + (ctx.docBase + localDocId)
                                            + ", term=" + term.utf8ToString()
                                            + ", prevPos=" + prevPos
                                            + ", pos=" + pos);
                        }
                        prevPos = pos;
                        
                        if (pos > docMaxPosForThisTerm) {
                            docMaxPosForThisTerm = pos;
                        }
                    }
                    
                    if (docMaxPosForThisTerm < 0) {
                        continue;
                    }
                    
                    final int globalDocId = ctx.docBase + localDocId;
                    final int newPosLen = docMaxPosForThisTerm + 1;
                    
                    if (newPosLen > posLen[globalDocId]) {
                        if (posLen[globalDocId] == 0) {
                            docsWithPosLen++;
                        }
                        posLen[globalDocId] = newPosLen;
                        
                        if (newPosLen > maxPosLen) {
                            maxPosLen = newPosLen;
                        }
                    }
                }
            }
        }
        
        report.info(
                "field=\"" + field + "\""
                        + ", leaves=" + leafCount
                        + ", leavesWithField=" + leafWithFieldCount
                        + ", docsWithPosLen=" + docsWithPosLen
                        + ", maxPosLen=" + maxPosLen
                        + ", deletedPostingsSkipped=" + deletedPostingsSkipped
                        + ", weirdPositions=" + weirdPositionCount);
        
        return posLen;
    }

    /**
     * Returns the field width (max position + 1) for one global Lucene document id.
     * <p>
     * Returns {@code 0} for documents that do not contain the field.
     * </p>
     *
     * @param docId global Lucene document id in {@code [0, maxDoc)}
     * @return number of token positions in the field for that document, or {@code 0}
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docWidth(final int docId)
    {
        checkDocId(docId);
        return docWidths[docId];
    }
    
    /**
     * Returns {@code true} if the persisted statistics file for the field exists as a regular file.
     * <p>
     * This is a cheap presence test only. It does not validate the file content.
     * </p>
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code true} if {@code <field>.stats} exists as a regular file
     */
    public static boolean exists(final Path indexDir, final String field)
    {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(statsPath(indexDir, field));
    }
    
    /**
     * Returns the indexed field covered by this reference population.
     *
     * @return field name
     */
    public String field()
    {
        return field;
    }
    

    /**
     * Returns the number of documents in the reference population.
     * <p>
     * This value is typically used for document-frequency-based scoring,
     * such as tf-idf-like measures.
     * </p>
     *
     * @return reference document count
     */
    public int fieldDocs()
    {
        return docs;
    }

    /**
     * Returns the total token count in the reference population.
     *
     * @return total token count in the reference population
     */
    public long fieldTokens()
    {
        return tokens;
    }

    /**
     * Returns the total positions count, including tokens and possible non indexed stopwords.
     *
     * @return total token count in the reference population
     */
    public long fieldWidth()
    {
        return width;
    }

    /** Build a global live-doc bitset aligned on top-level docIds. */
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
            }
            else {
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
     * <p>
     * Valid global document ids for {@link #docWidth(int)} are in {@code [0, maxDoc())}.
     * </p>
     *
     * @return reader maxDoc
     */
    public int maxDoc()
    {
        return maxDoc;
    }
    
    /**
     * Opens the persisted statistics for one field from a frozen Lucene directory.
     *
     * @param reader  snapshot reader used to cross-check maxDoc
     * @param sideDir directory that contains the stats file
     * @param field   indexed field name
     * @return opened immutable field statistics
     * @throws IOException if the file is missing, inconsistent or unreadable
     */
    public static FieldStats open(final IndexReader reader, final Path sideDir, final String field) throws IOException
    {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        
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
            // by-doc stats
            final int maxDoc = in.readInt();
            if (maxDoc != reader.maxDoc()) {
                throw new IOException("Read maxDoc=" + maxDoc + " inconsistent with Lucene IndexReader.maxDoc()=" + reader.maxDoc());
            }
            final int[] docWidths = new int[maxDoc];
            long width = 0L;
            for (int docId = 0; docId < maxDoc; docId++) {
                final int value = in.readInt();
                if (value < 0) {
                    throw new IOException("Invalid width=" + value + " for docId=" + docId);
                }
                docWidths[docId] = value;
                width += value;
            }
            // by-term stats
            final int vocabSize = in.readInt();
            if (vocabSize < 0) {
                throw new IOException("Invalid vocabSize in stats file: " + vocabSize);
            }
            
            final int docs = in.readInt();
            final long tokens = in.readLong();
            final int[] termDocs = new int[vocabSize];
            for (int termId = 0; termId < vocabSize; termId++) {
                termDocs[termId] = in.readInt();
            }
            
            final long[] termFreqs = new long[vocabSize];
            for (int termId = 0; termId < vocabSize; termId++) {
                termFreqs[termId] = in.readLong();
            }
            if (in.read() != -1) {
                throw new IOException("Trailing bytes in stats file: " + path);
            }
            
            return new FieldStats(
                sideDir, 
                field,
                maxDoc, 
                docWidths,
                width,
                vocabSize,
                docs,
                tokens,
                termDocs,
                termFreqs
            );
        } catch (EOFException e) {
            throw new IOException("Truncated stats file: " + path, e);
        }
    }
    
    /**
     * Opens the field statistics, building the sidecar file first if it does not exist,
     * using an already opened reader.
     *
     * @param reader   snapshot reader for building (ignored if file exists)
     * @param sideDir  Lucene directory that will receive the sidecar file
     * @param field    indexed field name
     * @param report   progress reporter; may be {@code null}
     * @return opened immutable field statistics
     * @throws IOException if building or opening fails
     */
    public static FieldStats openOrBuild(final IndexReader reader, final Path sideDir, final String field)
        throws IOException
    {
        if (!exists(sideDir, field)) {
            build(reader, sideDir, field, null);
        }
        return open(reader, sideDir, field);
    }
    
    /**
     * Opens the field statistics, building the sidecar file first if it does not exist,
     * using an already opened reader.
     *
     * @param reader   snapshot reader for building (ignored if file exists)
     * @param sideDir  Lucene directory that will receive the sidecar file
     * @param field    indexed field name
     * @param report   progress reporter; may be {@code null}
     * @return opened immutable field statistics
     * @throws IOException if building or opening fails
     */
    public static FieldStats openOrBuild(final IndexReader reader, final Path sideDir, final String field, final Report report)
        throws IOException
    {
        if (!exists(sideDir, field)) {
            build(reader, sideDir, field, report);
        }
        return open(reader, sideDir, field);
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
     * Returns the total number of occurrences of one term in the field.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return total term frequency in the field
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public long termCount(final int termId)
    {
        checkTermId(termId);
        return termCounts[termId];
    }
    
    /**
     * Get ref to global field term counts
     * @return 
     */
    public long[] termCountsRef()
    {
        return termCounts;
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
     * Collects per-term document frequencies and total term frequencies for one field.
     * <p>
     * The arrays in the returned record are indexed by dense term id, matching the order produced by
     * {@link TermLexicon} for the same snapshot. Iterates the {@link TermsEnum} in lexicographic order, 
     * to each term starting from 1. Id 0 is reserved as an absent-term sentinel.
     * </p>
     *
     * @param reader  snapshot reader
     * @param field   indexed field name
     * @param report  progress reporter; must not be {@code null}
     * @return aggregate term statistics
     * @throws IOException              if term iteration fails or term frequencies are unavailable
     * @throws IllegalArgumentException if the field does not exist or has no terms
     */
    public static ByTermStats termStats(
        final IndexReader reader, 
        final String field,
        Report report
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        // merge terms for those stats
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }
        long fieldDocs = terms.getDocCount();
        if (fieldDocs > Integer.MAX_VALUE) {
            throw new ArrayIndexOutOfBoundsException(
                    "Too much documents for field=" + field +
                            " " + fieldDocs + " > Integer.MAX_VALUE=" + Integer.MAX_VALUE);
        }
        report.setAttribute("field", field);
        final int vocabSize = vocabSize(terms, report);
        int[] termDocs = new int[vocabSize];
        long[] termFreqs = new long[vocabSize];
        int termId = 1; // Important, termId=0 means empty position
        final TermsEnum te = terms.iterator();
        @SuppressWarnings("unused")
        BytesRef term;
        while ((term = te.next()) != null) {
            if (termId >= vocabSize) {
                throw new IOException(
                        "Vocabulary size changed during FieldStats build for field '" + field +
                                "': seen more than " + vocabSize + " terms");
            }
            
            final int df = te.docFreq();
            if (df < 0) {
                throw new IOException("Negative docFreq for termId " + termId + " in field '" + field + "'");
            }
            termDocs[termId] = df;
            
            final long tf = te.totalTermFreq();
            if (tf < 0L) {
                throw new IOException(
                        "Term frequencies are unavailable for field '" + field + "'; cannot build FieldStats");
            }
            termFreqs[termId] = tf;
            
            termId++;
        }
        return new ByTermStats(vocabSize, (int)fieldDocs, terms.getSumTotalTermFreq(), termDocs, termFreqs);
    }

    /**
     * Returns the corpus-level weight of one term.
     *
     * <p>The weight is computed by {@link #buildWeights} and reflects how
     * thematically significant the term is in the corpus. It is suitable as
     * a building block for passage scoring: summing weights of terms in a
     * candidate window selects the window with the densest informative vocabulary.</p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return corpus-level weight, or {@code 0.0} for the absent-term sentinel (id 0)
     * @throws IllegalStateException    if {@link #buildWeights} has not been called yet
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public double termWeight(final int termId) {
        final double[] w = termWeights;
        if (w == null) {
            throw new IllegalStateException(
                "termWeights not built for field '" + field
                + "'; call buildWeights(reader, scorer) at startup");
        }
        checkTermId(termId);
        return w[termId];
    }

    public double[] termWeights(final IndexReader reader, final TermScorer scorer) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(scorer, "scorer");
        // weights already calculated with same scorer
        if (termWeights != null && scorer.equals(termWeightsScorer)) return termWeights;
        termWeightsScorer = scorer;
        termWeights = buildTermWeights(reader, scorer, null);
        return termWeights;
    }
    
    /**
     * Computes a corpus-level weight for every vocabulary term.
     *
     * <p>The weight is the BM25 summed score of the term across all documents:
     * {@code IDF(t) × Σ_d saturated_normalised_tf(t, d)}.
     * It reflects both how distinctive a term is (rare globally) and how
     * consistently it appears across documents. Common words score near zero;
     * thematically significant rare words score high.</p>
     *
     * <p>The implicit term-id assignment follows the same {@link MultiTerms} lexicographic
     * order used by {@link TermLexicon}: id 0 is reserved as the absent-term sentinel,
     * real terms start at 1. No {@link TermLexicon} lookup is performed per term.</p>
     *
     * @param reader snapshot reader; must match the snapshot from which this object was built
     * @param scorer local scorer (e.g. {@code new TermScorer.BM25(1.3)})
     * @throws IOException              if Lucene term or postings iteration fails
     * @throws IllegalStateException    if called before weights are needed (remind the caller
     *                                  to call this method at startup)
     */
    public double[] buildTermWeights(final IndexReader reader, final TermScorer scorer, FixedBitSet focusDocs) throws IOException {
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
        
        scorer.corpus(tokens, docs);
    
        final double[] weights = new double[vocabSize];
        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
    
        // Implicit termId: same MultiTerms lexicographic order as TermLexicon,
        // id 0 reserved as absent-term sentinel, real terms start at 1.
        int termId = 1;
    
        while (tenum.next() != null) {
            if (termId >= vocabSize) {
                throw new IOException(
                    "Vocabulary size changed during buildWeights for field '" + field
                    + "': seen more than " + vocabSize + " terms");
            }
    
            // termDocs and termFreqs are already available — no second pass needed.
            scorer.term(termCounts[termId], termDocs[termId]);
    
            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc();
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = postings.nextDoc()) {
                final int freq = postings.freq();
                if (freq <= 0) continue;
                scorer.score(freq, docWidths[docId]);
            }
    
            weights[termId] = scorer.result();
            termId++;
        }
        return weights;
    }
    
    /**
     * Returns the vocabulary size.
     *
     * @return number of distinct terms in the field
     */
    public int vocabSize()
    {
        return vocabSize;
    }

    /**
     * Resolves the vocabulary size of a field.
     * <p>
     * If Lucene exposes {@link Terms#size()}, that value is used directly.
     * Otherwise the method counts terms by iteration.
     * </p>
     *
     * @param reader index reader
     * @param field  indexed field name
     * @param report progress reporter
     * @return vocabulary size
     * @throws IOException if term iteration fails
     */
    public static int vocabSize(final IndexReader reader, final String field, Report report) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        if (report == null) report = Report.ReportNull.INSTANCE;
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            report.warn("No terms for field=" + field);
            return 0;
        }
        report.setAttribute("field", field);
        return vocabSize(terms, report);
    }

    /** Aggregate term-level statistics returned by {@link #byTermStats}. */
    public record ByTermStats(int vocabSize, int fieldDocs, long fieldTokens, int[] termDocs, long[] termFreqs){}
    
    /**
     * Checks that a document id is inside the valid range.
     *
     * @param docId global Lucene document id to validate
     * @throws IllegalArgumentException if the id is negative or outside {@code maxDoc}
     */
    private void checkDocId(final int docId)
    {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                    "docId out of range: " + docId + " (maxDoc=" + maxDoc + ')');
        }
    }

    /**
     * Checks that a term id is inside the valid range.
     *
     * @param termId dense term id to validate
     * @throws IllegalArgumentException if the id is negative or outside the vocabulary size
     */
    private void checkTermId(final int termId)
    {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException(
                    "termId out of range: " + termId + " (vocabSize=" + vocabSize + ')');
        }
    }

    /**
     * Returns the path of the persisted statistics file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <field>.stats}
     */
    private static Path statsPath(final Path indexDir, final String field)
    {
        return indexDir.resolve(field + ".stats");
    }

    private static int vocabSize(final Terms terms, final Report report) throws IOException
    {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(report, "report");
        long size = terms.size();
        if (size >= Integer.MAX_VALUE) {
            throw new ArrayIndexOutOfBoundsException("vocabSize=" + size + ", bigger than array size limit=" + Integer.MAX_VALUE);
        }
        if (size >= 0L) {
            return (int)size + 1;
        }
        report.debug("No terms.size() for field=" + report.getAttribute("field", "?"));
        size = 1L;
        final TermsEnum te = terms.iterator();
        for (BytesRef term = te.next(); term != null; term = te.next()) {
            size++;
        }
        if (size >= Integer.MAX_VALUE) {
            throw new ArrayIndexOutOfBoundsException("vocabSize=" + size + ", bigger than array size limit=" + Integer.MAX_VALUE);
        }
        if (size == 1L) {
            report.warn("No terms field=" + report.getAttribute("field", "?"));
        }
        return (int)size;
    }
}
