package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

import com.github.oeuvres.alix.util.IOUtil;
import com.github.oeuvres.alix.util.IntList;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable lookup table for one indexed field of one frozen Lucene directory.
 * <p>
 * The lexicon is persisted in two files located directly in the Lucene directory:
 * </p>
 * <ul>
 *   <li><b>{@code <field>.terms.dat}</b> — concatenated UTF-8 bytes of all terms in {@code termId} order</li>
 *   <li><b>{@code <field>.terms.off}</b> — native-endian {@code int} offsets into {@code .dat}</li>
 * </ul>
 * <p>
 * Term id 0 is reserved and represents the absence of a term (empty position). Real term ids start at 1
 * and are dense and stable for the frozen snapshot from which the lexicon was built. The id assignment
 * follows the lexicographic iteration order returned by Lucene's merged {@link TermsEnum} for the field.
 * The reserved id is stored as a zero-length phantom entry in the {@code .dat}/{@code .off} files so that
 * {@link #form(int) form(0)} returns {@code ""} and both files remain self-consistent.
 * </p>
 * <p>
 * Because ids are assigned in lexicographic byte order, the {@code .dat}/{@code .off} pair is itself a
 * sorted table. The forward direction {@code term → id} is therefore an unsigned-byte binary search over
 * that table ({@link #id(BytesRef)}); no separate index (e.g. an FST) is needed. The lookup is performed
 * directly on the stored UTF-8 bytes, with no charset conversion.
 * </p>
 * <p>
 * Both files are read fully into the Java heap on {@link #open(Path, String)} as a {@code byte[]} and an
 * {@code int[]}. At natural-language vocabulary sizes (order 10<sup>4</sup>–10<sup>5</sup> terms) this is a
 * sub-megabyte footprint. There is no native memory, no memory mapping, and no resource to release: the
 * instance is plain immutable state, safe for concurrent reads without locking, and is not
 * {@link java.io.Closeable}.
 * </p>
 * <p>
 * String lookup assumes that the caller provides the field's canonical indexed form. No analysis,
 * normalization, stemming or lower-casing is applied here.
 * </p>
 *
 * @see TermRail
 */
public final class TermLexicon {

    /** Lucene directory that contains both the index and the {@code <field>.terms.*} files. */
    private final Path indexDir;

    /** Indexed field for which this lexicon was built. */
    private final String field;

    /** Concatenation of all term bytes in term-id order. */
    private final byte[] dat;

    /**
     * Offsets into {@link #dat}; length is {@code vocabSize + 1}. For a term id {@code i}, the term bytes
     * occupy {@code dat[off[i] .. off[i + 1])}.
     */
    private final int[] off;

    /** Number of entries including the reserved id 0; valid ids span {@code [0, vocabSize)}. */
    private final int vocabSize;

    /**
     * Creates an opened lexicon backed by heap arrays.
     *
     * @param indexDir Lucene directory the lexicon was opened from
     * @param field    indexed field name
     * @param dat      concatenated term bytes in id order
     * @param off      offsets into {@code dat}, length {@code vocabSize + 1}
     */
    private TermLexicon(final Path indexDir, final String field, final byte[] dat, final int[] off) {
        this.indexDir = indexDir;
        this.field = field;
        this.dat = dat;
        this.off = off;
        this.vocabSize = off.length - 1;
    }

    /**
     * Builds the lexicon files for one field from an already opened snapshot reader.
     * <p>
     * Files are written to temporary paths first and renamed atomically on success. Stale temporary files
     * from a previous crashed write are silently removed before writing begins. If a final target file
     * already exists an exception is thrown — call {@link #exists(Path, String)} and delete manually if
     * overwrite semantics are needed. On failure both temporary and any already-committed final files are
     * removed to avoid leaving a partial file set.
     * </p>
     *
     * @param reader  snapshot reader that defines the term universe and its lexicographic order
     * @param sideDir Lucene directory that will receive the {@code <field>.terms.*} files
     * @param field   indexed field name
     * @throws IOException              if a final target file already exists or writing fails
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if any argument is null
     */
    public static void build(final IndexReader reader, final Path sideDir, final String field) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(sideDir, "sideDir");
        Objects.requireNonNull(field, "field");

        final Path datFinal = datPath(sideDir, field);
        final Path offFinal = offPath(sideDir, field);
        IOUtil.ensureAbsent(datFinal);
        IOUtil.ensureAbsent(offFinal);

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final Path datTmp = IOUtil.tmpPath(datFinal);
        final Path offTmp = IOUtil.tmpPath(offFinal);
        IOUtil.deleteIfExists(datTmp);
        IOUtil.deleteIfExists(offTmp);

        try {
            buildFiles(terms, datTmp, offTmp);
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
     * Returns {@code true} if both persisted files for {@code field} exist as regular files.
     * <p>
     * Cheap presence test only — does not validate sizes or internal consistency.
     * </p>
     *
     * @param indexDir Lucene directory
     * @param field    indexed field name
     * @return {@code true} if both files ({@code .dat}, {@code .off}) are present
     * @throws NullPointerException if either argument is null
     */
    public static boolean exists(final Path indexDir, final String field) {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");
        return Files.isRegularFile(datPath(indexDir, field))
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
     * Returns the term string for one dense term id.
     * <p>
     * {@code form(0)} returns the empty string (reserved absent-term slot). For tight loops over the full
     * vocabulary that can avoid {@link String} allocation, prefer
     * {@link #formBytes(int, BytesRefBuilder)} with a caller-owned buffer.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return decoded UTF-8 term string, never null; empty for the reserved id 0
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    public String form(final int termId) {
        checkTermId(termId);
        final int start = off[termId];
        final int length = off[termId + 1] - start;
        return new String(dat, start, length, StandardCharsets.UTF_8);
    }

    /**
     * Copies the raw UTF-8 bytes of one term into a caller-provided reusable buffer.
     * <p>
     * This avoids allocation when called in a loop. The bytes are copied directly from the in-heap
     * {@code .dat} array.
     * </p>
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @param reuse  destination buffer that will receive the term bytes; grown automatically if needed
     * @return {@code reuse.get()} after the copy, valid until the next call on the same buffer
     * @throws IllegalArgumentException if {@code termId} is out of range
     * @throws NullPointerException     if {@code reuse} is null
     */
    public BytesRef formBytes(final int termId, final BytesRefBuilder reuse) {
        checkTermId(termId);
        Objects.requireNonNull(reuse, "reuse");
        final int start = off[termId];
        final int length = off[termId + 1] - start;
        reuse.grow(length);
        System.arraycopy(dat, start, reuse.bytes(), 0, length);
        reuse.setLength(length);
        return reuse.get();
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as raw UTF-8 bytes.
     * <p>
     * Binary search over the lexicographically sorted term table, comparing {@code term} against the
     * stored bytes with unsigned-byte semantics matching {@link BytesRef#compareTo(BytesRef)}. The reserved
     * id 0 (empty term) is never returned for a non-empty query.
     * </p>
     *
     * @param term canonical indexed term as UTF-8 bytes
     * @return dense term id in {@code [1, vocabSize)}, or {@code -1} if the term is absent
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final BytesRef term) {
        Objects.requireNonNull(term, "term");
        int lo = 1;
        int hi = vocabSize - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int c = compareToTerm(mid, term);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /**
     * Looks up the dense term id for a canonical indexed term given as a Java string.
     * <p>
     * The string is encoded to UTF-8 and delegated to {@link #id(BytesRef)}. No analysis or normalization
     * is applied.
     * </p>
     *
     * @param term canonical indexed term form, must match the analyzer output exactly
     * @return dense term id in {@code [1, vocabSize)}, or {@code -1} if the term is absent
     * @throws NullPointerException if {@code term} is null
     */
    public int id(final String term) {
        Objects.requireNonNull(term, "term");
        final BytesRefBuilder bytes = new BytesRefBuilder();
        bytes.copyChars(term);
        return id(bytes.get());
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
     * Both files are read fully into the heap. The returned instance holds no native resources and does
     * not need to be closed. On open, the following consistency checks are performed:
     * </p>
     * <ul>
     *   <li>Both files must exist as regular files.</li>
     *   <li>The offsets file size must be a multiple of 4 bytes and contain at least 3 entries
     *       (the reserved phantom slot plus at least one real term).</li>
     *   <li>The first offset must be 0 and the last must equal the data file size.</li>
     *   <li>Offsets must be monotonically non-decreasing.</li>
     * </ul>
     *
     * @param indexDir Lucene directory that contains the index and the lexicon files
     * @param field    indexed field name
     * @return opened lexicon
     * @throws IOException          if a file is missing, sizes are inconsistent, or offsets are corrupt
     * @throws NullPointerException if either argument is null
     */
    public static TermLexicon open(final Path indexDir, final String field) throws IOException {
        Objects.requireNonNull(indexDir, "indexDir");
        Objects.requireNonNull(field, "field");

        final Path datPath = datPath(indexDir, field);
        final Path offPath = offPath(indexDir, field);
        IOUtil.ensureRegularFile(datPath);
        IOUtil.ensureRegularFile(offPath);

        final byte[] dat = Files.readAllBytes(datPath);
        final byte[] offBytes = Files.readAllBytes(offPath);

        if ((offBytes.length & 3) != 0) {
            throw new IOException("Invalid offsets file (size not a multiple of 4 bytes): " + offPath);
        }
        final int n = offBytes.length / Integer.BYTES;
        if (n < 3) {
            throw new IOException("Invalid offsets file (need at least 3 offsets: phantom + one real term): " + offPath);
        }
        final int[] off = new int[n];
        ByteBuffer.wrap(offBytes).order(ByteOrder.nativeOrder()).asIntBuffer().get(off);

        if (off[0] != 0) {
            throw new IOException("Invalid offsets file, off[0] != 0: " + offPath);
        }
        if (off[n - 1] != dat.length) {
            throw new IOException("Offsets/data mismatch for field '" + field
                + "': last offset=" + off[n - 1] + ", data length=" + dat.length);
        }
        validateMonotonic(off, offPath);

        return new TermLexicon(indexDir, field, dat, off);
    }

    /**
     * Opens the lexicon, building the files first if they do not exist, using an already opened reader.
     * <p>
     * The reader is used only for building (ignored if files exist); it is not closed by this method.
     * </p>
     *
     * @param reader  snapshot reader for building (ignored if files exist)
     * @param sideDir Lucene directory that will receive the files
     * @param field   indexed field name
     * @return opened lexicon
     * @throws IOException if building or opening fails
     */
    public static TermLexicon openOrBuild(final IndexReader reader, final Path sideDir, final String field)
        throws IOException {
        if (!exists(sideDir, field)) {
            build(reader, sideDir, field);
        }
        return open(sideDir, field);
    }

    /**
     * Resolves the terms in a query to their term ids, restricted to this lexicon's field.
     * <p>
     * Terms the lexicon does not know (e.g. never indexed, or belonging to another field) are silently
     * dropped. The query must already be rewritten via {@code IndexSearcher.rewrite(Query)}.
     * </p>
     *
     * @param query a rewritten {@link Query}
     * @return distinct term ids in query-visit order; unknown terms omitted
     */
    public int[] termIds(final Query query) {
        final IntList ids = new IntList();
        query.visit(new QueryVisitor() {
            @Override
            public void consumeTerms(final Query q, final Term... ts) {
                for (final Term t : ts) {
                    final int termId = id(t.bytes());
                    if (termId >= 0) {
                        ids.push(termId);
                    }
                }
            }
        });
        return ids.toArray();
    }

    /**
     * Returns the number of entries in the lexicon, including the reserved id 0.
     * <p>
     * Real terms occupy ids {@code [1, vocabSize)}; the count of real terms is {@code vocabSize() - 1}.
     * </p>
     *
     * @return vocabulary size (always &gt; 1 for a valid lexicon)
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Writes the two persisted files from the field's merged term dictionary.
     * <p>
     * Iterates the {@link TermsEnum} in lexicographic order, assigning a dense id to each term starting
     * from 1. Id 0 is reserved as an absent-term sentinel: the offset file begins with a zero-length
     * phantom entry ({@code off[0] == off[1] == 0}) so that downstream consumers can use 0 to mean
     * "no term at this position".
     * </p>
     *
     * @param terms   merged terms for the field
     * @param datPath target path for the concatenated term bytes
     * @param offPath target path for the native-endian offsets
     * @throws IOException if the field has no terms, writing fails, or 32-bit limits are exceeded
     */
    private static void buildFiles(final Terms terms, final Path datPath, final Path offPath) throws IOException {
        final IntList offsets = new IntList();
        offsets.push(0);                       // off[0]: start of phantom empty-term slot
        offsets.push(0);                       // off[1]: end of phantom — zero length

        int termId = 1;
        int datPos = 0;

        try (OutputStream datOs = new BufferedOutputStream(
                 Files.newOutputStream(datPath, StandardOpenOption.CREATE_NEW))) {
            final TermsEnum te = terms.iterator();
            BytesRef term;
            while ((term = te.next()) != null) {
                if (termId == Integer.MAX_VALUE) {
                    throw new IOException("Too many terms for int term ids");
                }
                if (datPos > Integer.MAX_VALUE - term.length) {
                    throw new IOException("Term bytes exceed 2 GiB; 32-bit offsets insufficient");
                }
                datOs.write(term.bytes, term.offset, term.length);
                datPos += term.length;
                offsets.push(datPos);
                termId++;
            }
        }

        if (termId == 1) {
            throw new IOException("Field has no terms; cannot build lexicon");
        }

        final int[] offArr = offsets.toArray();
        final ByteBuffer bb = ByteBuffer.allocate(offArr.length * Integer.BYTES).order(ByteOrder.nativeOrder());
        bb.asIntBuffer().put(offArr);
        Files.write(offPath, bb.array(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
     * Compares the stored term at {@code termId} against {@code term} with unsigned-byte lexicographic
     * order, matching {@link BytesRef#compareTo(BytesRef)} and the order in which ids were assigned.
     *
     * @param termId stored term id to compare
     * @param term   query term as UTF-8 bytes
     * @return negative, zero, or positive if the stored term is respectively less than, equal to, or
     *         greater than {@code term}
     */
    private int compareToTerm(final int termId, final BytesRef term) {
        final int start = off[termId];
        final int end = off[termId + 1];
        return Arrays.compareUnsigned(
            dat, start, end,
            term.bytes, term.offset, term.offset + term.length);
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
     * Verifies that the offsets are monotonically non-decreasing. The full array is scanned; at lexicon
     * sizes this is negligible.
     *
     * @param off     in-heap offsets
     * @param offPath path to the offsets file, used only in error messages
     * @throws IOException if any pair of consecutive offsets is non-monotonic
     */
    private static void validateMonotonic(final int[] off, final Path offPath) throws IOException {
        for (int i = 1; i < off.length; i++) {
            if (off[i] < off[i - 1]) {
                throw new IOException("Offsets decrease at index " + i + ": " + offPath);
            }
        }
    }
}
