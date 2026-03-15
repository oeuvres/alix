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
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
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
 * The lexicon is persisted in three files located directly in the Lucene directory:
 * </p>
 * <ul>
 *   <li><b>{@code <field>.terms.fst}</b> — exact lookup {@code term → termId}</li>
 *   <li><b>{@code <field>.terms.dat}</b> — concatenated UTF-8 bytes of all terms in {@code termId} order</li>
 *   <li><b>{@code <field>.terms.off}</b> — native-endian {@code int} offsets into {@code .dat}</li>
 * </ul>
 * <p>
 * Term ids are dense and stable for the frozen snapshot from which the lexicon was built.
 * The id assignment follows the lexicographic iteration order returned by Lucene's merged
 * {@link TermsEnum} for the field.
 * </p>
 * <p>
 * This class implements {@link Closeable}. Closing attempts to release the memory-mapped
 * regions immediately via {@code sun.misc.Unsafe.invokeCleaner()}, the same mechanism used
 * by Lucene's {@code MMapDirectory}. If the reflective call fails (e.g. on a future JDK that
 * removes the entry point), the buffers are left for garbage collection — safe on Linux,
 * but may hold file locks on Windows until GC runs.
 * </p>
 * <p>
 * String lookup assumes that the caller provides the field's canonical indexed form.
 * No analysis, normalization, stemming or lower-casing is applied here.
 * </p>
 *
 * @see TermRail
 */
public final class TermLexicon implements Closeable {

    /** Lucene directory that contains both the index and the {@code <field>.terms.*} files. */
    private final Path indexDir;

    /** Indexed field for which this lexicon was built. */
    private final String field;

    /** Exact immutable mapping from UTF-8 term bytes to dense term ids. */
    private final FST<Long> fst;

    /** Memory-mapped concatenation of all term bytes in term-id order. */
    private final MappedByteBuffer datBuf;

    /**
     * Read-only view of the memory-mapped term bytes, used for slicing in {@link #termBytes}.
     */
    private final ByteBuffer dat;

    /**
     * Memory-mapped offsets into {@link #dat}, native-endian int32s viewed as {@link IntBuffer}.
     * <p>
     * Capacity is {@code vocabSize + 1}. For a term id {@code i},
     * the term bytes occupy {@code dat[off.get(i) .. off.get(i+1))}.
     * </p>
     */
    private final MappedByteBuffer offBuf;

    /** IntBuffer view over {@link #offBuf} for direct int access without byte arithmetic. */
    private final IntBuffer off;

    /** Number of distinct terms in the field. */
    private final int vocabSize;

    /** Maximum tolerated mtime difference between the three lexicon files at open time, in milliseconds. */
    private static final long MTIME_TOLERANCE_MS = 5_000L;

    /** Number of offsets checked at the head and tail of {@code .off} for a quick monotonicity test. */
    private static final int MONO_CHECK = 1024;

    /** Per-thread reusable scratch buffer for {@link #id(String)} to avoid allocation per call. */
    private static final ThreadLocal<BytesRefBuilder> TL_TERM_BYTES =
        ThreadLocal.withInitial(BytesRefBuilder::new);

    /** Per-thread reusable scratch buffer for {@link #term(int)} to avoid allocation per call. */
    private static final ThreadLocal<BytesRefBuilder> TL_TERM_STRING =
        ThreadLocal.withInitial(BytesRefBuilder::new);

    /** Capacity of the write buffer for the offset file, in number of ints (each 4 bytes). */
    private static final int OFF_BUF_INTS = 4096;

    /**
     * {@code MethodHandle} for {@code sun.misc.Unsafe.invokeCleaner(ByteBuffer)}.
     * Resolved once at class load; {@code null} if the JDK does not expose it.
     * This is the same approach Lucene uses in {@code MMapDirectory}.
     */
    private static final MethodHandle INVOKE_CLEANER;

    static {
        MethodHandle mh = null;
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            final Object unsafe = f.get(null);
            mh = MethodHandles.lookup()
                .findVirtual(unsafeClass, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class))
                .bindTo(unsafe);
        } catch (Exception e) {
            // Say something here?
        }
        INVOKE_CLEANER = mh;
    }

    /**
     * Creates an opened lexicon backed by memory-mapped buffers.
     *
     * @param indexDir Lucene directory containing the lexicon files
     * @param field    indexed field name
     * @param fst      compiled FST mapping term bytes to dense ids
     * @param datBuf   memory-mapped term-bytes buffer (kept for unmapping)
     * @param offBuf   memory-mapped offsets buffer (kept for unmapping)
     * @param off      {@link IntBuffer} view over {@code offBuf}
     */
    private TermLexicon(
        final Path indexDir,
        final String field,
        final FST<Long> fst,
        final MappedByteBuffer datBuf,
        final MappedByteBuffer offBuf,
        final IntBuffer off
    ) {
        this.indexDir = indexDir;
        this.field = field;
        this.fst = fst;
        this.datBuf = datBuf;
        this.dat = datBuf.asReadOnlyBuffer();
        this.offBuf = offBuf;
        this.off = off;
        this.vocabSize = off.capacity() - 1;
    }

    /**
     * Releases the memory-mapped regions.
     * <p>
     * Attempts immediate unmap via {@code sun.misc.Unsafe.invokeCleaner()}.
     * If that reflective path is unavailable, the buffers are abandoned to garbage collection.
     * A warning is logged on failure but no exception is thrown.
     * </p>
     * <p>
     * After close, behaviour of read accessors is undefined (likely {@link java.lang.InternalError}
     * on access to unmapped memory). The caller must not use the instance after closing.
     * </p>
     */
    @Override
    public void close() {
        unmap(datBuf);
        unmap(offBuf);
    }

    /**
     * Returns {@code true} if the three persisted files for {@code field} exist as regular files.
     * <p>
     * Cheap presence test only — does not validate file sizes, modification times,
     * or internal consistency.
     * </p>
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code true} if all three files ({@code .fst}, {@code .dat}, {@code .off}) are present
     * @throws NullPointerException if either argument is null
     */
    public static boolean exists(final Path indexDir, final String field) {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(fstPath(indexDir, field))
            && Files.isRegularFile(datPath(indexDir, field))
            && Files.isRegularFile(offPath(indexDir, field));
    }

    /**
     * Returns the indexed field name covered by this lexicon.
     *
     * @return field name, never null
     */
    public String field() {
        return field;
    }

    /**
     * Returns the in-memory heap usage of the loaded FST, in bytes.
     * <p>
     * This does not include the memory-mapped {@code .dat} and {@code .off} buffers,
     * which live outside the Java heap in OS-managed page cache.
     * </p>
     *
     * @return FST heap footprint in bytes
     */
    public long fstRamBytesUsed() {
        return fst.ramBytesUsed();
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as a Java string.
     * <p>
     * The string is encoded to UTF-8 using a per-thread scratch buffer,
     * then looked up in the FST. No analysis or normalization is applied.
     * </p>
     *
     * @param term canonical indexed term form, must match the analyzer output exactly
     * @return dense term id in {@code [0, vocabSize)}, or {@code -1} if the term is absent
     * @throws IOException          if the FST read fails
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final String term) throws IOException {
        Objects.requireNonNull(term, "term");
        final BytesRefBuilder bytes = TL_TERM_BYTES.get();
        bytes.copyChars(term);
        return id(bytes.get());
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as raw UTF-8 bytes.
     * <p>
     * This is the lower-level entry point; prefer {@link #id(String)} unless you already
     * hold a {@link BytesRef} from Lucene internals.
     * </p>
     *
     * @param term canonical indexed term as UTF-8 bytes
     * @return dense term id in {@code [0, vocabSize)}, or {@code -1} if the term is absent
     * @throws IOException          if the FST read fails
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final BytesRef term) throws IOException {
        Objects.requireNonNull(term, "term");
        final Long value = Util.get(fst, term);
        return (value == null) ? -1 : toIntExact(value);
    }
    
    /**
     * Returns the Lucene directory from which this lexicon was opened.
     *
     * @return Lucene directory path, never null
     */
    public Path indexDir() {
        return indexDir;
    }

    
    /**
     * Opens the lexicon for one field from a frozen Lucene directory.
     * <p>
     * The returned instance holds memory-mapped file handles and <b>should</b> be closed
     * when no longer needed, typically via try-with-resources. Closing releases the mapped
     * memory immediately on JDKs that support it; otherwise GC reclaims it.
     * </p>
     * <p>
     * On open, the following consistency checks are performed:
     * </p>
     * <ul>
     *   <li>All three files must exist as regular files.</li>
     *   <li>File modification times must be within {@value #MTIME_TOLERANCE_MS} ms of each other.</li>
     *   <li>The offsets file size must be a multiple of 4 bytes and contain at least 2 entries.</li>
     *   <li>The first offset must be 0 and the last must equal the data file size.</li>
     *   <li>A bounded monotonicity check is run on the first and last {@value #MONO_CHECK} offsets.</li>
     * </ul>
     *
     * @param indexDir Lucene directory that contains the index and the lexicon files
     * @param field    indexed field name
     * @return opened lexicon; caller should close when done
     * @throws IOException          if a file is missing, sizes are inconsistent, mtimes diverge,
     *                              offsets are corrupt, or the FST cannot be loaded
     * @throws NullPointerException if either argument is null
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
    
        final MappedByteBuffer datBuf = mapReadOnly(datPath);
        final MappedByteBuffer offByteBuf = mapReadOnly(offPath);
        offByteBuf.order(ByteOrder.nativeOrder());
    
        try {
            if ((offByteBuf.remaining() & 3) != 0) {
                throw new IOException("Invalid offsets file (size not a multiple of 4 bytes): " + offPath);
            }
            final IntBuffer off = offByteBuf.asIntBuffer();
            if (off.capacity() < 2) {
                throw new IOException("Invalid offsets file (need at least 2 offsets): " + offPath);
            }
    
            final int first = off.get(0);
            final int last = off.get(off.capacity() - 1);
            if (first != 0) {
                throw new IOException("Invalid offsets file, off[0] != 0: " + offPath);
            }
            if (last != datBuf.capacity()) {
                throw new IOException("Offsets/data mismatch for field '" + field
                    + "': last offset=" + last + ", data length=" + datBuf.capacity());
            }
            monotonicityCheck(off, offPath);
    
            final PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
            final FST<Long> fst = FST.read(fstPath, outputs);
    
            return new TermLexicon(indexDir, field, fst, datBuf, offByteBuf, off);
    
        } catch (IOException | RuntimeException e) {
            unmap(datBuf);
            unmap(offByteBuf);
            throw e;
        }
    }

    /**
     * Returns the term string for one dense term id.
     * <p>
     * Uses a per-thread scratch buffer internally. Suitable for moderate use
     * (e.g. resolving 50 term ids for display). For tight loops over the full
     * vocabulary, prefer {@link #termBytes(int, BytesRefBuilder)} with a caller-owned buffer.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return decoded UTF-8 term string, never null
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public String term(final int termId) {
        return termBytes(termId, TL_TERM_STRING.get()).utf8ToString();
    }

    /**
     * Copies the raw UTF-8 bytes of one term into a caller-provided reusable buffer.
     * <p>
     * This avoids allocation when called in a loop. The bytes are read directly
     * from the memory-mapped {@code .dat} buffer.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @param reuse  destination buffer that will receive the term bytes;
     *               grown automatically if needed
     * @return {@code reuse.get()} after the copy, valid until the next call on the same buffer
     * @throws IllegalArgumentException if {@code termId} is out of range
     * @throws NullPointerException     if {@code reuse} is null
     */
    public BytesRef termBytes(final int termId, final BytesRefBuilder reuse) {
        checkTermId(termId);
        Objects.requireNonNull(reuse, "reuse");

        final int start = off.get(termId);
        final int end = off.get(termId + 1);
        final int length = end - start;

        reuse.grow(length);
        final ByteBuffer dup = dat.duplicate();
        dup.position(start);
        dup.limit(end);
        dup.get(reuse.bytes(), 0, length);
        reuse.setLength(length);
        return reuse.get();
    }

    
    /**
     * Returns the number of distinct terms in the field.
     *
     * @return vocabulary size (always &gt; 0 for a valid lexicon)
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Builds the lexicon files for one field using the latest committed state of the Lucene directory.
     * <p>
     * Opens a {@link DirectoryReader} internally, builds the files, then closes the reader.
     * If the final target files already exist, an exception is thrown.
     * </p>
     *
     * @param indexDir Lucene directory that contains the frozen index
     * @param field    indexed field name
     * @throws IOException              if the index cannot be opened, the field has no terms,
     *                                  a target file already exists, or writing fails
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the field has no terms in the index
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
     * This overload is useful when the caller controls which Lucene snapshot is being read,
     * for example via {@code DirectoryReader.open(IndexCommit)}.
     * </p>
     * <p>
     * Stale temporary files from a previous crashed write are silently cleaned up before
     * writing begins. If a final target file already exists, an exception is thrown —
     * call {@link #exists(Path, String)} and delete manually if overwrite semantics are needed.
     * </p>
     * <p>
     * On failure, both temporary and any already-committed final files are cleaned up
     * to avoid leaving a partial file set.
     * </p>
     *
     * @param indexDir Lucene directory that will receive the {@code <field>.terms.*} files
     * @param reader   snapshot reader that defines the term universe and its lexicographic order
     * @param field    indexed field name
     * @throws IOException              if the field has no terms, a final target file already exists, or writing fails
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if the field has no terms in the reader
     */
    public static void write(final Path indexDir, final IndexReader reader, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
    
        final Path fstFinal = fstPath(indexDir, field);
        final Path datFinal = datPath(indexDir, field);
        final Path offFinal = offPath(indexDir, field);
    
        ensureAbsent(fstFinal);
        ensureAbsent(datFinal);
        ensureAbsent(offFinal);
    
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }
    
        final Path fstTmp = tmpPath(fstFinal);
        final Path datTmp = tmpPath(datFinal);
        final Path offTmp = tmpPath(offFinal);
    
        // Clean up stale temps from a previous crash
        deleteIfExists(fstTmp);
        deleteIfExists(datTmp);
        deleteIfExists(offTmp);
    
        try {
            buildFiles(terms, fstTmp, datTmp, offTmp);
            moveTemp(datTmp, datFinal);
            moveTemp(offTmp, offFinal);
            moveTemp(fstTmp, fstFinal);
        } catch (IOException | RuntimeException e) {
            deleteIfExists(fstTmp);
            deleteIfExists(datTmp);
            deleteIfExists(offTmp);
            deleteIfExists(fstFinal);
            deleteIfExists(datFinal);
            deleteIfExists(offFinal);
            throw e;
        }
    }
    
    /**
     * Builds the three persisted files from the field's merged term dictionary.
     * <p>
     * Iterates the {@link TermsEnum} in lexicographic order, assigning a dense id
     * to each term starting from 0. The FST, concatenated term bytes, and native-endian
     * offset array are written to the respective temporary paths.
     * </p>
     *
     * @param terms   merged terms for the field
     * @param fstPath target path for the compiled FST
     * @param datPath target path for the concatenated term bytes
     * @param offPath target path for the native-endian offsets
     * @throws IOException if writing or FST compilation fails, or if limits are exceeded
     */
    private static void buildFiles(
        final Terms terms,
        final Path fstPath,
        final Path datPath,
        final Path offPath
    ) throws IOException {
        final PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
        final FSTCompiler<Long> compiler =
            new FSTCompiler.Builder<Long>(FST.INPUT_TYPE.BYTE1, outputs).build();
        final IntsRefBuilder ints = new IntsRefBuilder();
    
        int id = 0;
        int datPos = 0;
    
        final ByteBuffer offBuf = ByteBuffer.allocate(OFF_BUF_INTS * 4).order(ByteOrder.nativeOrder());
    
        try (OutputStream datOs = new BufferedOutputStream(
                 Files.newOutputStream(datPath, StandardOpenOption.CREATE_NEW));
             FileChannel offCh = FileChannel.open(offPath,
                 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
    
            offBuf.putInt(0);
    
            final TermsEnum te = terms.iterator();
            BytesRef term;
            while ((term = te.next()) != null) {
                if (id == Integer.MAX_VALUE) {
                    throw new IOException("Too many terms for int term ids");
                }
                if (datPos > Integer.MAX_VALUE - term.length) {
                    throw new IOException("Term bytes exceed 2 GiB; 32-bit offsets insufficient");
                }
    
                compiler.add(Util.toIntsRef(term, ints), (long) id);
    
                datOs.write(term.bytes, term.offset, term.length);
                datPos += term.length;
    
                if (!offBuf.hasRemaining()) {
                    offBuf.flip();
                    offCh.write(offBuf);
                    offBuf.clear();
                }
                offBuf.putInt(datPos);
                id++;
            }
    
            if (offBuf.position() > 0) {
                offBuf.flip();
                offCh.write(offBuf);
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
     * Checks that the modification times of several files are within {@value #MTIME_TOLERANCE_MS} ms
     * of each other.
     * <p>
     * Cheap startup guard against opening a set of files produced by different write operations.
     * </p>
     *
     * @param paths files that should have been produced together
     * @throws IOException if the mtime spread exceeds the tolerance
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
                "Lexicon file mtimes differ by " + (max - min) + "ms; possible partial copy or mixed versions");
        }
    }

    /**
     * Checks that a term id falls within the valid range {@code [0, vocabSize)}.
     *
     * @param termId dense term id to validate
     * @throws IllegalArgumentException if the id is negative or &ge; {@link #vocabSize}
     */
    private void checkTermId(final int termId) {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException(
                "termId out of range: " + termId + " (vocabSize=" + vocabSize + ")");
        }
    }

    /**
     * Returns the path of the concatenated term-bytes file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <indexDir>/<field>.terms.dat}
     */
    private static Path datPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".terms.dat");
    }

    /**
     * Deletes a file if it exists, silently ignoring any failure.
     * Used for best-effort cleanup only.
     *
     * @param path path to delete
     */
    private static void deleteIfExists(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Asserts that a path does not already exist.
     * <p>
     * Used before writing to prevent accidental overwrite of existing lexicon files.
     * </p>
     *
     * @param path target path that must not exist
     * @throws FileAlreadyExistsException if the path already exists
     */
    private static void ensureAbsent(final Path path) throws IOException {
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    /**
     * Asserts that a path points to an existing regular file.
     *
     * @param path path to check
     * @throws NoSuchFileException if the file does not exist or is not a regular file
     */
    private static void ensureRegularFile(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /**
     * Returns the path of the FST file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <indexDir>/<field>.terms.fst}
     */
    private static Path fstPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".terms.fst");
    }
    
    /**
     * Memory-maps one file in read-only mode.
     *
     * @param path file path
     * @return read-only memory-mapped byte buffer covering the entire file
     * @throws IOException if the file cannot be opened or mapped
     */
    private static MappedByteBuffer mapReadOnly(final Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0L, ch.size());
        }
    }

    /**
     * Performs a bounded monotonicity check on the offsets buffer.
     * <p>
     * Only the first and last {@value #MONO_CHECK} entries are tested.
     * This is intentionally not a full scan — it is a cheap startup guard
     * against obvious corruption (truncated writes, mixed file versions).
     * </p>
     *
     * @param off     int view of the offsets buffer
     * @param offPath path to the offsets file, used only in error messages
     * @throws IOException if any checked pair of consecutive offsets is non-monotonic
     */
    private static void monotonicityCheck(final IntBuffer off, final Path offPath) throws IOException {
        final int n = off.capacity();
        final int head = Math.min(MONO_CHECK, n);
        final int tailStart = Math.max(0, n - MONO_CHECK);

        int prev = off.get(0);
        for (int i = 1; i < head; i++) {
            final int cur = off.get(i);
            if (cur < prev) {
                throw new IOException("Offsets decrease at head index " + i + ": " + offPath);
            }
            prev = cur;
        }

        prev = off.get(tailStart);
        for (int i = tailStart + 1; i < n; i++) {
            final int cur = off.get(i);
            if (cur < prev) {
                throw new IOException("Offsets decrease at tail index " + i + ": " + offPath);
            }
            prev = cur;
        }
    }

    /**
     * Atomically moves a temporary file to its final location.
     * <p>
     * Attempts {@link StandardCopyOption#ATOMIC_MOVE} first. If the filesystem does not
     * support atomic moves, falls back to a plain move.
     * </p>
     *
     * @param source temporary file path
     * @param target final file path
     * @throws IOException if the move fails even with fallback
     */
    private static void moveTemp(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target);
        }
    }
    
    /**
     * Returns the path of the offsets file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <indexDir>/<field>.terms.off}
     */
    private static Path offPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".terms.off");
    }

    /**
     * Returns a temporary sibling path used during atomic write.
     * <p>
     * The temporary path has the same name as the final target with a {@code .tmp} suffix appended.
     * </p>
     *
     * @param path final target path
     * @return sibling path with {@code .tmp} suffix
     */
    private static Path tmpPath(final Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".tmp");
    }

    /**
     * Attempts to unmap a {@link MappedByteBuffer} immediately using
     * {@code sun.misc.Unsafe.invokeCleaner()}.
     * <p>
     * This is a best-effort operation. If the reflective call is unavailable or fails,
     * a fine-level log message is emitted and the buffer is left for garbage collection.
     * </p>
     *
     * @param buf the mapped buffer to unmap, or {@code null} (no-op)
     */
    static void unmap(final MappedByteBuffer buf) {
        if (buf == null || INVOKE_CLEANER == null) {
            return;
        }
        try {
            INVOKE_CLEANER.invokeExact((ByteBuffer) buf);
        } catch (Throwable t) {
            // Say someting?
        }
    }
}
