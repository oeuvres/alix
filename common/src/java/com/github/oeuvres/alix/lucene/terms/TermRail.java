package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

import java.io.Closeable;
import java.io.IOException;
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
import java.util.Arrays;
import java.util.Objects;

/**
 * Forward positional index for one field of a frozen Lucene directory.
 * <p>
 * Maps each {@code (docId, position)} to the {@code termId} assigned by a {@link TermLexicon}
 * built from the same snapshot. The underlying structure is a CSR (Compressed Sparse Row) layout
 * persisted in two files:
 * </p>
 * <ul>
 *   <li><b>{@code <field>.rail.dat}</b> — flat native-endian {@code int[]} of term ids,
 *       concatenated in document order. For document {@code d}, token positions
 *       {@code [0 .. docLength(d))} map to
 *       {@code dat[off.get(d) .. off.get(d) + docLength(d))}.</li>
 *   <li><b>{@code <field>.rail.off}</b> — native-endian {@code int[docCount + 1]} offsets
 *       (as int indices, not byte offsets) into the {@code .dat} array.
 *       The token count of document {@code d} is {@code off.get(d+1) - off.get(d)}.</li>
 * </ul>
 * <p>
 * Both files are memory-mapped as {@link MappedByteBuffer} in native byte order.
 * The intended access pattern is a sequential sliding-window scan for co-occurrence extraction,
 * which benefits from OS read-ahead on the mapped pages.
 * </p>
 * <p>
 * A position that has no term id (e.g. a gap left by a position increment &gt; 1 in the analyzer)
 * is stored as {@value #NO_TERM}. Callers must check for this sentinel when scanning.
 * </p>
 * <p>
 * This class implements {@link Closeable}. Closing attempts to release the mapped regions
 * immediately via {@link TermLexicon#unmap(MappedByteBuffer)}; if that fails, buffers are
 * left for garbage collection.
 * </p>
 *
 * @see TermLexicon
 */
public final class TermRail implements Closeable {

    /** Sentinel value stored at positions that carry no term (analyzer gaps). */
    public static final int NO_TERM = -1;

    /** Lucene directory that contains both the index and the {@code <field>.rail.*} files. */
    private final Path indexDir;

    /** Indexed field for which this rail was built. */
    private final String field;

    /**
     * Memory-mapped flat array of term ids in native byte order.
     * Kept as a field for unmapping in {@link #close()}.
     */
    private final MappedByteBuffer datBuf;

    /**
     * {@link IntBuffer} view over {@link #datBuf} for direct int-indexed access
     * without byte arithmetic. This is the buffer the sliding-window scan reads from.
     */
    private final IntBuffer dat;

    /**
     * Memory-mapped document offsets in native byte order.
     * Kept as a field for unmapping in {@link #close()}.
     */
    private final MappedByteBuffer offBuf;

    /**
     * {@link IntBuffer} view over {@link #offBuf}.
     * <p>
     * Capacity is {@code docCount + 1}. For document {@code d}, the term ids
     * span {@code dat[off.get(d) .. off.get(d+1))}.
     * </p>
     */
    private final IntBuffer off;

    /** Number of documents in the index snapshot from which this rail was built. */
    private final int docCount;

    /** Total number of token positions across all documents. */
    private final int totalPositions;

    /** Maximum tolerated mtime difference between the two rail files at open time, in milliseconds. */
    private static final long MTIME_TOLERANCE_MS = 5_000L;

    /** Write buffer capacity in number of ints. */
    private static final int BUF_INTS = 8192;

    /**
     * Creates an opened rail backed by memory-mapped buffers.
     *
     * @param indexDir       Lucene directory containing the rail files
     * @param field          indexed field name
     * @param datBuf         memory-mapped term-id buffer (kept for unmapping)
     * @param dat            {@link IntBuffer} view over {@code datBuf}
     * @param offBuf         memory-mapped offsets buffer (kept for unmapping)
     * @param off            {@link IntBuffer} view over {@code offBuf}
     * @param docCount       number of documents
     * @param totalPositions total token positions across all documents
     */
    private TermRail(
        final Path indexDir,
        final String field,
        final MappedByteBuffer datBuf,
        final IntBuffer dat,
        final MappedByteBuffer offBuf,
        final IntBuffer off,
        final int docCount,
        final int totalPositions
    ) {
        this.indexDir = indexDir;
        this.field = field;
        this.datBuf = datBuf;
        this.dat = dat;
        this.offBuf = offBuf;
        this.off = off;
        this.docCount = docCount;
        this.totalPositions = totalPositions;
    }

    /**
     * Releases the memory-mapped regions.
     * <p>
     * Delegates to {@link TermLexicon#unmap(MappedByteBuffer)} for best-effort immediate release.
     * After close, behaviour of read accessors is undefined. The caller must not use the
     * instance after closing.
     * </p>
     */
    @Override
    public void close() {
        TermLexicon.unmap(datBuf);
        TermLexicon.unmap(offBuf);
    }

    // ── static existence check ──────────────────────────────────────────

    /**
     * Returns {@code true} if the two persisted files for {@code field} exist as regular files.
     * <p>
     * Cheap presence test only — does not validate sizes, modification times, or contents.
     * </p>
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code true} if both {@code .rail.dat} and {@code .rail.off} are present
     * @throws NullPointerException if either argument is null
     */
    public static boolean exists(final Path indexDir, final String field) {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(datPath(indexDir, field))
            && Files.isRegularFile(offPath(indexDir, field));
    }

    // ── write ───────────────────────────────────────────────────────────

    /**
     * Builds the rail files for one field from an already opened snapshot reader and its lexicon.
     * <p>
     * For each document in {@code reader}, the stored term vectors with positions are read,
     * inverted into a position-to-termId array, and written sequentially to the {@code .dat} file.
     * Documents without term vectors for the field produce a zero-length segment (no positions).
     * </p>
     * <p>
     * The index <b>must</b> have been built with term vectors and positions stored for the field
     * ({@code FieldType.setStoreTermVectors(true)}, {@code setStoreTermVectorPositions(true)}).
     * </p>
     * <p>
     * Stale temporary files from a previous crashed write are silently cleaned up.
     * If a final target file already exists, an exception is thrown.
     * On failure, both temporary and any already-moved final files are cleaned up.
     * </p>
     *
     * @param indexDir Lucene directory that will receive the {@code <field>.rail.*} files
     * @param reader   snapshot reader; must have term vectors with positions for the field
     * @param field    indexed field name
     * @param lexicon  opened lexicon for the same field and snapshot, used to map terms to ids
     * @throws IOException              if term vectors are missing, a final file already exists, or writing fails
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if a term found in vectors is absent from the lexicon
     */
    public static void write(
        final Path indexDir,
        final IndexReader reader,
        final String field,
        final TermLexicon lexicon
    ) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(lexicon, "lexicon");

        final Path datFinal = datPath(indexDir, field);
        final Path offFinal = offPath(indexDir, field);

        ensureAbsent(datFinal);
        ensureAbsent(offFinal);

        final Path datTmp = tmpPath(datFinal);
        final Path offTmp = tmpPath(offFinal);

        deleteIfExists(datTmp);
        deleteIfExists(offTmp);

        try {
            buildFiles(reader, field, lexicon, datTmp, offTmp);
            moveTemp(datTmp, datFinal);
            moveTemp(offTmp, offFinal);
        } catch (IOException | RuntimeException e) {
            deleteIfExists(datTmp);
            deleteIfExists(offTmp);
            deleteIfExists(datFinal);
            deleteIfExists(offFinal);
            throw e;
        }
    }

    // ── open ────────────────────────────────────────────────────────────

    /**
     * Opens the rail for one field from a frozen Lucene directory.
     * <p>
     * The returned instance holds memory-mapped file handles and <b>should</b> be closed
     * when no longer needed, typically via try-with-resources.
     * </p>
     * <p>
     * On open, the following consistency checks are performed:
     * </p>
     * <ul>
     *   <li>Both files must exist as regular files.</li>
     *   <li>File modification times must be within {@value #MTIME_TOLERANCE_MS} ms of each other.</li>
     *   <li>Both file sizes must be multiples of 4 bytes.</li>
     *   <li>The offsets file must contain at least 2 entries (at least one document).</li>
     *   <li>The first offset must be 0 and the last must equal the data int count.</li>
     * </ul>
     *
     * @param indexDir Lucene directory that contains the index and the rail files
     * @param field    indexed field name
     * @return opened rail; caller should close when done
     * @throws IOException          if a file is missing, sizes are inconsistent, or mtimes diverge
     * @throws NullPointerException if either argument is null
     */
    public static TermRail open(final Path indexDir, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");

        final Path datPath = datPath(indexDir, field);
        final Path offPath = offPath(indexDir, field);

        ensureRegularFile(datPath);
        ensureRegularFile(offPath);
        checkMtimeCoherence(datPath, offPath);

        final MappedByteBuffer datMapped = mapReadOnly(datPath);
        datMapped.order(ByteOrder.nativeOrder());
        final MappedByteBuffer offMapped = mapReadOnly(offPath);
        offMapped.order(ByteOrder.nativeOrder());

        try {
            if ((datMapped.remaining() & 3) != 0) {
                throw new IOException("Invalid rail data file (size not a multiple of 4 bytes): " + datPath);
            }
            if ((offMapped.remaining() & 3) != 0) {
                throw new IOException("Invalid rail offsets file (size not a multiple of 4 bytes): " + offPath);
            }

            final IntBuffer dat = datMapped.asIntBuffer();
            final IntBuffer off = offMapped.asIntBuffer();

            if (off.capacity() < 2) {
                throw new IOException("Invalid rail offsets file (need at least 2 entries): " + offPath);
            }

            final int first = off.get(0);
            final int last = off.get(off.capacity() - 1);
            if (first != 0) {
                throw new IOException("Invalid rail offsets, off[0] != 0: " + offPath);
            }
            if (last != dat.capacity()) {
                throw new IOException("Rail offsets/data mismatch: last offset=" + last
                    + ", data int count=" + dat.capacity());
            }

            final int docCount = off.capacity() - 1;
            final int totalPositions = dat.capacity();

            return new TermRail(indexDir, field, datMapped, dat, offMapped, off, docCount, totalPositions);

        } catch (IOException | RuntimeException e) {
            TermLexicon.unmap(datMapped);
            TermLexicon.unmap(offMapped);
            throw e;
        }
    }

    // ── accessors ───────────────────────────────────────────────────────

    /**
     * Returns the Lucene directory from which this rail was opened.
     *
     * @return Lucene directory path, never null
     */
    public Path indexDir() {
        return indexDir;
    }

    /**
     * Returns the indexed field name covered by this rail.
     *
     * @return field name, never null
     */
    public String field() {
        return field;
    }

    /**
     * Returns the number of documents in the rail.
     *
     * @return document count (always &gt; 0 for a valid rail)
     */
    public int docCount() {
        return docCount;
    }

    /**
     * Returns the total number of token positions across all documents.
     *
     * @return total positions
     */
    public int totalPositions() {
        return totalPositions;
    }

    /**
     * Returns the int index into the data array where document {@code docId} begins.
     * <p>
     * This is a position in the {@link IntBuffer} returned by {@link #datBuffer()},
     * not a byte offset. Use with {@code datBuffer().get(docOffset(d) + position)}.
     * </p>
     *
     * @param docId document id in {@code [0, docCount)}
     * @return int index into the data buffer
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docOffset(final int docId) {
        checkDocId(docId);
        return off.get(docId);
    }

    /**
     * Returns the number of token positions in document {@code docId}.
     *
     * @param docId document id in {@code [0, docCount)}
     * @return number of token positions (may be 0 if the document had no term vectors for this field)
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docLength(final int docId) {
        checkDocId(docId);
        return off.get(docId + 1) - off.get(docId);
    }

    /**
     * Returns the term id at a given position within a document.
     * <p>
     * May return {@value #NO_TERM} for positions that correspond to analyzer gaps
     * (position increments &gt; 1).
     * </p>
     *
     * @param docId    document id in {@code [0, docCount)}
     * @param position token position in {@code [0, docLength(docId))}
     * @return term id, or {@value #NO_TERM} for gap positions
     * @throws IllegalArgumentException if {@code docId} or {@code position} is out of range
     */
    public int termId(final int docId, final int position) {
        checkDocId(docId);
        final int base = off.get(docId);
        final int docLen = off.get(docId + 1) - base;
        if (position < 0 || position >= docLen) {
            throw new IllegalArgumentException(
                "position out of range: " + position + " (docLength=" + docLen + ", docId=" + docId + ")");
        }
        return dat.get(base + position);
    }

    /**
     * Returns the underlying data buffer for direct bulk access.
     * <p>
     * Intended for the co-occurrence sliding-window scan, where per-element method calls
     * through {@link #termId(int, int)} would add overhead. The caller reads with
     * {@code datBuffer().get(index)}. Use {@link #docOffset(int)} and {@link #docLength(int)}
     * to locate each document's slice.
     * </p>
     * <p>
     * The returned buffer is a read-only view. It is valid only while this {@code TermRail} is open.
     * </p>
     *
     * @return read-only {@link IntBuffer} over the flat term-id array
     */
    public IntBuffer datBuffer() {
        return dat.asReadOnlyBuffer();
    }

    // ── internal: validation ────────────────────────────────────────────

    /**
     * Checks that a document id falls within the valid range {@code [0, docCount)}.
     *
     * @param docId document id to validate
     * @throws IllegalArgumentException if the id is negative or &ge; {@link #docCount}
     */
    private void checkDocId(final int docId) {
        if (docId < 0 || docId >= docCount) {
            throw new IllegalArgumentException(
                "docId out of range: " + docId + " (docCount=" + docCount + ")");
        }
    }

    // ── internal: build ─────────────────────────────────────────────────

    /**
     * Builds the two persisted files by reading term vectors with positions from every document.
     * <p>
     * For each document, the term vectors are inverted into a dense {@code int[maxPosition + 1]}
     * array where each slot holds the term id at that position, or {@value #NO_TERM} for gaps.
     * The array is then flushed sequentially to the {@code .dat} file.
     * </p>
     * <p>
     * Offsets stored in {@code .off} are <b>int indices</b> (not byte offsets) so that the
     * {@link IntBuffer} view can be used directly at read time without division.
     * </p>
     *
     * @param reader  snapshot reader with term vectors
     * @param field   indexed field name
     * @param lexicon opened lexicon for term-to-id mapping
     * @param datPath target path for the flat term-id array
     * @param offPath target path for the document offsets
     * @throws IOException if term vectors are missing or writing fails
     */
    private static void buildFiles(
        final IndexReader reader,
        final String field,
        final TermLexicon lexicon,
        final Path datPath,
        final Path offPath
    ) throws IOException {
        final int maxDoc = reader.maxDoc();
        final ByteBuffer datBuf = ByteBuffer.allocate(BUF_INTS * 4).order(ByteOrder.nativeOrder());
        final ByteBuffer offBuf = ByteBuffer.allocate(BUF_INTS * 4).order(ByteOrder.nativeOrder());

        try (FileChannel datCh = FileChannel.open(datPath,
                 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             FileChannel offCh = FileChannel.open(offPath,
                 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {

            int intPos = 0;  // running int index (not byte offset)
            offBuf.putInt(0); // first doc starts at int index 0

            for (int docId = 0; docId < maxDoc; docId++) {
                final Terms termVector = reader.termVectors().get(docId, field);

                if (termVector == null) {
                    // Document has no term vector for this field; zero-length segment
                    flushInt(offBuf, offCh, intPos);
                    continue;
                }

                // Pass 1: find max position to size the dense array
                int maxPos = -1;
                TermsEnum te = termVector.iterator();
                while (te.next() != null) {
                    final PostingsEnum pe = te.postings(null, PostingsEnum.POSITIONS);
                    pe.nextDoc();
                    for (int i = 0; i < pe.freq(); i++) {
                        final int pos = pe.nextPosition();
                        if (pos > maxPos) maxPos = pos;
                    }
                }

                if (maxPos < 0) {
                    // Term vector present but no positions
                    flushInt(offBuf, offCh, intPos);
                    continue;
                }

                // Pass 2: fill dense array
                final int docLen = maxPos + 1;
                final int[] positions = new int[docLen];
                Arrays.fill(positions, NO_TERM);

                te = termVector.iterator();
                BytesRef termBytes;
                while ((termBytes = te.next()) != null) {
                    final int termId = lexicon.id(termBytes);
                    if (termId < 0) {
                        throw new IllegalArgumentException(
                            "Term from vector absent in lexicon: " + termBytes.utf8ToString()
                            + " (docId=" + docId + ")");
                    }
                    final PostingsEnum pe = te.postings(null, PostingsEnum.POSITIONS);
                    pe.nextDoc();
                    for (int i = 0; i < pe.freq(); i++) {
                        positions[pe.nextPosition()] = termId;
                    }
                }

                // Write dense array to dat
                for (int p = 0; p < docLen; p++) {
                    if (!datBuf.hasRemaining()) {
                        datBuf.flip();
                        datCh.write(datBuf);
                        datBuf.clear();
                    }
                    datBuf.putInt(positions[p]);
                }
                intPos += docLen;

                // Write offset for next doc
                flushInt(offBuf, offCh, intPos);
            }

            // Flush remaining buffers
            if (datBuf.position() > 0) {
                datBuf.flip();
                datCh.write(datBuf);
            }
            if (offBuf.position() > 0) {
                offBuf.flip();
                offCh.write(offBuf);
            }
        }
    }

    /**
     * Writes one int to a buffered byte buffer, flushing to the channel first if the buffer is full.
     *
     * @param buf   native-endian byte buffer used as write buffer
     * @param ch    target file channel to flush into
     * @param value the int value to write
     * @throws IOException if the channel write fails
     */
    private static void flushInt(final ByteBuffer buf, final FileChannel ch, final int value) throws IOException {
        if (!buf.hasRemaining()) {
            buf.flip();
            ch.write(buf);
            buf.clear();
        }
        buf.putInt(value);
    }

    // ── internal: mmap utility ──────────────────────────────────────────

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

    // ── internal: path resolution ───────────────────────────────────────

    /**
     * Returns the path of the flat term-id data file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <indexDir>/<field>.rail.dat}
     */
    private static Path datPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".rail.dat");
    }

    /**
     * Returns the path of the document-offset file for one field.
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code <indexDir>/<field>.rail.off}
     */
    private static Path offPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".rail.off");
    }

    /**
     * Returns a temporary sibling path used during atomic write.
     *
     * @param path final target path
     * @return sibling path with {@code .tmp} suffix
     */
    private static Path tmpPath(final Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".tmp");
    }

    // ── internal: file operations ───────────────────────────────────────

    /**
     * Atomically moves a temporary file to its final location.
     * Falls back to a plain move if the filesystem does not support atomic moves.
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
     * Asserts that a path does not already exist.
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
     * Checks that the modification times of the given files are within
     * {@value #MTIME_TOLERANCE_MS} ms of each other, as a guard against mixed file versions.
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
                "Rail file mtimes differ by " + (max - min) + "ms; possible partial copy or mixed versions");
        }
    }
}
