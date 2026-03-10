package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
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
 * Forward positional rail for one Lucene field, built from postings.
 * <p>
 * This structure maps each {@code (docId, position)} to a single {@code termId},
 * where {@code termId} is assigned by a {@link TermLexicon} built from the same
 * index snapshot.
 * </p>
 *
 * <h2>Physical layout</h2>
 * <p>
 * The rail is persisted in two native-endian files:
 * </p>
 * <ul>
 *   <li><b>{@code <field>.rail.dat}</b> — flat {@code int[]} of term ids, concatenated
 *   document by document</li>
 *   <li><b>{@code <field>.rail.off}</b> — {@code int[docCount + 1]} offsets into the
 *   data array, expressed in <i>int indices</i>, not bytes</li>
 * </ul>
 * <p>
 * For document {@code d}, the slice is:
 * </p>
 * <pre>{@code
 * int base = off[d];
 * int len  = off[d + 1] - off[d];
 * int termIdAtPosP = dat[base + p];
 * }</pre>
 *
 * <h2>Index requirements</h2>
 * <p>
 * The source field must be indexed with positions in postings
 * ({@code IndexOptions.DOCS_AND_FREQS_AND_POSITIONS} or higher).
 * Term vectors are not used and are not required.
 * </p>
 *
 * <h2>Semantic contract</h2>
 * <p>
 * This rail stores <b>exactly one term id per position</b>.
 * It therefore supports:
 * </p>
 * <ul>
 *   <li>linear token streams with at most one token at each position</li>
 *   <li>position gaps, which are stored as {@link #NO_TERM}</li>
 * </ul>
 * <p>
 * It does <b>not</b> support stacked tokens at the same position
 * ({@code posInc == 0}, synonym stacks, lemma+surface stacks, token graphs collapsed
 * to one position, etc.). If multiple postings attempt to write to the same
 * {@code (docId, position)} slot, build fails fast with an exception.
 * </p>
 *
 * <h2>Build strategy</h2>
 * <p>
 * Build uses two passes over postings:
 * </p>
 * <ol>
 *   <li>scan all postings to determine the maximum position reached in each document</li>
 *   <li>allocate the flat array and write each {@code (docId, position) -> termId}</li>
 * </ol>
 * <p>
 * If postings are missing, positions are unavailable, pass 1 sees zero positions,
 * pass 2 disagrees with pass 1, a term is absent from the lexicon, or a duplicate
 * slot write occurs, build fails explicitly.
 * </p>
 *
 * <h2>Lifecycle</h2>
 * <p>
 * Instances memory-map the two rail files. Call {@link #close()} when finished.
 * Close is best-effort and delegates to {@link TermLexicon#unmap(MappedByteBuffer)}.
 * </p>
 *
 * @see TermLexicon
 */
public final class TermRail implements Closeable {

    /** Sentinel value stored in positions that are present in the rail but carry no term. */
    public static final int NO_TERM = -1;

    /** Lucene directory that contains both the index and the rail files. */
    private final Path indexDir;

    /** Indexed field covered by this rail. */
    private final String field;

    /** Mapped buffer for {@code <field>.rail.dat}; retained for explicit unmapping on close. */
    private final MappedByteBuffer datBuf;

    /** Int view over {@link #datBuf}. */
    private final IntBuffer dat;

    /** Mapped buffer for {@code <field>.rail.off}; retained for explicit unmapping on close. */
    private final MappedByteBuffer offBuf;

    /** Int view over {@link #offBuf}. */
    private final IntBuffer off;

    /** Number of documents represented by this rail. */
    private final int docCount;

    /** Total number of stored positions across all documents. */
    private final int totalPositions;

    /** Maximum tolerated mtime difference between the two rail files. */
    private static final long MTIME_TOLERANCE_MS = 5_000L;

    /** Buffered write chunk size, in ints. */
    private static final int BUF_INTS = 8192;

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
     * Releases the mapped regions.
     * <p>
     * After close, all accessors on this instance are invalid.
     * </p>
     */
    @Override
    public void close() {
        TermLexicon.unmap(datBuf);
        TermLexicon.unmap(offBuf);
    }

    /**
     * Tests whether both rail files for a field exist as regular files.
     * <p>
     * This is a presence check only. It does not validate consistency, sizes,
     * offsets, or modification times.
     * </p>
     *
     * @param indexDir Lucene directory
     * @param field indexed field name
     * @return {@code true} if both rail files exist as regular files
     */
    public static boolean exists(final Path indexDir, final String field) {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(datPath(indexDir, field))
            && Files.isRegularFile(offPath(indexDir, field));
    }

    /**
     * Builds the rail files for one field from postings and a matching lexicon.
     * <p>
     * Preconditions:
     * </p>
     * <ul>
     *   <li>the field must exist in the supplied reader</li>
     *   <li>the field must expose positions in postings</li>
     *   <li>the lexicon must come from the same field and index snapshot</li>
     *   <li>the target rail files must not already exist</li>
     * </ul>
     * <p>
     * Build fails fast if:
     * </p>
     * <ul>
     *   <li>the field has no terms</li>
     *   <li>positions are unavailable</li>
     *   <li>pass 1 sees zero positions</li>
     *   <li>a postings term is absent from the lexicon</li>
     *   <li>pass 2 disagrees with pass 1</li>
     *   <li>two postings target the same {@code (docId, position)} slot</li>
     * </ul>
     * <p>
     * On failure, temporary files are cleaned up. Because final targets are required
     * to be absent before build starts, any final files created during a failed write
     * are also removed.
     * </p>
     *
     * @param indexDir Lucene directory that will receive the rail files
     * @param reader snapshot reader
     * @param field indexed field name
     * @param lexicon lexicon built from the same field and snapshot
     * @throws IOException if build fails or output files already exist
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

    /**
     * Opens an existing rail from disk and validates basic structural consistency.
     * <p>
     * Checks performed on open:
     * </p>
     * <ul>
     *   <li>both files exist as regular files</li>
     *   <li>their modification times are sufficiently close</li>
     *   <li>their byte sizes are multiples of 4</li>
     *   <li>the offsets file contains at least two ints</li>
     *   <li>{@code off[0] == 0}</li>
     *   <li>offsets are monotonic</li>
     *   <li>{@code off[last] == dat.length}</li>
     * </ul>
     *
     * @param indexDir Lucene directory that contains the rail files
     * @param field indexed field name
     * @return opened rail
     * @throws IOException if files are missing or structurally inconsistent
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
                throw new IOException(
                    "Rail offsets/data mismatch: last offset=" + last + ", data int count=" + dat.capacity()
                );
            }

            int prev = first;
            for (int i = 1; i < off.capacity(); i++) {
                final int cur = off.get(i);
                if (cur < prev) {
                    throw new IOException(
                        "Invalid rail offsets, not monotonic at index " + i +
                        ": " + prev + " -> " + cur + " in " + offPath
                    );
                }
                prev = cur;
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

    /**
     * Returns the Lucene directory from which this rail was opened.
     */
    public Path indexDir() {
        return indexDir;
    }

    /**
     * Returns the field name covered by this rail.
     */
    public String field() {
        return field;
    }

    /**
     * Returns the number of documents represented by this rail.
     */
    public int docCount() {
        return docCount;
    }

    /**
     * Returns the total number of positions stored in the flat data array.
     * <p>
     * This is the sum of document lengths in the rail, including positions that
     * may contain {@link #NO_TERM} because of analyzer gaps.
     * </p>
     */
    public int totalPositions() {
        return totalPositions;
    }

    /**
     * Returns the start offset, in int units, of the specified document in the flat data array.
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @return start offset into the data array
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docOffset(final int docId) {
        checkDocId(docId);
        return off.get(docId);
    }

    /**
     * Returns the rail length of one document.
     * <p>
     * This is {@code maxPositionSeen + 1} for that document during build.
     * It may therefore include positions that contain {@link #NO_TERM}.
     * </p>
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @return document length in rail positions
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docLength(final int docId) {
        checkDocId(docId);
        return off.get(docId + 1) - off.get(docId);
    }

    /**
     * Returns the term id stored at one document position.
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @param position rail position in {@code [0, docLength(docId))}
     * @return term id, or {@link #NO_TERM} for a gap position
     * @throws IllegalArgumentException if {@code docId} or {@code position} is out of range
     */
    public int termId(final int docId, final int position) {
        checkDocId(docId);
        final int base = off.get(docId);
        final int docLen = off.get(docId + 1) - base;
        if (position < 0 || position >= docLen) {
            throw new IllegalArgumentException(
                "position out of range: " + position + " (docLength=" + docLen + ", docId=" + docId + ")"
            );
        }
        return dat.get(base + position);
    }

    /**
     * Returns a read-only view of the flat term-id array.
     * <p>
     * Intended for bulk sequential scans. Use {@link #docOffset(int)} and
     * {@link #docLength(int)} to locate each document slice.
     * </p>
     */
    public IntBuffer datBuffer() {
        return dat.asReadOnlyBuffer();
    }

    private void checkDocId(final int docId) {
        if (docId < 0 || docId >= docCount) {
            throw new IllegalArgumentException(
                "docId out of range: " + docId + " (docCount=" + docCount + ")"
            );
        }
    }

    /**
     * Builds the two persisted files from postings with positions.
     */
    private static void buildFiles(
        final IndexReader reader,
        final String field,
        final TermLexicon lexicon,
        final Path datPath,
        final Path offPath
    ) throws IOException {
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IOException("Field not found or has no terms: " + field);
        }
        if (!terms.hasPositions()) {
            throw new IOException(
                "Field '" + field + "' has no positions indexed; " +
                "TermRail requires IndexOptions.DOCS_AND_FREQS_AND_POSITIONS or higher"
            );
        }

        final int maxDoc = reader.maxDoc();
        if (maxDoc < 0) {
            throw new IOException("Invalid reader.maxDoc() for field '" + field + "': " + maxDoc);
        }

        final Pass1Stats p1 = scanPass1(terms, field, maxDoc);
        final int[] offsets = buildOffsets(p1.maxPos, field);
        final int totalPositions = offsets[maxDoc];

        final int[] data = new int[totalPositions];
        Arrays.fill(data, NO_TERM);

        final Pass2Stats p2 = scanPass2(terms, field, lexicon, offsets, data);

        if (p1.termCount != p2.termCount) {
            throw new IOException(
                "TermRail pass mismatch on field '" + field +
                "': pass1 termCount=" + p1.termCount +
                ", pass2 termCount=" + p2.termCount
            );
        }
        if (p1.docHitCount != p2.docHitCount) {
            throw new IOException(
                "TermRail pass mismatch on field '" + field +
                "': pass1 docHitCount=" + p1.docHitCount +
                ", pass2 docHitCount=" + p2.docHitCount
            );
        }
        if (p1.positionCount != p2.positionCount) {
            throw new IOException(
                "TermRail pass mismatch on field '" + field +
                "': pass1 positionCount=" + p1.positionCount +
                ", pass2 positionCount=" + p2.positionCount
            );
        }
        if (p2.positionCount == 0L) {
            throw new IOException(
                "TermRail build produced zero positions for field '" + field +
                "' (terms existed, but no positioned postings were recorded)"
            );
        }

        writeIntFile(datPath, data);
        writeIntFile(offPath, offsets);
    }

    /**
     * Pass 1: determine the maximum position reached in each document.
     */
    private static Pass1Stats scanPass1(
        final Terms terms,
        final String field,
        final int maxDoc
    ) throws IOException {
        final int[] maxPos = new int[maxDoc];
        Arrays.fill(maxPos, -1);

        long termCount = 0L;
        long docHitCount = 0L;
        long positionCount = 0L;

        final TermsEnum te = terms.iterator();
        BytesRef termBytes;
        while ((termBytes = te.next()) != null) {
            termCount++;

            final PostingsEnum pe = te.postings(null, PostingsEnum.POSITIONS);
            if (pe == null) {
                throw new IOException(
                    "PostingsEnum.POSITIONS returned null for field '" + field +
                    "', term='" + termBytes.utf8ToString() + "'"
                );
            }

            int docId;
            while ((docId = pe.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (docId < 0 || docId >= maxDoc) {
                    throw new IOException(
                        "Invalid docId from postings for field '" + field +
                        "': " + docId + " (maxDoc=" + maxDoc + ")"
                    );
                }

                docHitCount++;

                final int freq = pe.freq();
                if (freq <= 0) {
                    throw new IOException(
                        "Invalid postings freq for field '" + field +
                        "', term='" + termBytes.utf8ToString() +
                        "', docId=" + docId + ": " + freq
                    );
                }

                for (int i = 0; i < freq; i++) {
                    final int pos = pe.nextPosition();
                    if (pos < 0) {
                        throw new IOException(
                            "Invalid negative position for field '" + field +
                            "', term='" + termBytes.utf8ToString() +
                            "', docId=" + docId + ": " + pos
                        );
                    }
                    if (pos > maxPos[docId]) {
                        maxPos[docId] = pos;
                    }
                    positionCount++;
                }
            }
        }

        if (termCount == 0L) {
            throw new IOException("Field has Terms but zero enumerated terms: " + field);
        }
        if (positionCount == 0L) {
            throw new IOException(
                "Field '" + field + "' has terms and reports positions, but pass 1 read zero positions"
            );
        }

        return new Pass1Stats(maxPos, termCount, docHitCount, positionCount);
    }

    /**
     * Pass 2: fill the flat data array with {@code termId} values.
     * <p>
     * Duplicate writes to the same slot are rejected. This catches stacked tokens
     * and any other violation of the one-term-per-position contract.
     * </p>
     */
    private static Pass2Stats scanPass2(
        final Terms terms,
        final String field,
        final TermLexicon lexicon,
        final int[] offsets,
        final int[] data
    ) throws IOException {
        long termCount = 0L;
        long docHitCount = 0L;
        long positionCount = 0L;

        final TermsEnum te = terms.iterator();
        BytesRef termBytes;
        while ((termBytes = te.next()) != null) {
            termCount++;

            final int termId = lexicon.id(termBytes);
            if (termId < 0) {
                throw new IOException(
                    "Term from postings absent in lexicon for field '" + field +
                    "': '" + termBytes.utf8ToString() + "'"
                );
            }

            final PostingsEnum pe = te.postings(null, PostingsEnum.POSITIONS);
            if (pe == null) {
                throw new IOException(
                    "PostingsEnum.POSITIONS returned null in pass 2 for field '" + field +
                    "', term='" + termBytes.utf8ToString() + "'"
                );
            }

            int docId;
            while ((docId = pe.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                docHitCount++;

                final int base = offsets[docId];
                final int limit = offsets[docId + 1];
                final int docLen = limit - base;

                final int freq = pe.freq();
                if (freq <= 0) {
                    throw new IOException(
                        "Invalid postings freq in pass 2 for field '" + field +
                        "', term='" + termBytes.utf8ToString() +
                        "', docId=" + docId + ": " + freq
                    );
                }

                for (int i = 0; i < freq; i++) {
                    final int pos = pe.nextPosition();
                    if (pos < 0) {
                        throw new IOException(
                            "Invalid negative position in pass 2 for field '" + field +
                            "', term='" + termBytes.utf8ToString() +
                            "', docId=" + docId + ": " + pos
                        );
                    }
                    if (pos >= docLen) {
                        throw new IOException(
                            "Pass 2 position exceeds pass 1 doc length for field '" + field +
                            "', term='" + termBytes.utf8ToString() +
                            "', docId=" + docId +
                            ", pos=" + pos +
                            ", docLen=" + docLen
                        );
                    }

                    final int slot = base + pos;
                    final int prev = data[slot];
                    if (prev != NO_TERM) {
                        throw new IOException(
                            "Duplicate write to rail slot for field '" + field +
                            "', docId=" + docId +
                            ", pos=" + pos +
                            ", existingTermId=" + prev +
                            ", newTermId=" + termId +
                            ", term='" + termBytes.utf8ToString() + "'"
                        );
                    }

                    data[slot] = termId;
                    positionCount++;
                }
            }
        }

        return new Pass2Stats(termCount, docHitCount, positionCount);
    }

    /**
     * Builds the CSR-style offsets array from per-document maximum positions.
     */
    private static int[] buildOffsets(final int[] maxPos, final String field) throws IOException {
        final int[] offsets = new int[maxPos.length + 1];
        long total = 0L;
        offsets[0] = 0;

        for (int d = 0; d < maxPos.length; d++) {
            final long docLen = (maxPos[d] < 0) ? 0L : ((long) maxPos[d] + 1L);
            total += docLen;
            if (total > Integer.MAX_VALUE) {
                throw new IOException(
                    "TermRail too large for int offsets on field '" + field +
                    "': totalPositions=" + total
                );
            }
            offsets[d + 1] = (int) total;
        }

        return offsets;
    }

    /**
     * Writes an int array to disk in native byte order.
     */
    private static void writeIntFile(final Path path, final int[] values) throws IOException {
        try (FileChannel ch = FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            final ByteBuffer buf = ByteBuffer.allocate(BUF_INTS * Integer.BYTES)
                .order(ByteOrder.nativeOrder());

            for (int value : values) {
                if (buf.remaining() < Integer.BYTES) {
                    buf.flip();
                    writeFully(ch, buf);
                    buf.clear();
                }
                buf.putInt(value);
            }

            if (buf.position() > 0) {
                buf.flip();
                writeFully(ch, buf);
            }

            ch.force(true);
        }
    }

    /**
     * Writes the full remaining content of a buffer to a channel.
     */
    private static void writeFully(final FileChannel ch, final ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /**
     * Maps one file in read-only mode.
     */
    private static MappedByteBuffer mapReadOnly(final Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0L, ch.size());
        }
    }

    /**
     * Returns {@code <indexDir>/<field>.rail.dat}.
     */
    private static Path datPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".rail.dat");
    }

    /**
     * Returns {@code <indexDir>/<field>.rail.off}.
     */
    private static Path offPath(final Path indexDir, final String field) {
        return indexDir.resolve(field + ".rail.off");
    }

    /**
     * Returns a sibling temporary path used during write.
     */
    private static Path tmpPath(final Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".tmp");
    }

    /**
     * Moves a temporary file to its final location.
     * <p>
     * Atomic move is attempted first and plain move is used as fallback.
     * </p>
     */
    private static void moveTemp(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target);
        }
    }

    /**
     * Deletes a file if it exists. Failures are ignored because this is cleanup only.
     */
    private static void deleteIfExists(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    /**
     * Ensures that a path exists and is a regular file.
     */
    private static void ensureRegularFile(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /**
     * Ensures that a path does not already exist.
     */
    private static void ensureAbsent(final Path path) throws IOException {
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    /**
     * Checks that all supplied paths have close modification times.
     * <p>
     * This is a cheap guard against mixing a data file and an offsets file from
     * different writes.
     * </p>
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
                "Rail file mtimes differ by " + (max - min) + "ms; possible partial copy or mixed versions"
            );
        }
    }

    /**
     * Pass 1 accounting.
     */
    private static final class Pass1Stats {
        final int[] maxPos;
        final long termCount;
        final long docHitCount;
        final long positionCount;

        Pass1Stats(
            final int[] maxPos,
            final long termCount,
            final long docHitCount,
            final long positionCount
        ) {
            this.maxPos = maxPos;
            this.termCount = termCount;
            this.docHitCount = docHitCount;
            this.positionCount = positionCount;
        }
    }

    /**
     * Pass 2 accounting.
     */
    private static final class Pass2Stats {
        final long termCount;
        final long docHitCount;
        final long positionCount;

        Pass2Stats(
            final long termCount,
            final long docHitCount,
            final long positionCount
        ) {
            this.termCount = termCount;
            this.docHitCount = docHitCount;
            this.positionCount = positionCount;
        }
    }
}
