package com.github.oeuvres.alix.lucene.terms;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>
 * Maps each {@code (docId, position)} to a single {@code termId}, where {@code termId} is assigned
 * by a {@link TermLexicon} built from the same index snapshot.
 * </p>
 * <h2>Physical layout</h2>
 * <p>
 * Two native-endian files are produced under the side directory:
 * </p>
 * <ul>
 * <li><b>{@code <field>.rail.dat}</b> — flat {@code int[]} of term ids, concatenated document by
 * document; may exceed 2&nbsp;GB.</li>
 * <li><b>{@code <field>.rail.off}</b> — {@code long[maxDoc + 1]} of <em>byte</em> offsets into the
 * data file; {@code off[d]} is the byte position where the rail of document {@code d} starts.</li>
 * </ul>
 * <p>
 * For document {@code d}, the slice is:
 * </p>
 *
 * <pre>{@code
 * long base = off[d];
 * int len = (int) ((off[d + 1] - off[d]) / Integer.BYTES);
 * int termId = dat.get(JAVA_INT, base + p * 4L);
 * }</pre>
 *
 * <h2>Index requirements</h2>
 * <p>
 * The source field must have term vectors with positions stored.
 * </p>
 * <h2>Semantic contract</h2>
 * <p>
 * Exactly one term id per position slot. Position gaps and unfilled slots are stored as
 * {@link #NO_TERM}. Stacked tokens ({@code positionIncrement == 0}, synonym or lemma stacks) are
 * not supported: a duplicate slot write during {@link #build} aborts the build with an
 * {@link IllegalStateException}.
 * </p>
 * <h2>Build strategy</h2>
 * <p>
 * Two passes over different sources:
 * </p>
 * <ol>
 * <li>One pass over the inverted-index postings (via {@link TermStats#docWidths}) to compute each
 * document's width and size the offset table.</li>
 * <li>One pass over the term vectors to fill the {@code (docId, position) → termId} array.
 * Term-vector positions for a document are required to fit within its postings-derived width; an
 * out-of-range position aborts the build.</li>
 * </ol>
 * <h2>Memory mapping and I/O</h2>
 * <p>
 * On {@link #open}, both files are mapped as {@link MemorySegment}s owned by one
 * {@link Arena#ofShared() shared Arena}. Pages are demand-faulted by the operating system; no data
 * is loaded eagerly. The shared arena makes concurrent reads valid and, when closed, deterministically
 * unmaps both files without reflective cleaner access.
 * </p>
 * <h2>Lifecycle</h2>
 * <p>
 * Call {@link #close()} when finished. After close all accessors are invalid.
 * </p>
 *
 * @see TermLexicon
 */
public final class TermRail implements Closeable
{
    /** Sentinel value stored at position gaps and unfilled slots. */
    public static final int NO_TERM = 0;

    private final Arena arena;
    private final MemorySegment dat;
    private final int docCount;
    private final String field;
    private final MemorySegment off;
    private final Path sideDir;
    private final long totalPositions;

    /**
     * Creates an opened rail backed by arena-owned mapped segments.
     *
     * @param sideDir directory containing the rail files
     * @param field indexed field name covered by this rail
     * @param docCount number of documents represented
     * @param totalPositions total number of position slots across all documents
     * @param arena shared arena owning both mappings
     * @param dat mapped {@code <field>.rail.dat} segment
     * @param off mapped {@code <field>.rail.off} segment
     */
    private TermRail(
        final Path sideDir,
        final String field,
        final int docCount,
        final long totalPositions,
        final Arena arena,
        final MemorySegment dat,
        final MemorySegment off
    ) {
        this.sideDir = sideDir;
        this.field = field;
        this.docCount = docCount;
        this.totalPositions = totalPositions;
        this.arena = arena;
        this.dat = dat;
        this.off = off;
    }

    /**
     * Builds the term-id rail for the given field and writes it to disk.
     * <p>
     * Produces {@code <field>.rail.dat} and {@code <field>.rail.off} under {@code sideDir}, written to
     * temporary paths first and renamed atomically on success. On failure all temporary and final files
     * are deleted.
     * </p>
     *
     * @param reader index reader; field must have term vectors with positions
     * @param sideDir directory for the output files
     * @param field indexed field name
     * @param lexicon term lexicon mapping terms to dense ids; id {@link #NO_TERM} is reserved
     * @param report progress reporter; {@code null} accepted, mapped to a no-op reporter
     * @throws IOException on I/O failure
     * @throws IllegalArgumentException if the field has no term vectors, or term vectors for some
     * document have no positions
     * @throws IllegalStateException if a document has stacked tokens or term-vector positions outside
     * its postings-derived width
     * @throws NullPointerException if {@code reader}, {@code sideDir}, {@code field}, or
     * {@code lexicon} is {@code null}
     */
    public static void build(
        final IndexReader reader,
        final Path sideDir,
        final String field,
        final TermLexicon lexicon,
        Report report
    )
        throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(lexicon, "lexicon");
        if (report == null)
            report = Report.ReportNull.INSTANCE;

        final FieldInfo fi = FieldInfos.getMergedFieldInfos(reader).fieldInfo(field);
        if (fi == null || !fi.hasTermVectors()) {
            throw new IllegalArgumentException("field \"" + field + "\" has no term vectors");
        }

        final Path offFinal = offPath(sideDir, field);
        IOUtil.ensureAbsent(offFinal);
        final Path offTmp = IOUtil.tmpPath(offFinal);
        IOUtil.deleteIfExists(offTmp);

        final int maxDoc = reader.maxDoc();
        final BitSet liveDocs = TermStats.liveDocs(reader);
        final int[] docWidths = TermStats.docWidths(reader, field, report);

        int widthMax = 0;
        final long[] offsets = new long[maxDoc + 1];
        long totalBytes = 0L;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (docWidths[docId] <= 0 || !liveDocs.get(docId)) {
                docWidths[docId] = 0;
            }
            offsets[docId] = totalBytes;
            if (docWidths[docId] > widthMax)
                widthMax = docWidths[docId];
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
                if (docWidth == 0)
                    continue;
                final Fields fields = termVectors.get(docId);
                if (fields == null)
                    continue;
                final Terms terms = fields.terms(field);
                if (terms == null)
                    continue;
                if (!terms.hasPositions()) {
                    throw new IllegalArgumentException(
                            "field \"" + field + "\" term vectors for docId=" + docId + " have no positions");
                }
                Arrays.fill(rail, 0, docWidth, NO_TERM);
                final TermsEnum termsEnum = terms.iterator();
                BytesRef term;
                while ((term = termsEnum.next()) != null) {
                    final int termId = lexicon.id(term);
                    final PostingsEnum postings = termsEnum.postings(null, PostingsEnum.POSITIONS);
                    postings.nextDoc();
                    final int freq = postings.freq();
                    for (int i = 0; i < freq; i++) {
                        final int pos = postings.nextPosition();
                        if (pos < 0 || pos >= docWidth) {
                            throw new IllegalStateException(
                                    "term-vector position out of range: docId=" + docId + ", pos=" + pos + ", docWidth="
                                            + docWidth + ", term=" + term.utf8ToString());
                        }
                        if (rail[pos] != NO_TERM) {
                            throw new IllegalStateException(
                                    "stacked token at docId=" + docId + ", pos=" + pos + ": existing termId="
                                            + rail[pos] + ", new termId=" + termId + ", new term="
                                            + term.utf8ToString());
                        }
                        rail[pos] = termId;
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
     * Releases both memory-mapped rail files by closing their shared arena.
     * <p>
     * After this method returns, all accessors on this instance are invalid. Idempotency is not
     * guaranteed.
     * </p>
     */
    @Override
    public void close()
    {
        arena.close();
    }

    /**
     * Copies all position slots of one document into a caller-provided array.
     * <p>
     * The copy preserves {@link #NO_TERM} gap slots. The destination may be larger than the document;
     * only indices {@code [0, docLength)} are written. This method resolves the document base offset
     * once, making it suitable for algorithms that need a complete positional document rail in a tight
     * loop.
     * </p>
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @param destination destination array with capacity at least {@link #docLength(int)}
     * @return number of copied position slots
     * @throws IllegalArgumentException if {@code docId} is out of range or the destination is too small
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public int copyDocument(
        final int docId,
        final int[] destination
    ) {
        Objects.requireNonNull(destination, "destination");
        checkDocId(docId);
        final long base = offset(docId);
        final int docLen = (int) ((offset(docId + 1) - base) / Integer.BYTES);
        if (destination.length < docLen) {
            throw new IllegalArgumentException(
                "destination too small: length=" + destination.length + ", docLen=" + docLen);
        }
        for (int position = 0; position < docLen; position++) {
            destination[position] = dat.get(
                ValueLayout.JAVA_INT_UNALIGNED,
                base + (long) position * Integer.BYTES);
        }
        return docLen;
    }

    /**
     * Returns the number of documents represented by this rail.
     *
     * @return document count, equal to {@code IndexReader.maxDoc()} at build time
     */
    public int docCount() {
        return docCount;
    }

    /**
     * Returns the slot count for one document. May include {@link #NO_TERM} gap slots.
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @return slot count, {@code 0} for documents without the field or marked deleted at build time
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public int docLength(
        final int docId
    ) {
        checkDocId(docId);
        return (int) ((offset(docId + 1) - offset(docId)) / Integer.BYTES);
    }

    /**
     * Tests whether both rail files for the given field exist as regular files. Presence check only;
     * does not validate sizes, offsets, or modification times.
     *
     * @param sideDir directory containing the rail files
     * @param field indexed field name
     * @return {@code true} if both files exist as regular files
     * @throws NullPointerException if {@code sideDir} or {@code field} is {@code null}
     */
    public static boolean exists(
        final Path sideDir,
        final String field
    ) {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(datPath(sideDir, field)) && Files.isRegularFile(offPath(sideDir, field));
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
     * <p>
     * Both rail files are mapped read-only into one shared arena. Closing the returned rail closes
     * that arena and unmaps both files. No data is loaded eagerly.
     * </p>
     * <p>
     * Checks performed:
     * </p>
     * <ul>
     * <li>both files exist as regular files</li>
     * <li>{@code off} file size is a multiple of 8 and contains at least two entries</li>
     * <li>{@code dat} file size is a multiple of 4</li>
     * <li>{@code off[0] == 0}</li>
     * <li>{@code off[last] == dat byte size}</li>
     * <li>offsets are monotonically non-decreasing, sampled for very large corpora</li>
     * </ul>
     *
     * @param sideDir directory containing the rail files
     * @param field indexed field name
     * @return opened rail; caller must {@link #close()} it when done
     * @throws IOException if files are missing or structurally inconsistent
     * @throws NullPointerException if {@code sideDir} or {@code field} is {@code null}
     */
    public static TermRail open(
        final Path sideDir,
        final String field
    ) throws IOException {
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");

        final Path datPath = datPath(sideDir, field);
        final Path offPath = offPath(sideDir, field);
        IOUtil.ensureRegularFile(datPath);
        IOUtil.ensureRegularFile(offPath);

        final long offBytes = Files.size(offPath);
        if ((offBytes & 7L) != 0L) {
            throw new IOException("off file size not a multiple of 8: " + offPath);
        }
        if (offBytes < 2L * Long.BYTES) {
            throw new IOException("off file too small (need at least 2 entries): " + offPath);
        }
        final long offsetCountLong = offBytes / Long.BYTES;
        if (offsetCountLong > Integer.MAX_VALUE) {
            throw new IOException("too many rail offsets for int doc ids: " + offsetCountLong);
        }
        final int offsetCount = (int) offsetCountLong;

        final long datBytes = Files.size(datPath);
        if ((datBytes & 3L) != 0L) {
            throw new IOException("dat file size not a multiple of 4: " + datPath);
        }

        final Arena arena = Arena.ofShared();
        try {
            final MemorySegment off = IOUtil.mapReadOnly(offPath, arena);
            final MemorySegment dat = IOUtil.mapReadOnly(datPath, arena);

            if (offset(off, 0) != 0L) {
                throw new IOException("off[0] != 0 in " + offPath);
            }
            final long lastOffset = offset(off, offsetCount - 1);
            if (lastOffset != datBytes) {
                throw new IOException(
                    "off/dat mismatch: last offset=" + lastOffset + ", dat bytes=" + datBytes
                        + " in " + datPath);
            }
            validateMonotonic(off, offsetCount, offPath);

            final int docCount = offsetCount - 1;
            final long totalPositions = datBytes / Integer.BYTES;
            return new TermRail(sideDir, field, docCount, totalPositions, arena, dat, off);
        }
        catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Iterates the set bits of {@code positions} in ascending order within the document and feeds each
     * non-{@link #NO_TERM} term id to {@code sink}.
     * <p>
     * Primary entry point for {@code CoocListener}-style aggregation: the listener marks window
     * positions in a reusable bitset across all matches of one document, then asks the rail for the
     * corresponding term ids in one call. The base offset and document length are resolved once; bits
     * at indices {@code >= docLength(docId)} are ignored, so the caller need not clip the bitset.
     * </p>
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @param positions bitset of positions to read; bits at indices {@code >= docLength(docId)} are
     * ignored
     * @param sink receives each non-{@link #NO_TERM} term id, in ascending position order
     * @throws IllegalArgumentException if {@code docId} is out of range
     * @throws NullPointerException if {@code positions} or {@code sink} is {@code null}
     */
    public void scanPositions(
        final int docId,
        final BitSet positions,
        final IntConsumer sink
    ) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(sink, "sink");
        checkDocId(docId);
        final long base = offset(docId);
        final int docLen = (int) ((offset(docId + 1) - base) / Integer.BYTES);
        if (docLen == 0)
            return;
        for (int p = positions.nextSetBit(0); p >= 0 && p < docLen; p = positions.nextSetBit(p + 1)) {
            final int id = dat.get(ValueLayout.JAVA_INT_UNALIGNED, base + (long) p * Integer.BYTES);
            if (id != NO_TERM)
                sink.accept(id);
        }
    }

    /**
     * Scans a contiguous half-open position window {@code [posLo, posHi)} within one document and feeds
     * each non-{@link #NO_TERM} term id to {@code sink}, in ascending position order.
     * <p>
     * The effective lower bound is {@code Math.max(0, posLo)} and the effective upper bound is
     * {@code Math.min(docLength(docId), posHi)}. If the resulting interval is empty, the method returns
     * without invoking {@code sink}.
     * </p>
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @param startPosition first position to include (inclusive); negative values are treated as
     * {@code 0}
     * @param endPosition one past the last position to include (exclusive); values greater than
     * {@code docLength(docId)} are clamped to it
     * @param sink receives each non-{@link #NO_TERM} term id, in ascending position order
     * @throws IllegalArgumentException if {@code docId} is out of range
     * @throws NullPointerException if {@code sink} is {@code null}
     */
    public void scanWindow(
        final int docId,
        final int startPosition,
        final int endPosition,
        final IntConsumer sink
    ) {
        Objects.requireNonNull(sink, "sink");
        checkDocId(docId);
        final long base = offset(docId);
        final int docLen = (int) ((offset(docId + 1) - base) / Integer.BYTES);
        final int lo = Math.max(0, startPosition);
        final int hi = Math.min(docLen, endPosition);
        for (int p = lo; p < hi; p++) {
            final int id = dat.get(ValueLayout.JAVA_INT_UNALIGNED, base + (long) p * Integer.BYTES);
            if (id != NO_TERM)
                sink.accept(id);
        }
    }

    /**
     * Returns the side directory from which this rail was opened.
     *
     * @return side directory path
     */
    public Path sideDir() {
        return sideDir;
    }

    /**
     * Returns the term id stored at one document position.
     *
     * @param docId Lucene doc id in {@code [0, docCount)}
     * @param position position in {@code [0, docLength(docId))}
     * @return term id, or {@link #NO_TERM} for a gap slot
     * @throws IllegalArgumentException if {@code docId} or {@code position} is out of range
     */
    public int termId(
        final int docId,
        final int position
    ) {
        checkDocId(docId);
        final long base = offset(docId);
        final int docLen = (int) ((offset(docId + 1) - base) / Integer.BYTES);
        if (position < 0 || position >= docLen) {
            throw new IllegalArgumentException(
                    "position " + position + " out of range (docLen=" + docLen + ", docId=" + docId + ")");
        }
        return dat.get(ValueLayout.JAVA_INT_UNALIGNED, base + (long) position * Integer.BYTES);
    }

    /**
     * Returns the total number of int position slots across all documents, including {@link #NO_TERM}
     * gap slots. Equal to {@code dat byte size / Integer.BYTES}.
     *
     * @return total position slot count
     */
    public long totalPositions() {
        return totalPositions;
    }

    /**
     * Validates a Lucene doc id against this rail's document count.
     *
     * @param docId doc id to validate
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    private void checkDocId(
        final int docId
    ) {
        if (docId < 0 || docId >= docCount) {
            throw new IllegalArgumentException(
                "docId " + docId + " out of range (docCount=" + docCount + ")");
        }
    }

    /**
     * Resolves the path of the data file for one field.
     *
     * @param dir side directory
     * @param field indexed field name
     * @return path of {@code <field>.rail.dat} under {@code dir}
     */
    private static Path datPath(
        final Path dir,
        final String field
    ) {
        return dir.resolve(field + ".rail.dat");
    }

    /**
     * Reads one byte offset from this rail's offset segment.
     *
     * @param index offset-table index
     * @return byte offset into the data segment
     */
    private long offset(
        final int index
    ) {
        return offset(off, index);
    }

    /**
     * Reads one native-endian long value from an offset segment.
     *
     * @param off offset segment
     * @param index long-value index
     * @return stored long value
     */
    private static long offset(
        final MemorySegment off,
        final int index
    ) {
        return off.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) index * Long.BYTES);
    }

    /**
     * Resolves the path of the offset file for one field.
     *
     * @param dir side directory
     * @param field indexed field name
     * @return path of {@code <field>.rail.off} under {@code dir}
     */
    private static Path offPath(
        final Path dir,
        final String field
    ) {
        return dir.resolve(field + ".rail.off");
    }

    /**
     * Verifies that sampled offsets are monotonically non-decreasing.
     * <p>
     * Every entry is checked up to one million offsets. Above that size, every 4096th entry plus
     * the final entry is checked to keep opening cost bounded.
     * </p>
     *
     * @param off mapped offset segment
     * @param offsetCount number of long offsets stored in the segment
     * @param offPath offset-file path used in error messages
     * @throws IOException if a sampled offset decreases
     */
    private static void validateMonotonic(
        final MemorySegment off,
        final int offsetCount,
        final Path offPath
    ) throws IOException {
        final int stride = offsetCount > 1_048_576 ? 4096 : 1;
        int previousIndex = 0;
        long previous = offset(off, 0);
        for (int index = stride; index < offsetCount; index += stride) {
            final long current = offset(off, index);
            if (current < previous) {
                throw new IOException(
                    "rail offsets not monotonic between indices " + previousIndex + " and " + index
                        + ": " + previous + " -> " + current + " in " + offPath);
            }
            previousIndex = index;
            previous = current;
        }
        final int last = offsetCount - 1;
        if (previousIndex != last) {
            final long current = offset(off, last);
            if (current < previous) {
                throw new IOException(
                    "rail offsets not monotonic between indices " + previousIndex + " and " + last
                        + ": " + previous + " -> " + current + " in " + offPath);
            }
        }
    }
}
