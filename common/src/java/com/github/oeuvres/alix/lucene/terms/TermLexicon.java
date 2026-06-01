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

import com.github.oeuvres.alix.util.IntList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable in-memory lookup table for one indexed field of one frozen Lucene directory.
 * <p>
 * The lexicon holds two heap arrays:
 * </p>
 * <ul>
 *   <li>{@code dat} — concatenated UTF-8 bytes of all terms in {@code termId} order;</li>
 *   <li>{@code off} — {@code int} offsets into {@code dat}, length {@code vocabSize + 1}.</li>
 * </ul>
 * <p>
 * Term id 0 is reserved and represents the absence of a term (empty position). Real term ids start at 1
 * and are dense. They are assigned in the lexicographic iteration order of the field's merged
 * {@link TermsEnum}; id 0 is a zero-length phantom entry ({@code off[0] == off[1] == 0}) so that
 * {@link #form(int) form(0)} returns {@code ""}.
 * </p>
 * <p>
 * Because ids follow lexicographic byte order, {@code dat}/{@code off} is itself a sorted table, so the
 * forward direction {@code term -> id} is an unsigned-byte binary search over it ({@link #id(BytesRef)}),
 * performed directly on the stored UTF-8 with no charset conversion and no auxiliary index.
 * </p>
 * <p>
 * The lexicon is <b>not persisted</b>. It is rebuilt from the reader each time it is constructed, in a
 * single {@link TermsEnum} pass. The reader is used only during construction and is not retained; the
 * resulting instance is plain immutable heap state, safe for concurrent reads without locking, and holds
 * no native resources to release.
 * </p>
 * <p>
 * The term ids produced here are meaningful only against the exact term ordering of the reader they were
 * built from. The same property is what a {@link TermRail} encodes. Keeping lexicon and rail consistent is
 * therefore a matter of building both against the same index, and of validating the rail against the
 * index it belongs to — not against the lexicon. This class deliberately stores nothing that would let it
 * be paired with the wrong index.
 * </p>
 * <p>
 * String lookup assumes that the caller provides the field's canonical indexed form. No analysis,
 * normalization, stemming or lower-casing is applied here.
 * </p>
 *
 * @see TermRail
 */
public final class TermLexicon {

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
     * Builds the lexicon for one field by reading the field's merged term dictionary in lexicographic
     * order. The reader is consulted only here and is not retained.
     *
     * @param reader snapshot reader that defines the term universe and its lexicographic order
     * @param field  indexed field name
     * @throws IOException              if reading the term dictionary fails, or 32-bit limits are exceeded
     * @throws IllegalArgumentException if the field has no terms in the reader
     * @throws NullPointerException     if either argument is null
     */
    public TermLexicon(final IndexReader reader, final String field) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(field, "field");
        this.field = field;

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final IntList offsets = new IntList();
        offsets.push(0);                       // off[0]: start of phantom empty-term slot
        offsets.push(0);                       // off[1]: end of phantom — zero length
        final ByteArrayOutputStream datOut = new ByteArrayOutputStream(1 << 20);

        int termId = 1;
        int datPos = 0;
        final TermsEnum te = terms.iterator();
        BytesRef term;
        while ((term = te.next()) != null) {
            if (termId == Integer.MAX_VALUE) {
                throw new IOException("Too many terms for int term ids");
            }
            if (datPos > Integer.MAX_VALUE - term.length) {
                throw new IOException("Term bytes exceed 2 GiB; 32-bit offsets insufficient");
            }
            datOut.write(term.bytes, term.offset, term.length);
            datPos += term.length;
            offsets.push(datPos);
            termId++;
        }
        if (termId == 1) {
            throw new IllegalArgumentException("Field has no terms: " + field);
        }

        this.dat = datOut.toByteArray();
        this.off = offsets.toArray();
        this.vocabSize = off.length - 1;
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
     * {@code dat} array.
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
     * stored bytes with unsigned-byte semantics matching {@link BytesRef#compareTo(BytesRef)} and the
     * order in which ids were assigned. The reserved id 0 (empty term) is never returned for a non-empty
     * query.
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
}
