package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
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
 *   <li><b>{@code totalTermFreq}</b>: total number of tokens in the field</li>
 *   <li><b>{@code docFreqs[termId]}</b>: number of documents that contain the term</li>
 *   <li><b>{@code termFreqs[termId]}</b>: total number of occurrences of the term in the field</li>
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
 *   <li>other derived per-term statistics over a frozen corpus snapshot.</li>
 * </ul>
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
 *   <li>8-byte {@code totalTermFreq}</li>
 *   <li>{@code vocabSize} times 4-byte {@code docFreq}</li>
 *   <li>{@code vocabSize} times 8-byte {@code termFreq}</li>
 * </ol>
 * <p>
 * Multi-byte values are written in big-endian order through {@link DataOutputStream}.
 * </p>
 *
 * <h2>Order compatibility</h2>
 * <p>
 * The persisted arrays follow Lucene's merged lexicographic {@link TermsEnum} order for the field.
 * This is the same order used by {@link TermLexicon} when it assigns dense term ids, provided both
 * artifacts were written from the same frozen index snapshot.
 * </p>
 *
 * <h2>Performance model</h2>
 * <p>
 * The persisted file is a compact binary artifact on disk, but {@link #open(Path, String)} loads
 * the numeric arrays into ordinary heap arrays. This is intentional: repeated per-term scoring is
 * typically faster and more predictable on plain {@code int[]} and {@code long[]} than on
 * memory-mapped buffers.
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
    private static final int VERSION = 1;

    /** Lucene directory that contains both the index and the {@code <field>.stats} file. */
    private final Path indexDir;

    /** Indexed field covered by these statistics. */
    private final String field;

    /** Number of distinct terms in the field. */
    private final int vocabSize;

    /** Number of documents that contain at least one term in the field. */
    private final int docCount;

    /** Total number of tokens in the field. */
    private final long totalTermFreq;

    /** Per-term document frequencies, indexed by dense term id. */
    private final int[] docFreqs;

    /** Per-term total occurrences in the field, indexed by dense term id. */
    private final long[] termFreqs;

    /**
     * Creates an opened immutable statistics object.
     *
     * @param indexDir Lucene directory that contains the index and the stats file
     * @param field indexed field
     * @param vocabSize number of distinct terms
     * @param docCount number of documents that contain the field
     * @param totalTermFreq total number of tokens in the field
     * @param docFreqs per-term document frequencies
     * @param termFreqs per-term total term frequencies
     */
    private FieldStats(
        final Path indexDir,
        final String field,
        final int vocabSize,
        final int docCount,
        final long totalTermFreq,
        final int[] docFreqs,
        final long[] termFreqs
    ) {
        this.indexDir = indexDir;
        this.field = field;
        this.vocabSize = vocabSize;
        this.docCount = docCount;
        this.totalTermFreq = totalTermFreq;
        this.docFreqs = docFreqs;
        this.termFreqs = termFreqs;
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
     * Builds the statistics file for one field using the latest committed state of the Lucene directory.
     *
     * @param indexDir Lucene directory that contains the frozen index
     * @param field indexed field name
     * @throws IOException if the index cannot be opened, if the field has no terms, if term frequencies
     *         are unavailable, or if the target file already exists
     */
    public static void write(final Path indexDir, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");

        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            write(indexDir, reader, field);
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
    public static void write(final Path indexDir, final IndexReader reader, final String field) throws IOException {
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
        final Path tmp = tmpPath(statsPath);
        ensureAbsent(tmp);

        try {
            buildFile(tmp, field, vocabSize, docCount, totalTermFreq, terms);
            moveTemp(tmp, statsPath);
        } catch (IOException | RuntimeException e) {
            deleteIfExists(tmp);
            throw e;
        }
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

            if (in.read() != -1) {
                throw new IOException("Trailing bytes in stats file: " + path);
            }

            return new FieldStats(indexDir, field, vocabSize, docCount, totalTermFreq, docFreqs, termFreqs);
        } catch (EOFException e) {
            throw new IOException("Truncated stats file: " + path, e);
        }
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
     * Returns the field name covered by these statistics.
     *
     * @return field name
     */
    public String field() {
        return field;
    }

    /**
     * Returns the vocabulary size.
     *
     * @return number of distinct terms in the field
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Returns the number of documents that contain at least one term in the field.
     *
     * @return field document count
     */
    public int docCount() {
        return docCount;
    }

    /**
     * Returns the total number of tokens in the field.
     *
     * @return field total term frequency
     */
    public long totalTermFreq() {
        return totalTermFreq;
    }

    /**
     * Returns the document frequency of one term.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return number of documents that contain the term
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public int docFreq(final int termId) {
        checkTermId(termId);
        return docFreqs[termId];
    }

    /**
     * Returns the total number of occurrences of one term in the field.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return total term frequency in the field
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public long termFreq(final int termId) {
        checkTermId(termId);
        return termFreqs[termId];
    }

    /**
     * Returns a defensive copy of the document-frequency vector.
     *
     * @return copied {@code docFreq} array
     */
    public int[] docFreqsCopy() {
        return Arrays.copyOf(docFreqs, docFreqs.length);
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
     * Returns an approximate heap footprint of the numeric arrays only.
     * <p>
     * This excludes object headers, references, the field string and JVM-specific overheads.
     * </p>
     *
     * @return approximate bytes used by {@code docFreqs} and {@code termFreqs}
     */
    public long arraysBytes() {
        return (long) docFreqs.length * Integer.BYTES + (long) termFreqs.length * Long.BYTES;
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
     * Builds the persisted binary file.
     *
     * @param path target temporary path
     * @param field indexed field
     * @param vocabSize vocabulary size
     * @param docCount field document count
     * @param totalTermFreq field total token count
     * @param terms merged field terms
     * @throws IOException if writing fails
     */
    private static void buildFile(
        final Path path,
        final String field,
        final int vocabSize,
        final int docCount,
        final long totalTermFreq,
        final Terms terms
    ) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW));
             DataOutputStream out = new DataOutputStream(os)) {

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeUtf8(out, field);
            out.writeInt(vocabSize);
            out.writeInt(docCount);
            out.writeLong(totalTermFreq);

            TermsEnum te = terms.iterator();
            BytesRef term;
            int seen = 0;
            while ((term = te.next()) != null) {
                final int df = te.docFreq();
                if (df < 0) {
                    throw new IOException("Negative docFreq for term in field '" + field + "'");
                }
                out.writeInt(df);
                seen++;
            }
            if (seen != vocabSize) {
                throw new IOException(
                    "Vocabulary size changed during stats build for field '" + field +
                    "': header=" + vocabSize + ", seen=" + seen
                );
            }

            te = terms.iterator();
            seen = 0;
            while ((term = te.next()) != null) {
                final long tf = te.totalTermFreq();
                if (tf < 0L) {
                    throw new IOException(
                        "Term frequencies are unavailable for field '" + field + "'; cannot build FieldStats"
                    );
                }
                out.writeLong(tf);
                seen++;
            }
            if (seen != vocabSize) {
                throw new IOException(
                    "Vocabulary size changed during stats build for field '" + field +
                    "': header=" + vocabSize + ", seen=" + seen
                );
            }
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
}