package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TopTerms.Buffers;

/**
 * {@link SpanListener} that accumulates per-term cooccurrence counts in a fixed-width window around
 * each pivot match.
 *
 * <p>
 * For each match {@code [start, end)} delivered by {@link SpanWalker}, the listener marks the
 * context positions {@code [max(0, start - left), start)} and
 * {@code [end, min(docWidth, end + right))} in a per-document bitset. After the document is
 * exhausted, the marked positions are resolved to term ids via {@link TermRail#scanPositions} and
 * counts are written into a {@link FocusBuffers} obtained from a
 * {@link com.github.oeuvres.alix.lucene.terms.TopTerms} instance.
 * </p>
 *
 * <h2>Pivot self-exclusion</h2>
 *
 * <p>
 * Pivot positions of every match in the document are tracked in a separate bitset and removed from
 * the window mask before the rail scan. With many pivot matches close together (large slop, or
 * {@code SpanOr} of co-occurring terms), pivot positions naturally land in another match's window;
 * without this exclusion they would inflate the cooccurrence counts and tilt the focus token
 * denominator, biasing every score.
 * </p>
 *
 * <h2>Per-document deduplication for document frequency</h2>
 *
 * <p>
 * A vocabulary-sized bitset records which term ids have already been counted in the current
 * document, so {@link FocusBuffers#termDocs} is incremented at most once per (term, document) pair
 * while {@link FocusBuffers#termFreq} is incremented per occurrence.
 * </p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * The listener is bound to a {@link FocusBuffers} via {@link #bindTo(FocusBuffers)} before the walk
 * starts. {@link com.github.oeuvres.alix.lucene.terms.TopTerms#coocs} performs the binding, runs
 * the walk, and reads back {@link #coocTokens()} and {@link #coocDocsTotal()}.
 * </p>
 *
 * <p>
 * This class is not thread-safe and is single-use per walk: a fresh instance, or a fresh
 * {@link #bindTo(FocusBuffers)}, is required for each cooccurrence query.
 * </p>
 */
public final class CoocListener implements SpanListener
{
    /**
     * Number of documents that contributed at least one window position resolving to a real term.
     */
    private int coocDocsTotal;
    
    /** Total non-gap positions visited across all documents. */
    private long coocTokens;
    
    /** Field statistics for the pivot field; used for vocabulary size and max document width. */
    private final TermStats fieldStats;
    
    /** Number of context positions to read on the right of each match. */
    private final int left;
    
    /** Pivot positions in the current document, accumulated across all matches. */
    private final BitSet pivotMask;
    
    /** Forward positional rail for the pivot field. */
    private final TermRail rail;
    
    /** Number of context positions to read on the left of each match. */
    private final int right;
    
    /** Per-document set of term ids already counted toward {@link FocusBuffers#termDocs}. */
    private final BitSet termSeen;
    
    /** Bound focus buffers; {@code null} until {@link #bindTo(FocusBuffers)} is called. */
    private Buffers buffers;
    
    /** Whether the current document contributed at least one cooc position. */
    private boolean docContributed;
    
    /** Window positions in the current document, accumulated across all matches. */
    private final BitSet windowMask;
    
    private static final int[] EMPTY_INT = new int[0];
    /** Do not count pivots occurrences */
    private int[] pivotIds = EMPTY_INT;
    
    /**
     * Constructs a cooccurrence listener.
     *
     * @param fieldStats field statistics for the pivot field
     * @param rail       forward positional rail for the same field
     * @param left       context width on the left of each match, in positions; must be {@code >= 0}
     * @param right      context width on the right of each match, in positions; must be
     *                   {@code >= 0}
     * @throws IllegalArgumentException if {@code left} or {@code right} is negative, or if both are
     *                                  zero
     * @throws NullPointerException     if {@code fieldStats} or {@code rail} is {@code null}
     */
    public CoocListener(
            final TermStats fieldStats,
            final TermRail rail,
            final int left,
            final int right)
    {
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.rail = Objects.requireNonNull(rail, "rail");
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("left and right must be >= 0; got left=" + left + ", right=" + right);
        }
        this.left = left;
        this.right = right;
        this.windowMask = new BitSet(fieldStats.maxWidth());
        this.pivotMask = new BitSet(fieldStats.maxWidth());
        this.termSeen = new BitSet(fieldStats.vocabSize());
    }
    
    /**
     * Provide the set of pivot term ids that should be
     * excluded from co-occurrence accounting. Called in
     * {@link }
     * 
     * @throws NullPointerException if {@code buffers} is {@code null}
     */
    protected void setPivotIds(final int[] pivotIds)
    {
        Objects.requireNonNull(pivotIds, "pivotIds");
        this.pivotIds = pivotIds;
    }
    
    /**
     * Binds this listener to a {@link FocusBuffers} obtained from a
     * {@link TopTerms} instance. Must be called before the
     * walk starts.
     *
     * @param buffers      focus buffers to write into
     * @param pivotTermIds term ids of the query terms; positions resolving to one of these ids
     *                     are excluded from both {@code termFreq}/{@code termDocs} and
     *                     {@code coocTokens}. May be empty but not {@code null}.
     * @throws NullPointerException     if {@code buffers} is {@code null}
     * @throws IllegalArgumentException if buffer lengths do not match
     *                                  {@code fieldStats.vocabSize()}
     */
    public void bindTo(final Buffers buffers)
    {
        Objects.requireNonNull(buffers, "buffers");
        final int vocab = fieldStats.vocabSize();
        if (buffers.termFreq().length != vocab || buffers.termDocs().length != vocab) {
            throw new IllegalArgumentException(
                    "buffer length mismatch: vocabSize=" + vocab
                            + ", termFreq.length=" + buffers.termFreq().length
                            + ", termDocs.length=" + buffers.termDocs().length);
        }
        this.buffers = buffers;
    }
    
    /**
     * Returns the number of documents that contributed at least one cooccurrence position.
     *
     * @return focus document count
     */
    public int coocDocsTotal()
    {
        return coocDocsTotal;
    }
    
    /**
     * Returns the total non-gap positions visited across all documents. Used as the focus-side
     * denominator in keyness scoring.
     *
     * @return total cooccurrence token count
     */
    public long coocTokens()
    {
        return coocTokens;
    }
    
    @Override
    public void endDoc(final int spanCount) throws IOException
    {
        if (buffers == null) {
            throw new IllegalStateException("CoocListener not bound; call bindTo(FocusBuffers) before the walk");
        }
        windowMask.andNot(pivotMask);
        if (windowMask.isEmpty())
            return;
        
        final long[] termFreq = buffers.termFreq();
        final int[] termDocs = buffers.termDocs();
        final int docId = lastDocId;
        
        rail.scanPositions(docId, windowMask, termId -> {
            if (Arrays.binarySearch(pivotIds, termId) >= 0)
                return;
            termFreq[termId]++;
            coocTokens++;
            if (!termSeen.get(termId)) {
                termSeen.set(termId);
                termDocs[termId]++;
            }
            docContributed = true;
        });
        
        if (docContributed)
            coocDocsTotal++;
    }
    
    @Override
    public boolean span(final SpanMatch match) throws IOException
    {
        final int start = match.startPosition();
        final int end = match.endPosition();
        // left context: [max(0, start - left), start)
        if (left > 0 && start > 0) {
            windowMask.set(Math.max(0, start - left), start);
        }
        // right context: [end, end + right) — clamped by scanPositions
        if (right > 0) {
            windowMask.set(end, end + right);
        }
        // pivot positions: [start, end)
        pivotMask.set(start, end);
        return true;
    }
    
    @Override
    public void start() throws IOException
    {
        if (buffers == null) {
            throw new IllegalStateException("CoocListener not bound; call bindTo(FocusBuffers) before the walk");
        }
        coocTokens = 0L;
        coocDocsTotal = 0;
    }
    
    @Override
    public void startDoc(final int docId) throws IOException
    {
        this.lastDocId = docId;
        windowMask.clear();
        pivotMask.clear();
        termSeen.clear();
        docContributed = false;
    }
    
    /** Last docId seen, captured in {@link #startDoc(int)} and consumed in {@link #endDoc(int)}. */
    private int lastDocId = -1;
}
