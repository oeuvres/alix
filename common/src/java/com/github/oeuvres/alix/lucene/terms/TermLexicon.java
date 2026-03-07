package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.PositiveIntOutputs;
import org.apache.lucene.util.fst.Util;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static java.lang.Math.toIntExact;

/**
 * Immutable lookup table for one indexed field of one frozen Lucene directory.
 * <p>
 * The lexicon is stored in three files located directly in the Lucene directory:
 * </p>
 * <ul>
 *   <li><b>{@code &lt;field&gt;.terms.fst}</b>: exact lookup {@code term -> termId}</li>
 *   <li><b>{@code &lt;field&gt;.terms.dat}</b>: concatenated UTF-8 bytes of all terms in {@code termId} order</li>
 *   <li><b>{@code &lt;field&gt;.terms.off}</b>: big-endian {@code long} offsets into {@code .dat}</li>
 * </ul>
 * <p>
 * Term ids are dense and stable for the frozen snapshot from which the lexicon was built.
 * The id assignment is the lexicographic iteration order returned by Lucene's merged
 * {@link TermsEnum} for the field.
 * </p>
 * <p>
 * This class exposes only two lifecycle operations:
 * </p>
 * <ul>
 *   <li>{@link #write(Path, String)} or {@link #write(Path, IndexReader, String)} create the three files once</li>
 *   <li>{@link #open(Path, String)} opens the persisted lexicon for lookup</li>
 * </ul>
 * <p>
 * The string-based lookup assumes that the caller already provides the field's canonical indexed form.
 * No analysis, normalization, stemming or lower-casing is applied here.
 * </p>
 */
public final class TermLexicon {
    /** Lucene directory that contains both the index and the {@code <field>.terms.*} files. */
    private final Path indexDir;

    /** Indexed field for which this lexicon was built. */
    private final String field;

    /** Exact immutable mapping from UTF-8 term bytes to dense term ids. */
    private final FST<Long> fst;

    /** Memory-mapped concatenation of all term bytes in term-id order. */
    private final ByteBuffer dat;

    /**
     * Memory-mapped offsets into {@link #dat}.
     * <p>
     * The buffer length is {@code vocabSize + 1}. For a term id {@code i},
     * the term bytes are stored in {@code dat[off[i] .. off[i+1])}.
     * </p>
     */
    private final LongBuffer off;

    /** Number of distinct terms in the field. */
    private final int vocabSize;

    /** Maximum tolerated difference between the mtimes of the three lexicon files at open time. */
    private static final long MTIME_TOLERANCE_MS = 5_000L;

    /** Number of offsets checked at the head and tail of {@code .off} for a quick monotonicity test. */
    private static final int MONO_CHECK = 1024;

    /** Reused UTF-8 encoder scratch for {@link #id(String)} to avoid one allocation per call. */
    private static final ThreadLocal<BytesRefBuilder> TL_TERM_BYTES =
        ThreadLocal.withInitial(BytesRefBuilder::new);

    /**
     * Creates an opened lexicon.
     *
     * @param indexDir Lucene directory that contains the lexicon files
     * @param field indexed field name
     * @param fst exact mapping {@code term -> termId}
     * @param dat memory-mapped term bytes file
     * @param off memory-mapped offsets file
     */
    private TermLexicon(
        final Path indexDir,
        final String field,
        final FST<Long> fst,
        final ByteBuffer dat,
        final LongBuffer off
    ) {
        this.indexDir = indexDir;
        this.field = field;
        this.fst = fst;
        this.dat = dat.asReadOnlyBuffer();
        this.off = off.asReadOnlyBuffer();
        this.vocabSize = off.capacity() - 1;
    }

    /**
     * Builds the lexicon files for one field using the latest committed state of the Lucene directory.
     *
     * @param indexDir Lucene directory that contains the frozen index
     * @param field indexed field name
     * @throws IOException if the index cannot be opened, if the field has no terms, or if one target file already exists
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
     * Builds the lexicon files for one field from an already opened snapshot reader.
     * <p>
     * This overload is useful when the caller already controls which Lucene snapshot is being read,
     * for example via {@code DirectoryReader.open(IndexCommit)}.
     * </p>
     *
     * @param indexDir Lucene directory that will receive the {@code <field>.terms.*} files
     * @param reader snapshot reader that defines the term universe and its lexicographic order
     * @param field indexed field name
     * @throws IOException if the field has no terms, if a target file already exists, or if writing fails
     */
    public static void write(final Path indexDir, final IndexReader reader, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");

        final Path fstPath = fstPath(indexDir, field);
        final Path datPath = datPath(indexDir, field);
        final Path offPath = offPath(indexDir, field);

        ensureAbsent(fstPath);
        ensureAbsent(datPath);
        ensureAbsent(offPath);

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final Path fstTmp = tmpPath(fstPath);
        final Path datTmp = tmpPath(datPath);
        final Path offTmp = tmpPath(offPath);
        ensureAbsent(fstTmp);
        ensureAbsent(datTmp);
        ensureAbsent(offTmp);

        try {
            buildFiles(terms, fstTmp, datTmp, offTmp);
            moveTemp(datTmp, datPath);
            moveTemp(offTmp, offPath);
            moveTemp(fstTmp, fstPath);
        } catch (IOException | RuntimeException e) {
            deleteIfExists(datTmp);
            deleteIfExists(offTmp);
            deleteIfExists(fstTmp);
            throw e;
        }
    }

    /**
     * Opens the lexicon for one field from a frozen Lucene directory.
     *
     * @param indexDir Lucene directory that contains the index and the lexicon files
     * @param field indexed field name
     * @return opened lexicon
     * @throws IOException if a file is missing, inconsistent or unreadable
     */
    public static TermLexicon open(final Path indexDir, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");

        final Path fstPath = fstPath(indexDir, field);
        final Path datPath = datPath(indexDir, field);
        final Path offPath = offPath(indexDir, field);

        ensureRegularFile(fstPath);
        ensureRegularFile(datPath);
        ensureRegularFile(offPath);
        checkMtimeCoherence(fstPath, datPath, offPath);

        final ByteBuffer dat = mapReadOnly(datPath);
        final ByteBuffer offBytes = mapReadOnly(offPath).order(ByteOrder.BIG_ENDIAN);
        if ((offBytes.remaining() & 7) != 0) {
            throw new IOException("Invalid offsets file (size is not a multiple of 8 bytes): " + offPath);
        }
        final LongBuffer off = offBytes.asLongBuffer();
        if (off.capacity() < 2) {
            throw new IOException("Invalid offsets file (need at least 2 offsets): " + offPath);
        }

        final long datLength = dat.capacity();
        final long first = off.get(0);
        final long last = off.get(off.capacity() - 1);
        if (first != 0L) {
            throw new IOException("Invalid offsets file, off[0] != 0: " + offPath);
        }
        if (last != datLength) {
            throw new IOException("Offsets/data mismatch for field '" + field + "': last offset=" + last +
                ", data length=" + datLength);
        }
        monotonicityCheck(off, offPath);

        final PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
        final FST<Long> fst = FST.read(fstPath, outputs);
        return new TermLexicon(indexDir, field, fst, dat, off);
    }

    /**
     * Returns the Lucene directory from which this lexicon was opened.
     *
     * @return Lucene directory path
     */
    public Path indexDir() {
        return indexDir;
    }

    /**
     * Returns the field name covered by this lexicon.
     *
     * @return field name
     */
    public String field() {
        return field;
    }

    /**
     * Returns the number of distinct terms in the field.
     *
     * @return vocabulary size
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Looks up a canonical indexed term represented as a Java string.
     *
     * @param term canonical indexed term form
     * @return dense term id, or {@code -1} if the term is absent
     * @throws IOException if the FST cannot be read
     */
    public int id(final String term) throws IOException {
        Objects.requireNonNull(term, "term");
        final BytesRefBuilder bytes = TL_TERM_BYTES.get();
        bytes.copyChars(term);
        return id(bytes.get());
    }

    /**
     * Looks up a canonical indexed term represented as UTF-8 bytes.
     *
     * @param term canonical indexed term bytes
     * @return dense term id, or {@code -1} if the term is absent
     * @throws IOException if the FST cannot be read
     */
    public int id(final BytesRef term) throws IOException {
        Objects.requireNonNull(term, "term");
        final Long value = Util.get(fst, term);
        return (value == null) ? -1 : toIntExact(value);
    }

    /**
     * Copies the bytes of one term into a caller-provided reusable buffer.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @param reuse destination buffer that will receive the term bytes
     * @return {@code reuse.get()} after copy
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public BytesRef termBytes(final int termId, final BytesRefBuilder reuse) {
        checkTermId(termId);
        Objects.requireNonNull(reuse, "reuse");

        final long start = off.get(termId);
        final long end = off.get(termId + 1);
        final int length = toIntExact(end - start);

        reuse.grow(length);
        final byte[] dst = reuse.bytes();

        final ByteBuffer dup = dat.duplicate();
        dup.position(toIntExact(start));
        dup.limit(toIntExact(end));
        dup.get(dst, 0, length);

        reuse.setLength(length);
        return reuse.get();
    }

    /**
     * Returns the term string associated with one dense term id.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return decoded UTF-8 term string
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public String term(final int termId) {
        final BytesRefBuilder reuse = new BytesRefBuilder();
        return termBytes(termId, reuse).utf8ToString();
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
                "termId out of range: " + termId + " (vocabSize=" + vocabSize + ")"
            );
        }
    }

    /**
     * Builds the three persisted files from the field's merged term dictionary.
     *
     * @param terms merged field terms
     * @param fstPath target FST path
     * @param datPath target term-bytes path
     * @param offPath target offsets path
     * @throws IOException if writing or FST compilation fails
     */
    private static void buildFiles(
        final Terms terms,
        final Path fstPath,
        final Path datPath,
        final Path offPath
    ) throws IOException {
        final PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
        final FSTCompiler<Long> compiler = new FSTCompiler.Builder<Long>(FST.INPUT_TYPE.BYTE1, outputs).build();
        final IntsRefBuilder ints = new IntsRefBuilder();

        long id = 0L;
        long datPos = 0L;

        try (OutputStream datOs = new BufferedOutputStream(Files.newOutputStream(datPath, StandardOpenOption.CREATE_NEW));
             DataOutputStream offOut = new DataOutputStream(
                 new BufferedOutputStream(Files.newOutputStream(offPath, StandardOpenOption.CREATE_NEW))
             )) {

            offOut.writeLong(0L);

            final TermsEnum te = terms.iterator();
            BytesRef term;
            while ((term = te.next()) != null) {
                compiler.add(Util.toIntsRef(term, ints), id++);

                datOs.write(term.bytes, term.offset, term.length);
                datPos += term.length;
                offOut.writeLong(datPos);
            }
        }

        final FST.FSTMetadata<Long> metadata = compiler.compile();
        if (metadata == null) {
            throw new IOException("Field has no terms; cannot build lexicon");
        }
        final FST<Long> fst = FST.fromFSTReader(metadata, compiler.getFSTReader());
        fst.save(fstPath);
    }

    /**
     * Returns the path of the FST file for one field.
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code <field>.terms.fst}
     */
    private static Path fstPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".terms.fst");
    }

    /**
     * Returns the path of the concatenated term-bytes file for one field.
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code <field>.terms.dat}
     */
    private static Path datPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".terms.dat");
    }

    /**
     * Returns the path of the offsets file for one field.
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code <field>.terms.off}
     */
    private static Path offPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".terms.off");
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
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
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
     * @throws IOException if the target already exists
     */
    private static void ensureAbsent(final Path path) throws IOException {
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    /**
     * Memory-maps one file in read-only mode.
     *
     * @param path file path
     * @return read-only memory-mapped byte buffer
     * @throws IOException if the file cannot be opened or mapped
     */
    private static ByteBuffer mapReadOnly(final Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0L, ch.size());
        }
    }

    /**
     * Performs a cheap startup check against mixed file copies by comparing file modification times.
     *
     * @param paths files that should have been produced together
     * @throws IOException if the modification times differ by more than the configured tolerance
     */
    private static void checkMtimeCoherence(final Path... paths) throws IOException {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Path path : paths) {
            final long t = Files.getLastModifiedTime(path).toMillis();
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        if ((max - min) > MTIME_TOLERANCE_MS) {
            throw new IOException(
                "Lexicon files mtimes differ by " + (max - min) + "ms; possible partial copy or mixed versions"
            );
        }
    }

    /**
     * Performs a bounded monotonicity check on the offsets file.
     * <p>
     * This is intentionally not a full scan. It is a cheap startup guard against obvious corruption.
     * </p>
     *
     * @param off mapped offsets buffer
     * @param offPath offsets file path, used only in error messages
     * @throws IOException if checked offsets are not monotonic
     */
    private static void monotonicityCheck(final LongBuffer off, final Path offPath) throws IOException {
        final int n = off.capacity();
        final int head = Math.min(MONO_CHECK, n);
        final int tailStart = Math.max(0, n - MONO_CHECK);

        long prev = off.get(0);
        for (int i = 1; i < head; i++) {
            final long cur = off.get(i);
            if (cur < prev) {
                throw new IOException("Invalid offsets file, offsets decrease at head index " + i + ": " + offPath);
            }
            prev = cur;
        }

        prev = off.get(tailStart);
        for (int i = tailStart + 1; i < n; i++) {
            final long cur = off.get(i);
            if (cur < prev) {
                throw new IOException("Invalid offsets file, offsets decrease at tail index " + i + ": " + offPath);
            }
            prev = cur;
        }
    }
}
