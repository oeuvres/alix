package com.github.oeuvres.alix.lucene.terms;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermVectors;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.util.IOUtil;
import com.github.oeuvres.alix.util.NumWriter;
import com.github.oeuvres.alix.util.Report;

/**
 * Forward positional rail for one Lucene field, built from term vectors.
 *
 * <p>Maps each {@code (docId, position)} to a single {@code termId}, where
 * {@code termId} is assigned by a {@link TermLexicon} built from the same
 * index snapshot.</p>
 *
 * <h2>Physical layout</h2>
 * <p>Two native-endian files are produced under the side directory:</p>
 * <ul>
 *   <li><b>{@code <field>.rail.dat}</b> — flat {@code int[]} of term ids,
 *       concatenated document by document; may exceed 2&nbsp;GB.</li>
 *   <li><b>{@code <field>.rail.off}</b> — {@code long[docCount + 1]} of
 *       <em>byte</em> offsets into the data file; {@code off[d]} is the byte
 *       position where the rail of document {@code d} starts.</li>
 * </ul>
 * <p>For document {@code d}, the slice is:</p>
 * <pre>{@code
 * long base   = off[d];
 * int  len    = (int) ((off[d + 1] - off[d]) / Integer.BYTES);
 * int  termId = dat.get(JAVA_INT, base + p * 4L);
 * }</pre>
 *
 * <h2>Index requirements</h2>
 * <p>The source field must have term vectors with positions stored
 * ({@code TermVectors} with {@code POSITIONS}).</p>
 *
 * <h2>Semantic contract</h2>
 * <p>Exactly one term id per position slot. Position gaps and unfilled slots
 * are stored as {@link #NO_TERM}. Stacked tokens ({@code positionIncrement == 0},
 * synonym or lemma stacks) are not supported; a duplicate slot write causes
 * {@link #build} to fail immediately.</p>
 *
 * <h2>Build strategy</h2>
 * <p>Two passes over term vectors:</p>
 * <ol>
 *   <li>Determine the maximum position reached in each document to size
 *       the offset table and allocate the data file.</li>
 *   <li>Write {@code (docId, position) → termId} into the flat array.</li>
 * </ol>
 *
 * <h2>Memory mapping and I/O</h2>
 * <p>On {@link #open}, both files are memory-mapped via {@code mmap(2)}.
 * Pages are demand-faulted by the OS virtual memory subsystem; no data is
 * loaded eagerly. For a sparse access pattern (cooccurrence on a filtered
 * doc set) only the touched pages ever become resident, providing a natural
 * sliding window at OS level without any explicit prefetch logic. The
 * {@code .dat} file is mapped through a {@link MemorySegment} backed by an
 * {@link Arena#ofShared() shared Arena}, which supports files larger than
 * 2&nbsp;GB and is safe for concurrent reads from multiple threads.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #close()} when finished. After close all accessors are
 * invalid.</p>
 *
 * @see TermLexicon
 */
public final class TermRail implements Closeable {

    /** Sentinel value stored at position gaps and unfilled slots. */
    public static final int NO_TERM = 0;

    /** Maximum tolerated mtime difference between the two rail files. */
    private static final long MTIME_TOLERANCE_MS = 5_000L;

    /** Directory that contains both the index and the rail files. */
    private final Path sideDir;

    /** Indexed field covered by this rail. */
    private final String field;

    /** Number of documents represented by this rail. */
    private final int docCount;

    /** Total number of int position slots across all documents. */
    private final long totalPositions;

    /**
     * Arena owning the dat mapping; released on {@link #close()}.
     * {@link Arena#ofShared()} permits concurrent reads from multiple threads.
     */
    private final Arena datArena;

    /**
     * Memory-mapped view of {@code <field>.rail.dat}.
     * Native-endian {@code int} per position slot; may exceed 2&nbsp;GB.
     */
    private final MemorySegment dat;

    /** Long view over the mapped {@code <field>.rail.off} file. */
    private final LongBuffer off;

    /** Retained for explicit unmapping of the off file on {@link #close()}. */
    private final MappedByteBuffer offBuf;

    private TermRail(
        final Path sideDir,
        final String field,
        final int docCount,
        final long totalPositions,
        final Arena datArena,
        final MemorySegment dat,
        final MappedByteBuffer offBuf,
        final LongBuffer off
    ) {
        this.sideDir = sideDir;
        this.field = field;
        this.docCount = docCount;
        this.totalPositions = totalPositions;
        this.datArena = datArena;
        this.dat = dat;
        this.offBuf = offBuf;
        this.off = off;
    }

    // -------------------------------------------------------------------------
    // Public API — alphabetical
    // -------------------------------------------------------------------------

    /**
     * Builds a term-id rail for the given field and writes it to disk.
     *
     * <p>Produces two files under {@code sideDir}:</p>
     * <ul>
     *   <li><b>offsets</b> ({@code <field>.rail.off}) — a
     *       {@code long[maxDoc + 1]} array of byte offsets into the data file.
     *       {@code off[docId]} is the byte position where the rail for
     *       {@code docId} begins; the slot count is
     *       {@code (off[docId + 1] - off[docId]) / Integer.BYTES}.
     *       Deleted documents and documents without the field have
     *       {@code off[docId] == off[docId + 1]}.</li>
     *   <li><b>data</b> ({@code <field>.rail.dat}) — a flat {@code int[]}
     *       where each slot holds the {@link TermLexicon} id of the term at
     *       that position, or {@link #NO_TERM} for gaps. May exceed
     *       2&nbsp;GB.</li>
     * </ul>
     *
     * <p>Both files are written to temporary paths first and atomically
     * renamed on success. On failure all temporary and final files are
     * deleted.</p>
     *
     * @param reader  index reader; field must have term vectors with positions.
     * @param sideDir directory for the output files.
     * @param field   indexed field name.
     * @param lexicon FST lexicon mapping terms to integer ids; id {@link #NO_TERM}
     *                is reserved as a sentinel (no term at position).
     * @param report  progress reporter; {@code null} is accepted.
     * @throws IOException              if an I/O error occurs.
     * @throws IllegalArgumentException if the field has no term vectors.
     */
    public static void build(
        final IndexReader reader,
        final Path sideDir,
        final String field,
        final TermLexicon lexicon,
        Report report
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(lexicon, "lexicon");
        if (report == null) report = Report.ReportNull.INSTANCE;

        final FieldInfo fi = FieldInfos.getMergedFieldInfos(reader).fieldInfo(field);
        if (fi == null || !fi.hasTermVectors()) {
            throw new IllegalArgumentException("field \"" + field + "\" has no term vectors");
        }

        final Path offFinal = offPath(sideDir, field);
        IOUtil.ensureAbsent(offFinal);
        final Path offTmp = IOUtil.tmpPath(offFinal);
        IOUtil.deleteIfExists(offTmp);

        final int maxDoc = reader.maxDoc();
        final BitSet liveDocs = FieldStats.liveDocs(reader);
        final int[] docWidths = FieldStats.docWidths(reader, field, report);

        int widthMax = 0;
        final long[] offsets = new long[maxDoc + 1];
        long totalBytes = 0L;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (docWidths[docId] <= 0 || !liveDocs.get(docId)) {
                docWidths[docId] = 0;
            }
            offsets[docId] = totalBytes;
            if (docWidths[docId] > widthMax) {
                widthMax = docWidths[docId];
            }
            totalBytes += (long) docWidths[docId] * Integer.BYTES;
        }
        offsets[maxDoc] = totalBytes;

        try (NumWriter offsetsWriter = NumWriter.open(offTmp, (long) offsets.length * Long.BYTES)) {
            offsetsWriter.put(0L, offsets, 0, offsets.length);
        }

        final Path datFinal = datPath(sideDir, field);
        IOUtil.ensureAbsent(datFinal);
        final Path datTmp = IOUtil.tmpPath(datFinal);
        IOUtil.deleteIfExists(datTmp);

        try (NumWriter railWriter = NumWriter.open(datTmp, totalBytes)) {
            final int[] rail = new int[widthMax];
            final TermVectors termVectors = reader.termVectors();
            for (int docId = 0; docId < maxDoc; docId++) {
                final int docWidth = docWidths[docId];
                if (docWidth == 0) continue;
                final Fields fields = termVectors.get(docId);
                if (fields == null) continue;
                final Terms terms = fields.terms(field);
                if (terms == null) continue;
                if (!terms.hasPositions()) {
                    throw new IllegalArgumentException(
                        "field \"" + field + "\" term vectors for docId=" + docId + " have no positions"
                    );
                }
                Arrays.fill(rail, 0, docWidth, 0);
                final TermsEnum termsEnum = terms.iterator();
                BytesRef term;
                while ((term = termsEnum.next()) != null) {
                    final int termId = lexicon.id(term);
                    final PostingsEnum postings = termsEnum.postings(null, PostingsEnum.POSITIONS);
                    postings.nextDoc();
                    final int freq = postings.freq();
                    for (int i = 0; i < freq; i++) {
                        rail[postings.nextPosition()] = termId;
                    }
                }
                railWriter.put(offsets[docId], rail, 0, docWidth);
            }
        }

        try {
            IOUtil.moveTemp(datTmp, datFinal);
            IOUtil.moveTemp(offTmp, offFinal);
        } catch (IOException | RuntimeException e) {
            IOUtil.deleteIfExists(datTmp);
            IOUtil.deleteIfExists(offTmp);
            IOUtil.deleteIfExists(datFinal);
            IOUtil.deleteIfExists(offFinal);
            throw e;
        }
    }

    /**
     * Releases the memory-mapped regions.
     *
     * <p>After close, all accessors on this instance produce undefined
     * results. Close is idempotent within the same instance.</p>
     */
    @Override
    public void close() {
        datArena.close();
        IOUtil.unmap(offBuf);
    }

    /**
     * Returns the number of documents represented by this rail.
     *
     * @return document count, equal to {@code IndexReader.maxDoc()} at build time.
     */
    public int docCount() {
        return docCount;
    }

    /**
     * Returns the number of position slots for one document.
     *
     * <p>This is {@code maxPositionSeen + 1} for that document as recorded
     * during build, and may therefore include {@link #NO_TERM} gap slots.</p>
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @return slot count
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docLength(final int docId) {
        checkDocId(docId);
        return (int) ((off.get(docId + 1) - off.get(docId)) / Integer.BYTES);
    }

    /**
     * Returns the byte offset of one document in the dat file.
     *
     * <p>To address position {@code p} within the document, use
     * {@code docOffset(docId) + p * Integer.BYTES} as the byte address
     * into the {@code dat} segment. Prefer {@link #scanWindow} for bulk
     * window access.</p>
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @return byte offset into {@code <field>.rail.dat}
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public long docOffset(final int docId) {
        checkDocId(docId);
        return off.get(docId);
    }

    /**
     * Tests whether both rail files for the given field exist as regular files.
     *
     * <p>Presence check only; does not validate sizes, offsets, or
     * modification times.</p>
     *
     * @param sideDir directory containing the rail files
     * @param field   indexed field name
     * @return {@code true} if both files exist
     */
    public static boolean exists(final Path sideDir, final String field) {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(datPath(sideDir, field))
            && Files.isRegularFile(offPath(sideDir, field));
    }

    /**
     * Returns the indexed field name covered by this rail.
     *
     * @return field name
     */
    public String field() {
        return field;
    }

    /**
     * Opens an existing rail from disk and validates structural consistency.
     *
     * <p>Checks performed:</p>
     * <ul>
     *   <li>both files exist as regular files</li>
     *   <li>modification times agree within {@code 5 s}</li>
     *   <li>{@code off} file size is a multiple of 8 (long entries)</li>
     *   <li>{@code dat} file size is a multiple of 4 (int entries)</li>
     *   <li>{@code off[0] == 0}</li>
     *   <li>{@code off[last] == dat byte size}</li>
     *   <li>offsets are monotonically non-decreasing (sampled for large corpora)</li>
     * </ul>
     *
     * <p>No data is loaded eagerly. Pages of the {@code dat} file are
     * demand-faulted by the OS on first access, providing automatic
     * sliding-window behaviour for large files.</p>
     *
     * @param sideDir directory containing the rail files
     * @param field   indexed field name
     * @return opened rail; caller must {@link #close()} when done
     * @throws IOException if files are missing or structurally inconsistent
     */
    public static TermRail open(final Path sideDir, final String field) throws IOException {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");

        final Path datPath = datPath(sideDir, field);
        final Path offPath = offPath(sideDir, field);
        IOUtil.ensureRegularFile(datPath);
        IOUtil.ensureRegularFile(offPath);
        IOUtil.checkMtimeCoherence(MTIME_TOLERANCE_MS, datPath, offPath);

        final MappedByteBuffer offBuf = IOUtil.mapReadOnly(offPath);
        offBuf.order(ByteOrder.nativeOrder());

        Arena datArena = null;
        try {
            final long offBytes = offBuf.remaining();
            if ((offBytes & 7L) != 0)
                throw new IOException("off file size not a multiple of 8: " + offPath);
            if (offBytes < 16L)
                throw new IOException("off file too small (need at least 2 entries): " + offPath);

            final LongBuffer off = offBuf.asLongBuffer();
            if (off.get(0) != 0L)
                throw new IOException("off[0] != 0 in " + offPath);

            final long datBytes = Files.size(datPath);
            if ((datBytes & 3L) != 0)
                throw new IOException("dat file size not a multiple of 4: " + datPath);
            if (off.get(off.capacity() - 1) != datBytes)
                throw new IOException(
                    "off/dat mismatch: last offset=" + off.get(off.capacity() - 1)
                    + ", dat bytes=" + datBytes + " in " + datPath);

            validateMonotonic(off, offPath);

            datArena = Arena.ofShared();
            final MemorySegment dat;
            try (FileChannel fc = FileChannel.open(datPath, StandardOpenOption.READ)) {
                dat = fc.map(FileChannel.MapMode.READ_ONLY, 0L, datBytes, datArena);
            }

            final int docCount = off.capacity() - 1;
            final long totalPositions = datBytes / Integer.BYTES;
            return new TermRail(sideDir, field, docCount, totalPositions,
                                datArena, dat, offBuf, off);

        } catch (IOException | RuntimeException e) {
            if (datArena != null) datArena.close();
            IOUtil.unmap(offBuf);
            throw e;
        }
    }

    /**
     * Scans a position window within one document and delivers each non-{@link #NO_TERM}
     * term id to {@code sink}.
     *
     * <p>This is the primary entry point for cooccurrence accumulation. The
     * caller passes the window {@code [posLo, posHi]} derived from the pivot
     * positions found in step 1 (posting intersection), and the method iterates
     * the corresponding dat slots sequentially, which is cache-friendly after
     * the single seek to {@code base}. Both bounds are clamped to the document
     * length so the caller need not guard against them.</p>
     *
     * <p>Example — accumulate cooc frequencies for a pair of pivots at
     * positions {@code pA} and {@code pB} with span {@code n}:</p>
     * <pre>{@code
     * int lo = Math.min(pA, pB) - n;
     * int hi = Math.max(pA, pB) + n;
     * rail.scanWindow(docId, lo, hi, termId -> coocFreq[termId]++);
     * }</pre>
     *
     * @param docId  Lucene doc id in {@code [0, docCount)}
     * @param posLo  first position to include (inclusive; clamped to {@code [0, docLength)})
     * @param posHi  last position to include (inclusive; clamped to {@code [0, docLength)})
     * @param sink   receives each non-zero termId in the window, in position order
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public void scanWindow(
        final int docId,
        final int posLo,
        final int posHi,
        final IntConsumer sink
    ) {
        checkDocId(docId);
        final long base = off.get(docId);
        final int docLen = (int) ((off.get(docId + 1) - base) / Integer.BYTES);
        final int lo = Math.max(0, posLo);
        final int hi = Math.min(docLen - 1, posHi);
        for (int p = lo; p <= hi; p++) {
            final int id = dat.get(ValueLayout.JAVA_INT_UNALIGNED,
                                   base + (long) p * Integer.BYTES);
            if (id != NO_TERM) sink.accept(id);
        }
    }

    /**
     * Returns the directory from which this rail was opened.
     *
     * @return side directory path
     */
    public Path sideDir() {
        return sideDir;
    }

    /**
     * Returns the term id stored at one document position.
     *
     * @param docId    Lucene doc id in {@code [0, docCount)}
     * @param position position in {@code [0, docLength(docId))}
     * @return term id, or {@link #NO_TERM} for a gap slot
     * @throws IllegalArgumentException if {@code docId} or {@code position} is out of range
     */
    public int termId(final int docId, final int position) {
        checkDocId(docId);
        final long base = off.get(docId);
        final int docLen = (int) ((off.get(docId + 1) - base) / Integer.BYTES);
        if (position < 0 || position >= docLen) {
            throw new IllegalArgumentException(
                "position " + position + " out of range (docLen=" + docLen
                + ", docId=" + docId + ")");
        }
        return dat.get(ValueLayout.JAVA_INT_UNALIGNED,
                       base + (long) position * Integer.BYTES);
    }

    /**
     * Returns the total number of int position slots across all documents.
     *
     * <p>Includes {@link #NO_TERM} gap slots. Equal to
     * {@code dat byte size / Integer.BYTES}.</p>
     *
     * @return total position slot count
     */
    public long totalPositions() {
        return totalPositions;
    }

    private void checkDocId(final int docId) {
        if (docId < 0 || docId >= docCount) {
            throw new IllegalArgumentException(
                "docId " + docId + " out of range (docCount=" + docCount + ")");
        }
    }

    private static Path datPath(final Path dir, final String field) {
        return dir.resolve(field + ".rail.dat");
    }

    private static Path offPath(final Path dir, final String field) {
        return dir.resolve(field + ".rail.off");
    }

    /**
     * Checks that the offset buffer is monotonically non-decreasing.
     * For large corpora (capacity &gt; 1&nbsp;M entries) only every 4096th
     * entry is checked to keep {@link #open} O(1) amortised.
     */
    private static void validateMonotonic(final LongBuffer off, final Path offPath)
        throws IOException {
        final int cap = off.capacity();
        final int stride = cap > 1_048_576 ? 4096 : 1;
        long prev = 0L;
        for (int i = 1; i < cap; i += stride) {
            final long cur = off.get(i);
            if (cur < prev) {
                throw new IOException(
                    "Rail offsets not monotonic at index " + i
                    + ": " + prev + " → " + cur + " in " + offPath);
            }
            prev = cur;
        }
    }
}
