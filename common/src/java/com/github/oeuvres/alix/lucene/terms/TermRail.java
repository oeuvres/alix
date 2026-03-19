package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermVectors;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.util.NumWriter;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.SideFiles;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
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
 * Close is best-effort and delegates to {@link SideFiles#unmap(MappedByteBuffer)}.
 * </p>
 *
 * @see TermLexicon
 */
public final class TermRail implements Closeable {

    /** Directory that contains both the index and the rail files. */
    private final Path dataDir;

    /** Mapped buffer for {@code <field>.rail.dat}; retained for explicit unmapping on close. */
    private final MappedByteBuffer datBuf;

    /** Number of documents represented by this rail. */
    private final int docCount;

    /** Int view over {@link #datBuf}. */
    private final IntBuffer dat;

    /** Indexed field covered by this rail. */
    private final String field;

    /** Int view over {@link #offBuf}. */
    private final IntBuffer off;

    /** Mapped buffer for {@code <field>.rail.off}; retained for explicit unmapping on close. */
    private final MappedByteBuffer offBuf;

    /** Total number of stored positions across all documents. */
    private final int totalPositions;

    /** Maximum tolerated mtime difference between the two rail files. */
    private static final long MTIME_TOLERANCE_MS = 5_000L;

    private TermRail(
        final Path dataDir,
        final String field,
        final MappedByteBuffer datBuf,
        final IntBuffer dat,
        final MappedByteBuffer offBuf,
        final IntBuffer off,
        final int docCount,
        final int totalPositions
    ) {
        this.dataDir = dataDir;
        this.field = field;
        this.datBuf = datBuf;
        this.dat = dat;
        this.offBuf = offBuf;
        this.off = off;
        this.docCount = docCount;
        this.totalPositions = totalPositions;
    }

    /**
     * Build a term-id rail for the given field and write it to disk.
     *
     * <p>Produces two files under {@code dataDir}:</p>
     * <ul>
     *   <li><b>offsets</b> ({@link #offPath offPath(dataDir, field)}) —
     *       a {@code long[maxDoc + 1]} array of byte offsets into the data file.
     *       {@code offsets[docId]} is the byte position where the rail for
     *       {@code docId} begins; the rail length in ints is
     *       {@code (offsets[docId + 1] - offsets[docId]) / Integer.BYTES}.
     *       Deleted documents and documents without the field have
     *       {@code offsets[docId] == offsets[docId + 1]}.</li>
     *   <li><b>data</b> ({@link #datPath datPath(dataDir, field)}) —
     *       a flat {@code int[]} rail where each slot holds the
     *       {@link TermLexicon} id of the term at that position,
     *       or {@code 0} for unfilled positions (position gaps).
     *       Files may exceed 2&nbsp;GB.</li>
     * </ul>
     *
     * <p>Both files are written to temporary paths first and atomically
     * renamed on success. On failure, all temporary and final files are
     * cleaned up.</p>
     *
     * @param dataDir  directory for the output files.
     * @param reader   index reader (must have term vectors with positions
     *                 for {@code field}).
     * @param field    indexed field name.
     * @param lexicon  FST lexicon mapping terms to integer ids;
     *                 id {@code 0} should be reserved as a sentinel
     *                 (no term at position).
     * @param report   progress reporter; may be {@code null}.
     * @throws IOException if an I/O error occurs during reading or writing.
     */
    public static void build(
        final Path dataDir,
        final IndexReader reader,
        final String field,
        final TermLexicon lexicon,
        Report report
    ) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(lexicon, "lexicon");
        if (report == null) report = Report.ReportNull.INSTANCE;
        final FieldInfo fi = FieldInfos.getMergedFieldInfos(reader).fieldInfo(field);
        if (fi == null || !fi.hasTermVectors()) {
            throw new IllegalArgumentException("field \"" + field + "\" has no term vectors");
        }

        final Path offFinal = offPath(dataDir, field);
        SideFiles.ensureAbsent(offFinal);
        final Path offTmp = SideFiles.tmpPath(offFinal);
        SideFiles.deleteIfExists(offTmp);

        final int maxDoc = reader.maxDoc();
        final BitSet liveDocs = FieldStats.liveDocs(reader);

        final int[] docWidths = FieldStats.docWidths(reader, field, report);
        int widthMax = 0;
        final long[] offsets = new long[maxDoc + 1];
        long totalBytes = 0L;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (docWidths[docId] <= 0 || !liveDocs.get(docId)) {
                docWidths[docId] = 0; // zero in-place so the write loop skips this doc
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

        final Path datFinal = datPath(dataDir, field);
        SideFiles.ensureAbsent(datFinal);
        final Path datTmp = SideFiles.tmpPath(datFinal);
        SideFiles.deleteIfExists(datTmp);

        try (NumWriter railWriter = NumWriter.open(datTmp, totalBytes)) {
            final int[] rail = new int[widthMax];
            final TermVectors termVectors = reader.termVectors();
            for (int docId = 0; docId < maxDoc; docId++) {
                final int docWidth = docWidths[docId];
                if (docWidth == 0) {
                    continue;
                }
                final Fields fields = termVectors.get(docId);
                if (fields == null) {
                    continue;
                }
                final Terms terms = fields.terms(field);
                if (terms == null) {
                    continue;
                }
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
            SideFiles.moveTemp(datTmp, datFinal);
            SideFiles.moveTemp(offTmp, offFinal);
        } catch (IOException | RuntimeException e) {
            SideFiles.deleteIfExists(datTmp);
            SideFiles.deleteIfExists(offTmp);
            SideFiles.deleteIfExists(datFinal);
            SideFiles.deleteIfExists(offFinal);
            throw e;
        }
    }

    /**
     * Releases the mapped regions.
     * <p>
     * After close, all accessors on this instance are invalid.
     * </p>
     */
    @Override
    public void close() {
        SideFiles.unmap(datBuf);
        SideFiles.unmap(offBuf);
    }

    /**
     * Returns the number of documents represented by this rail.
     */
    public int docCount() {
        return docCount;
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
     * Returns the field name covered by this rail.
     */
    public String field() {
        return field;
    }

    /**
     * Returns the Lucene directory from which this rail was opened.
     */
    public Path indexDir() {
        return dataDir;
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
    
        SideFiles.ensureRegularFile(datPath);
        SideFiles.ensureRegularFile(offPath);
        SideFiles.checkMtimeCoherence(MTIME_TOLERANCE_MS, datPath, offPath);
    
        final MappedByteBuffer datMapped = SideFiles.mapReadOnly(datPath);
        datMapped.order(ByteOrder.nativeOrder());
        final MappedByteBuffer offMapped = SideFiles.mapReadOnly(offPath);
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
            SideFiles.unmap(datMapped);
            SideFiles.unmap(offMapped);
            throw e;
        }
    }

    private void checkDocId(final int docId) {
        if (docId < 0 || docId >= docCount) {
            throw new IllegalArgumentException(
                "docId out of range: " + docId + " (docCount=" + docCount + ")"
            );
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
}
