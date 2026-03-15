package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
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
 *   <li><b>{@code docCount}</b>: number of documents that contain at least one term in the field</li>
 *   <li><b>{@code maxDoc}</b>: Lucene document-address space size for the frozen reader snapshot</li>
 *   <li><b>{@code totalTermFreq}</b>: total number of tokens in the field</li>
 *   <li><b>{@code docFreqs[termId]}</b>: number of documents that contain the term</li>
 *   <li><b>{@code termFreqs[termId]}</b>: total number of occurrences of the term in the field</li>
 *   <li><b>{@code docLens[docId]}</b>: exact token count of the field for one Lucene document id</li>
 * </ul>
 *
 * <h2>Intended use</h2>
 * <p>
 * This class represents the immutable reference population for one field. It is intended to be
 * combined with query-subset statistics such as {@code TermStats} in order to compute:
 * </p>
 * <ul>
 *   <li>subset-vs-reference tests such as G-test or log-likelihood ratio,</li>
 *   <li>relative frequencies and keyness measures,</li>
 *   <li>tf-idf-like weighting that needs field-level document frequency,</li>
 *   <li>dispersion and other field-wide statistics that need exact per-document token counts,</li>
 *   <li>other derived per-term statistics over a frozen corpus snapshot.</li>
 * </ul>
 *
 * <h2>Exact document lengths</h2>
 * <p>
 * Exact field lengths are derived directly from the postings of the indexed field.
 * For each live document, {@code docLens[docId]} is the sum of all term frequencies of that
 * field in that document.
 * </p>
 * <p>
 * This avoids any dependency on a separate length field and guarantees consistency with
 * {@code totalTermFreq}, {@code termFreqs} and {@code docFreqs}.
 * </p>
 *
 * <h2>Persistence format</h2>
 * <p>
 * The statistics are persisted in one binary sidecar file located directly in the Lucene directory:
 * </p>
 * <ul>
 *   <li><b>{@code &lt;field&gt;.stats}</b></li>
 * </ul>
 * <p>
 * Binary layout, in order:
 * </p>
 * <ol>
 *   <li>4-byte magic</li>
 *   <li>4-byte format version</li>
 *   <li>4-byte UTF-8 field-name byte length</li>
 *   <li>field-name UTF-8 bytes</li>
 *   <li>4-byte {@code vocabSize}</li>
 *   <li>4-byte {@code docCount}</li>
 *   <li>4-byte {@code maxDoc}</li>
 *   <li>8-byte {@code totalTermFreq}</li>
 *   <li>{@code vocabSize} times 4-byte {@code docFreq}</li>
 *   <li>{@code vocabSize} times 8-byte {@code termFreq}</li>
 *   <li>{@code maxDoc} times 4-byte {@code docLen}</li>
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
 * The persisted document-length array is indexed by Lucene global {@code docId} in the same frozen
 * reader snapshot, on the range {@code [0, maxDoc)}.
 * </p>
 *
 * <h2>Performance model</h2>
 * <p>
 * The persisted file is a compact binary artifact on disk, but {@link #open(Path, String)} loads
 * the numeric arrays into ordinary heap arrays. This is intentional: repeated per-term scoring and
 * repeated {@code docId -> docLen} lookup are typically faster and more predictable on plain
 * primitive arrays than on memory-mapped buffers.
 * </p>
 *
 * <h2>Preconditions</h2>
 * <ul>
 *   <li>The field must exist and have terms.</li>
 *   <li>The field must store term frequencies. If frequencies are omitted at index time,
 *       total term frequencies are unavailable and this class cannot be built.</li>
 * </ul>
 */
public final class FieldStats implements ReferenceStats {
    /** File magic: ASCII "FSTS". */
    private static final int MAGIC = 0x46535453;

    /** On-disk format version. */
    private static final int VERSION = 2;

    /** Lucene directory that contains both the index and the {@code <field>.stats} file. */
    private final Path indexDir;

    /** Indexed field covered by these statistics. */
    private final String field;

    /** Number of distinct terms in the field. */
    private final int vocabSize;

    /** Number of documents that contain at least one term in the field. */
    private final int fieldDocs;

    /** Lucene document-address space size for this frozen reader snapshot. */
    private final int maxDoc;

    /** Total number of tokens in the field. */
    private final long fieldTokens;

    /** Per-term document frequencies, indexed by dense term id. */
    private final int[] termDocs;

    /** Per-term total occurrences in the field, indexed by dense term id. */
    private final long[] termFreqs;

    /** Exact field token counts, indexed by global Lucene doc id. */
    private final int[] docLens;

    /**
     * Creates an opened immutable statistics object.
     *
     * @param indexDir Lucene directory that contains the index and the stats file
     * @param field indexed field
     * @param vocabSize number of distinct terms
     * @param docCount number of documents that contain the field
     * @param maxDoc Lucene document-address space size
     * @param totalTermFreq total number of tokens in the field
     * @param docFreqs per-term document frequencies
     * @param termFreqs per-term total term frequencies
     * @param docLens exact field token counts by global doc id
     */
    private FieldStats(
        final Path indexDir,
        final String field,
        final int vocabSize,
        final int fieldDocs,
        final int maxDoc,
        final long fieldTokens,
        final int[] termDocs,
        final long[] termFreqs,
        final int[] docLens
    ) {
        this.indexDir = indexDir;
        this.field = field;
        this.vocabSize = vocabSize;
        this.fieldDocs = fieldDocs;
        this.maxDoc = maxDoc;
        this.fieldTokens = fieldTokens;
        this.termDocs = termDocs;
        this.termFreqs = termFreqs;
        this.docLens = docLens;
    }

    /**
     * Returns an approximate heap footprint of the numeric arrays only.
     * <p>
     * This excludes object headers, references, the field string and JVM-specific overheads.
     * </p>
     *
     * @return approximate bytes used by {@code docFreqs}, {@code termFreqs} and {@code docLens}
     */
    public long arraysBytes() {
        return (long) termDocs.length * Integer.BYTES
            + (long) termFreqs.length * Long.BYTES
            + (long) docLens.length * Integer.BYTES;
    }

    /**
     * Builds the statistics file for one field using the latest committed state of the Lucene directory.
     *
     * @param indexDir Lucene directory that contains the frozen index
     * @param field indexed field name
     * @throws IOException if the index cannot be opened, if the field has no terms, if term frequencies
     *         are unavailable, or if the target file already exists
     */
    public static void build(final Path indexDir, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
    
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            build(indexDir, reader, field);
        }
    }

    /**
     * Builds the statistics file for one field from an already opened snapshot reader.
     * <p>
     * This overload is useful when the caller already controls which Lucene snapshot is being read.
     * </p>
     *
     * @param indexDir Lucene directory that will receive the {@code <field>.stats} file
     * @param reader snapshot reader that defines the field statistics
     * @param field indexed field name
     * @throws IOException if the field has no terms, if term frequencies are unavailable,
     *         if a target file already exists, or if writing fails
     */
    public static void build(final Path indexDir, final IndexReader reader, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
    
        final Path statsPath = statsPath(indexDir, field);
        ensureAbsent(statsPath);
    
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }
    
        final int docCount = terms.getDocCount();
        if (docCount < 0) {
            throw new IOException("Field docCount is unavailable: " + field);
        }
    
        final long totalTermFreq = terms.getSumTotalTermFreq();
        if (totalTermFreq < 0L) {
            throw new IOException("Field totalTermFreq is unavailable; term frequencies are required: " + field);
        }
    
        final int vocabSize = resolveVocabSize(terms);
        final int maxDoc = reader.maxDoc();
    
        final Path tmp = tmpPath(statsPath);
        ensureAbsent(tmp);
    
        try {
            buildFile(tmp, reader, field, vocabSize, docCount, maxDoc, totalTermFreq);
            moveTemp(tmp, statsPath);
        } catch (IOException | RuntimeException e) {
            deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Returns the document frequency of one term.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return number of documents that contain the term
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    @Override
    public int docFreq(final int termId) {
        checkTermId(termId);
        return termDocs[termId];
    }

    /**
     * Returns a defensive copy of the document-frequency vector.
     *
     * @return copied {@code docFreq} array
     */
    public int[] docFreqsCopy() {
        return Arrays.copyOf(termDocs, termDocs.length);
    }

    /**
     * Returns the exact token count of the field for one global Lucene document id.
     * <p>
     * The returned value is:
     * </p>
     * <ul>
     *   <li>the exact number of indexed tokens of the field for that document,</li>
     *   <li>{@code 0} if the document has no value for the field,</li>
     *   <li>{@code 0} for deleted documents in the frozen snapshot.</li>
     * </ul>
     *
     * @param docId global Lucene document id in {@code [0, maxDoc)}
     * @return exact field token count for that document
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docLen(final int docId) {
        checkDocId(docId);
        return docLens[docId];
    }

    /**
     * Returns {@code true} if the persisted statistics file for the field exists as a regular file.
     * <p>
     * This is a cheap presence test only. It does not validate the file content.
     * </p>
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code true} if {@code <field>.stats} exists as a regular file
     */
    public static boolean exists(final Path indexDir, final String field) {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(statsPath(indexDir, field));
    }

    /**
     * Returns the field name covered by these statistics.
     *
     * @return field name
     */
    @Override
    public String field() {
        return field;
    }

    /**
     * Returns the number of documents that contain at least one term in the field.
     *
     * @return field document count
     */
    @Override
    public int fieldDocs() {
        return fieldDocs;
    }

    /**
     * Returns the total number of tokens in the field.
     *
     * @return field total term frequency
     */
    @Override
    public long fieldTokens() {
        return fieldTokens;
    }

    /**
     * Returns the Lucene directory from which these statistics were opened.
     *
     * @return Lucene directory path
     */
    public Path indexDir() {
        return indexDir;
    }

    /**
     * Returns the Lucene document-address space size for this frozen reader snapshot.
     * <p>
     * Valid global document ids for {@link #docLen(int)} are in {@code [0, maxDoc())}.
     * </p>
     *
     * @return reader maxDoc
     */
    public int maxDoc() {
        return maxDoc;
    }

    /**
     * Opens the persisted statistics for one field from a frozen Lucene directory.
     *
     * @param indexDir Lucene directory that contains the index and the stats file
     * @param field indexed field name
     * @return opened immutable field statistics
     * @throws IOException if the file is missing, inconsistent or unreadable
     */
    public static FieldStats open(final Path indexDir, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
    
        final Path path = statsPath(indexDir, field);
        ensureRegularFile(path);
    
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ))
        )) {
            final int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid stats file magic: " + path);
            }
    
            final int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported stats file version " + version + ": " + path);
            }
    
            final String storedField = readUtf8(in);
            if (!field.equals(storedField)) {
                throw new IOException(
                    "Field mismatch in stats file: requested '" + field + "', found '" + storedField + "'"
                );
            }
    
            final int vocabSize = in.readInt();
            if (vocabSize < 0) {
                throw new IOException("Invalid vocabSize in stats file: " + vocabSize);
            }
    
            final int docCount = in.readInt();
            if (docCount < 0) {
                throw new IOException("Invalid docCount in stats file: " + docCount);
            }
    
            final int maxDoc = in.readInt();
            if (maxDoc < 0) {
                throw new IOException("Invalid maxDoc in stats file: " + maxDoc);
            }
    
            final long totalTermFreq = in.readLong();
            if (totalTermFreq < 0L) {
                throw new IOException("Invalid totalTermFreq in stats file: " + totalTermFreq);
            }
    
            final int[] docFreqs = new int[vocabSize];
            for (int i = 0; i < vocabSize; i++) {
                final int value = in.readInt();
                if (value < 0) {
                    throw new IOException("Invalid docFreq at termId " + i + ": " + value);
                }
                docFreqs[i] = value;
            }
    
            final long[] termFreqs = new long[vocabSize];
            for (int i = 0; i < vocabSize; i++) {
                final long value = in.readLong();
                if (value < 0L) {
                    throw new IOException("Invalid termFreq at termId " + i + ": " + value);
                }
                termFreqs[i] = value;
            }
    
            final int[] docLens = new int[maxDoc];
            long docLensSum = 0L;
            for (int docId = 0; docId < maxDoc; docId++) {
                final int value = in.readInt();
                if (value < 0) {
                    throw new IOException("Invalid docLen at docId " + docId + ": " + value);
                }
                docLens[docId] = value;
                docLensSum += value;
            }
    
            if (docLensSum != totalTermFreq) {
                throw new IOException(
                    "Inconsistent stats file: sum(docLens)=" + docLensSum +
                    ", totalTermFreq=" + totalTermFreq + " for field '" + field + "'"
                );
            }
    
            if (in.read() != -1) {
                throw new IOException("Trailing bytes in stats file: " + path);
            }
    
            return new FieldStats(indexDir, field, vocabSize, docCount, maxDoc, totalTermFreq, docFreqs, termFreqs, docLens);
        } catch (EOFException e) {
            throw new IOException("Truncated stats file: " + path, e);
        }
    }
    
    /**
     * Opens the field statistics, building the sidecar file first if it does not exist.
     * <p>
     * Opens a {@link DirectoryReader} internally if building is needed.
     * When both {@code FieldStats} and {@code TermLexicon} need building
     * for the same field, prefer the overload that accepts an
     * {@link IndexReader} to avoid opening the index twice.
     * </p>
     *
     * @param indexDir Lucene directory containing the frozen index
     * @param field    indexed field name
     * @return opened immutable field statistics
     * @throws IOException if building or opening fails
     */
    public static FieldStats openOrBuild(final Path indexDir, final String field) throws IOException {
        if (!exists(indexDir, field)) {
            build(indexDir, field);
        }
        return open(indexDir, field);
    }

    /**
     * Opens the field statistics, building the sidecar file first if it does not exist,
     * using an already opened reader.
     *
     * @param indexDir Lucene directory that will receive the sidecar file
     * @param reader   snapshot reader for building (ignored if file exists)
     * @param field    indexed field name
     * @return opened immutable field statistics
     * @throws IOException if building or opening fails
     */
    public static FieldStats openOrBuild(final Path indexDir, final IndexReader reader, final String field)
        throws IOException
    {
        if (!exists(indexDir, field)) {
            build(indexDir, reader, field);
        }
        return open(indexDir, field);
    }

    /**
     * Returns the total number of occurrences of one term in the field.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return total term frequency in the field
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    @Override
    public long termFreq(final int termId) {
        checkTermId(termId);
        return termFreqs[termId];
    }

    /**
     * Returns a defensive copy of the total-term-frequency vector.
     *
     * @return copied {@code termFreq} array
     */
    public long[] termFreqsCopy() {
        return Arrays.copyOf(termFreqs, termFreqs.length);
    }

    /**
     * Returns the vocabulary size.
     *
     * @return number of distinct terms in the field
     */
    @Override
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Builds the persisted binary file.
     *
     * @param path target temporary path
     * @param reader frozen snapshot reader
     * @param field indexed field
     * @param vocabSize vocabulary size
     * @param docCount field document count
     * @param maxDoc reader maxDoc
     * @param totalTermFreq field total token count
     * @throws IOException if writing fails
     */
    private static void buildFile(
        final Path path,
        final IndexReader reader,
        final String field,
        final int vocabSize,
        final int docCount,
        final int maxDoc,
        final long totalTermFreq
    ) throws IOException {
        final int[] docFreqs = new int[vocabSize];
        final long[] termFreqs = new long[vocabSize];
        final int[] docLens = new int[maxDoc];
    
        fillStats(reader, field, vocabSize, docFreqs, termFreqs, docLens);
    
        long docLensSum = 0L;
        for (int docLen : docLens) {
            docLensSum += docLen;
        }
        if (docLensSum != totalTermFreq) {
            throw new IOException(
                "Inconsistent field statistics for field '" + field + "': sum(docLens)=" + docLensSum +
                ", totalTermFreq=" + totalTermFreq
            );
        }
    
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW));
             DataOutputStream out = new DataOutputStream(os)) {
    
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeUtf8(out, field);
            out.writeInt(vocabSize);
            out.writeInt(docCount);
            out.writeInt(maxDoc);
            out.writeLong(totalTermFreq);
    
            for (int docFreq : docFreqs) {
                out.writeInt(docFreq);
            }
            for (long termFreq : termFreqs) {
                out.writeLong(termFreq);
            }
            for (int docLen : docLens) {
                out.writeInt(docLen);
            }
        }
    }

    /**
     * Checks that a document id is inside the valid range.
     *
     * @param docId global Lucene document id to validate
     * @throws IllegalArgumentException if the id is negative or outside {@code maxDoc}
     */
    private void checkDocId(final int docId) {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                "docId out of range: " + docId + " (maxDoc=" + maxDoc + ')'
            );
        }
    }

    /**
     * Checks that a term id is inside the valid range.
     *
     * @param termId dense term id to validate
     * @throws IllegalArgumentException if the id is negative or outside the vocabulary size
     */
    private void checkTermId(final int termId) {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException(
                "termId out of range: " + termId + " (vocabSize=" + vocabSize + ')'
            );
        }
    }

    /**
     * Deletes a file if it exists.
     *
     * @param path path to delete
     */
    private static void deleteIfExists(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
    }

    /**
     * Ensures that a file does not already exist.
     *
     * @param path target path
     * @throws FileAlreadyExistsException if the path already exists
     */
    private static void ensureAbsent(final Path path) throws FileAlreadyExistsException {
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    /**
     * Ensures that a file exists and is a regular file.
     *
     * @param path path to check
     * @throws IOException if the file does not exist or is not regular
     */
    private static void ensureRegularFile(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /**
     * Fills per-term and per-document statistics directly from the field postings.
     *
     * @param reader frozen snapshot reader
     * @param field indexed field
     * @param vocabSize vocabulary size
     * @param docFreqs destination per-term document frequencies
     * @param termFreqs destination per-term total occurrences
     * @param docLens destination per-document field lengths
     * @throws IOException if term or postings traversal fails
     */
    private static void fillStats(
        final IndexReader reader,
        final String field,
        final int vocabSize,
        final int[] docFreqs,
        final long[] termFreqs,
        final int[] docLens
    ) throws IOException {
        int termId = 0;

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final TermsEnum te = terms.iterator();
        BytesRef term;

        while ((term = te.next()) != null) {
            if (termId >= vocabSize) {
                throw new IOException(
                    "Vocabulary size changed during FieldStats build for field '" + field +
                    "': seen more than " + vocabSize + " terms"
                );
            }

            final int df = te.docFreq();
            if (df < 0) {
                throw new IOException("Negative docFreq for termId " + termId + " in field '" + field + "'");
            }
            docFreqs[termId] = df;

            final long tf = te.totalTermFreq();
            if (tf < 0L) {
                throw new IOException(
                    "Term frequencies are unavailable for field '" + field + "'; cannot build FieldStats"
                );
            }
            termFreqs[termId] = tf;

            termId++;
        }

        if (termId != vocabSize) {
            throw new IOException(
                "Vocabulary size mismatch during FieldStats build for field '" + field +
                "': header=" + vocabSize + ", seen=" + termId
            );
        }

        final List<LeafReaderContext> leaves = reader.leaves();
        for (LeafReaderContext ctx : leaves) {
            final LeafReader leaf = ctx.reader();
            final Terms leafTerms = leaf.terms(field);
            if (leafTerms == null) {
                continue;
            }
            if (!leafTerms.hasFreqs()) {
                throw new IOException(
                    "Field '" + field + "' does not store term frequencies; cannot build FieldStats"
                );
            }

            final Bits liveDocs = leaf.getLiveDocs();
            final int docBase = ctx.docBase;
            final TermsEnum leafEnum = leafTerms.iterator();
            PostingsEnum postings = null;

            while (leafEnum.next() != null) {
                postings = leafEnum.postings(postings, PostingsEnum.FREQS);
                for (int doc = postings.nextDoc(); doc != PostingsEnum.NO_MORE_DOCS; doc = postings.nextDoc()) {
                    if (liveDocs != null && !liveDocs.get(doc)) {
                        continue;
                    }
                    docLens[docBase + doc] += postings.freq();
                }
            }
        }
    }

    /**
     * Moves one temporary file into its final location.
     *
     * @param source temporary file path
     * @param target final file path
     * @throws IOException if the move fails
     */
    private static void moveTemp(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target);
        }
    }

    /**
     * Resolves the vocabulary size of a field.
     * <p>
     * If Lucene exposes {@link Terms#size()}, that value is used directly.
     * Otherwise the method counts terms by iteration.
     * </p>
     *
     * @param terms merged field terms
     * @return vocabulary size
     * @throws IOException if term iteration fails
     */
    private static int resolveVocabSize(final Terms terms) throws IOException {
        final long size = terms.size();
        if (size >= 0L) {
            if (size > Integer.MAX_VALUE) {
                throw new IOException("Too many terms for int vocabulary size: " + size);
            }
            return (int) size;
        }

        int count = 0;
        final TermsEnum te = terms.iterator();
        while (te.next() != null) {
            if (count == Integer.MAX_VALUE) {
                throw new IOException("Too many terms for int vocabulary size");
            }
            count++;
        }
        return count;
    }

    /**
     * Reads one UTF-8 string preceded by its byte length.
     *
     * @param in source stream
     * @return decoded string
     * @throws IOException if reading fails or if the encoded length is invalid
     */
    private static String readUtf8(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative UTF-8 byte length: " + length);
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the path of the persisted statistics file for one field.
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code <field>.stats}
     */
    private static Path statsPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".stats");
    }

    /**
     * Returns the temporary path used while writing one file.
     *
     * @param path final target path
     * @return sibling temporary path with {@code .tmp} suffix
     */
    private static Path tmpPath(final Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".tmp");
    }

    /**
     * Writes one UTF-8 string preceded by its byte length.
     *
     * @param out destination stream
     * @param s string to write
     * @throws IOException if writing fails
     */
    private static void writeUtf8(final DataOutputStream out, final String s) throws IOException {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}