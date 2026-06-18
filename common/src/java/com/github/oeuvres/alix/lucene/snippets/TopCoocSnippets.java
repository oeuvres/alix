package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms.Buffers;
import com.github.oeuvres.alix.util.IntList;

/**
 * {@link SnippetsConsumer} accumulating per-term cooccurrence counts in a fixed-width window
 * around each snippet produced by {@link SpanWalker}.
 * <p>
 * For every snippet at positions {@code [snipStart, snipEnd)} the listener marks the contiguous
 * range {@code [max(0, snipStart - left), snipEnd + right)} in a per-document {@link BitSet}.
 * After all snippets of a document have been seen, the marked positions are resolved to term ids
 * via {@link TermRail#scanPositions}, and each resolved term id is written into the
 * {@link Buffers} bound via {@link #bindTo(Buffers)}.
 * </p>
 * <h2>Pivot exclusion</h2>
 * <p>
 * Term ids passed to {@link #setPivotIds(int[])} are excluded from the count <em>everywhere</em>
 * in the window, not only inside the matched span. This is by design, and supports an iterative
 * query-refinement workflow: a user running {@code SpanNear("foo ... bar")} wants {@code baz} to
 * surface as a cooccurrent so the next query can be {@code SpanNear("foo ... bar ... baz")}, but
 * does not want {@code foo} or {@code bar} themselves to surface as cooccurrents even when extra
 * occurrences of them fall in the slop window before or after the snippet. Position-based
 * exclusion of the matched span alone would surface those extras and is therefore not used here.
 * </p>
 * <p>
 * The pivot set is held sorted and deduplicated and probed with {@link Arrays#binarySearch}.
 * {@link #setPivotIds(int[])} normalises its argument via {@link IntList#uniq(int[])}; callers
 * may pass any int array.
 * </p>
 * <h2>Per-document deduplication for document frequency</h2>
 * <p>
 * A vocabulary-sized bitset tracks which term ids have already been counted in the current
 * document, so {@link Buffers#termDocs()} is incremented at most once per (term, document) pair
 * while {@link Buffers#termFreq()} is incremented per occurrence.
 * </p>
 * <h2>Lifecycle</h2>
 * <p>
 * {@link #bindTo(Buffers)} and {@link #setPivotIds(int[])} must both be called before the walk
 * starts. {@link #reset()} clears {@link #coocTokens()} and {@link #coocDocsTotal()} between
 * walks; the bound {@link Buffers} and pivot ids persist and must be replaced with another
 * {@code bindTo} / {@code setPivotIds} call if they need to change.
 * </p>
 * <p>
 * After the walk, {@link #coocTokens()} and {@link #coocDocsTotal()} are the focus-side totals
 * used as denominators in keyness scoring.
 * </p>
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class TopCoocSnippets implements SnippetsConsumer
{
    /** Bound focus buffers; {@code null} until {@link #bindTo(Buffers)} is called. */
    private Buffers buffers;

    /** Number of documents that contributed at least one non-pivot cooccurrence position. */
    private int coocDocsTotal;

    /** Total non-pivot non-gap rail positions counted across all documents. */
    private long coocTokens;

    /** Whether the current document contributed at least one non-pivot cooccurrence position. */
    private boolean docContributed;

    /** Field statistics for the pivot field; used for vocabulary size and max document width. */
    private final TermStats fieldStats;

    /** Number of context positions to read on the left of each snippet. */
    private final int left;

    /** Forward positional rail for the pivot field. */
    private final TermRail rail;

    /** Number of context positions to read on the right of each snippet. */
    private final int right;

    /** Per-document set of term ids already counted toward {@link Buffers#termDocs()}. */
    private final BitSet termSeen;

    /** Per-document accumulated window positions across all snippets of the document. */
    private final BitSet windowMask;

    /**
     * Constructs a cooccurrence listener.
     *
     * @param fieldStats field statistics for the pivot field
     * @param rail forward positional rail for the same field
     * @param left context width on the left of each snippet, in positions; must be
     * {@code >= 0}
     * @param right context width on the right of each snippet, in positions; must be
     * {@code >= 0}
     * @throws IllegalArgumentException if {@code left} or {@code right} is negative
     * @throws NullPointerException if {@code fieldStats} or {@code rail} is {@code null}
     */
    public TopCoocSnippets(
        final TermStats fieldStats,
        final TermRail rail,
        final int left,
        final int right
    ) {
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.rail = Objects.requireNonNull(rail, "rail");
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException(
                    "left and right must be >= 0; got left=" + left + ", right=" + right);
        }
        this.left = left;
        this.right = right;
        this.windowMask = new BitSet(fieldStats.maxWidth());
        this.termSeen = new BitSet(fieldStats.vocabSize());
    }

    /**
     * Binds this listener to a {@link Buffers} instance obtained from a
     * {@link com.github.oeuvres.alix.lucene.terms.TopTerms}. Must be called before the walk
     * starts. Does not touch {@link #coocTokens()} or {@link #coocDocsTotal()}; call
     * {@link #reset()} explicitly when reusing the listener across walks.
     *
     * @param buffers focus buffers to write into; lengths must equal
     * {@code fieldStats.vocabSize()}
     * @throws NullPointerException if {@code buffers} is {@code null}
     * @throws IllegalArgumentException if buffer lengths do not match
     * {@code fieldStats.vocabSize()}
     */
    public TopCoocSnippets bindTo(
        final Buffers buffers
    ) {
        Objects.requireNonNull(buffers, "buffers");
        final int vocab = fieldStats.vocabSize();
        if (buffers.termFreq().length != vocab || buffers.termDocs().length != vocab) {
            throw new IllegalArgumentException(
                    "buffer length mismatch: vocabSize=" + vocab + ", termFreq.length=" + buffers.termFreq().length
                            + ", termDocs.length=" + buffers.termDocs().length);
        }
        this.buffers = buffers;
        return this;
    }

    /**
     * Returns the number of documents that contributed at least one non-pivot cooccurrence
     * position.
     *
     * @return focus document count
     */
    public int coocDocsTotal() {
        return coocDocsTotal;
    }

    /**
     * Returns the total non-pivot non-gap rail positions counted across all documents. Used as
     * the focus-side denominator in keyness scoring.
     *
     * @return total cooccurrence token count
     */
    public long coocTokens() {
        return coocTokens;
    }

    @Override
    public void docSnippets(
        final int docId,
        final Snippets snippets
    )
        throws IOException {
        windowMask.clear();
        termSeen.clear();
        docContributed = false;

        final int snipCount = snippets.count();
        for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
            final int snipStartPosition = snippets.snipStartPosition(snipOrd);
            final int snipEndPosition = snippets.snipEndPosition(snipOrd);
            final int winStartPosition = Math.max(0, snipStartPosition - left);
            final int winEndPosition = snipEndPosition + right;
            windowMask.set(winStartPosition, winEndPosition);
        }

        final long[] termFreq = buffers.termFreq();
        final int[] termDocs = buffers.termDocs();
        rail.scanPositions(docId, windowMask, termId -> {
            termFreq[termId]++;
            coocTokens++;
            if (!termSeen.get(termId)) {
                termSeen.set(termId);
                termDocs[termId]++;
            }
            docContributed = true;
        });

        if (docContributed) {
            coocDocsTotal++;
        }
    }

    /**
     * Removes pivot contributions accumulated during the walk: subtracts pivot
     * occurrences from {@link #coocTokens()} and clears their per-term buffers so
     * they cannot surface in the ranking. Call once, after the walk and before the
     * totals are read.
     */
    public void subtractPivots(
        final int[] pivotIds
    ) {
        if (pivotIds == null) return;
        if (pivotIds.length < 1) return;
        final long[] termFreq = buffers.termFreq();
        final int[] termDocs = buffers.termDocs();
        long pivotTokens = 0L;
        for (final int p : pivotIds) {
            pivotTokens += termFreq[p];
            termFreq[p] = 0L;
            termDocs[p] = 0;
        }
        coocTokens -= pivotTokens;
    }

    /**
     * Clears the accumulators {@link #coocTokens()} and {@link #coocDocsTotal()}. The bound
     * {@link Buffers} and pivot ids are preserved.
     */
    public void reset() {
        coocTokens = 0L;
        coocDocsTotal = 0;
    }
}
