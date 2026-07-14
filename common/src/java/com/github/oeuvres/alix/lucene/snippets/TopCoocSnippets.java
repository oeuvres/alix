package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms.Population;

/**
 * Accumulates occurrence, document, and context counts in windows around the
 * merged snippets produced by {@link SpanWalker}.
 *
 * <p>
 * Each merged snippet is one independent context. For a snippet occupying
 * {@code [snipStart, snipEnd)}, this consumer scans the half-open window
 * {@code [snipStart - left, snipEnd + right)} through
 * {@link TermRail#scanWindow(int, int, int, java.util.function.IntConsumer)}.
 * The rail clips the window to the document bounds.
 * </p>
 *
 * <p>
 * Three per-term statistics are collected:
 * </p>
 * <ul>
 * <li>{@link Population#termFreq()} counts occurrences across snippet contexts.
 * An indexed position covered by two distinct context windows is counted once
 * in each context. This is the same counting model as {@link CoocMatSnippets},
 * so the count equals the matrix identity cell for an identical walk.</li>
 * <li>{@link Population#termContexts()} counts merged snippets containing the
 * term, once per {@code (term, snippet)} pair.</li>
 * <li>{@link Population#termDocs()} counts Lucene documents containing the term
 * in at least one scanned snippet window, once per {@code (term, document)}
 * pair.</li>
 * </ul>
 *
 * <p>
 * Bind with {@link #bindTo(Population)} when possible. After the walk and any
 * pivot subtraction, {@link #complete()} publishes token, document, and context
 * totals to the same {@code TopTerms} population. The legacy
 * {@link #bindTo(Buffers)} overload remains available for callers that publish
 * totals separately.
 * </p>
 *
 * <p>
 * Pivot occurrences are collected during the walk and may be removed afterward
 * with {@link #subtractPivots(int[])}. This clears all three per-term counts for
 * each pivot and removes pivot occurrences from the token total. Document and
 * context totals describe the walked population and are therefore unchanged.
 * </p>
 *
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopCoocSnippets implements SnippetsConsumer
{
    /** Whether totals have been published to the bound population. */
    private boolean completed;

    /** Number of merged snippet contexts processed. */
    private int contextCount;

    /** Number of Lucene documents containing at least one merged snippet. */
    private int documentCount;

    /** Field statistics used to validate count-vector sizes. */
    private final TermStats fieldStats;

    /** Number of context positions read before each snippet. */
    private final int left;

    /** Population completed by {@link #complete()}, or {@code null} for legacy binding. */
    private Population population;

    /** Forward positional rail for the indexed field. */
    private final TermRail rail;

    /** Number of context positions read after each snippet. */
    private final int right;

    /** Writable per-term context counts. */
    private int[] termContexts;

    /** Writable per-term document counts. */
    private int[] termDocs;

    /** Writable per-term occurrence counts. */
    private long[] termFreq;

    /** Terms already counted in the current snippet context. */
    private final BitSet termSeenInContext;

    /** Terms already counted in the current Lucene document. */
    private final BitSet termSeenInDocument;

    /** Total non-pivot occurrences counted across snippet contexts. */
    private long tokenCount;

    /**
     * Constructs a snippet co-occurrence consumer.
     *
     * @param fieldStats field statistics for the indexed field
     * @param rail forward positional rail for the same field
     * @param left number of positions to include before each snippet
     * @param right number of positions to include after each snippet
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
        final int vocabSize = fieldStats.vocabSize();
        this.termSeenInContext = new BitSet(vocabSize);
        this.termSeenInDocument = new BitSet(vocabSize);
    }

    /**
     * Binds a writable population and resets accumulated totals.
     *
     * <p>
     * After the walk and any pivot subtraction, call {@link #complete()} to
     * publish all totals to the bound population.
     * </p>
     *
     * @param population population obtained from
     * {@link com.github.oeuvres.alix.lucene.terms.TopTerms#beginPopulation()}
     * @return this consumer
     * @throws IllegalArgumentException if a vector length differs from the vocabulary size
     * @throws NullPointerException if {@code population} is {@code null}
     */
    public TopCoocSnippets bindTo(final Population population)
    {
        final Population target = Objects.requireNonNull(population, "population");
        bindVectors(target.termFreq(), target.termDocs(), target.termContexts());
        this.population = target;
        reset();
        return this;
    }

    /**
     * Publishes accumulated totals to the population bound through
     * {@link #bindTo(Population)}.
     *
     * @throws IllegalStateException if no population was bound or it was already completed
     */
    public void complete()
    {
        if (completed) {
            throw new IllegalStateException("consumer already completed");
        }
        if (population == null) {
            throw new IllegalStateException(
                "bindTo(TopTerms.Population) must be called before complete()");
        }
        population.complete(tokenCount, documentCount, contextCount);
        completed = true;
    }

    /**
     * Returns the number of merged snippet contexts processed.
     *
     * @return context count
     */
    public int contextCount()
    {
        return contextCount;
    }

    /**
     * Accumulates every merged snippet window from one Lucene document.
     *
     * @param docId global Lucene document id
     * @param snippets merged snippets collected for the document
     * @throws IOException if snippet processing fails
     * @throws IllegalStateException if no count vectors have been bound
     */
    @Override
    public void docSnippets(
        final int docId,
        final DocSnippets snippets
    )
        throws IOException
    {
        requireCollecting();

        final int count = snippets.count();
        if (count == 0) {
            return;
        }

        documentCount++;
        termSeenInDocument.clear();

        for (int snippetOrd = 0; snippetOrd < count; snippetOrd++) {
            termSeenInContext.clear();

            final int start = snippets.snipStartPosition(snippetOrd);
            final int end = snippets.snipEndPosition(snippetOrd);

            rail.scanWindow(docId, start - left, end + right, termId -> {
                termFreq[termId]++;
                tokenCount++;

                if (!termSeenInContext.get(termId)) {
                    termSeenInContext.set(termId);
                    termContexts[termId]++;
                }

                if (!termSeenInDocument.get(termId)) {
                    termSeenInDocument.set(termId);
                    termDocs[termId]++;
                }
            });

            contextCount++;
        }
    }

    /**
     * Returns the number of Lucene documents containing at least one merged snippet.
     *
     * @return document count
     */
    public int documentCount()
    {
        return documentCount;
    }

    /**
     * Clears totals accumulated by this consumer.
     *
     * <p>
     * Bound count vectors are not cleared. Obtain a fresh population or fresh
     * buffers before starting another collection pass.
     * </p>
     */
    public void reset()
    {
        completed = false;
        contextCount = 0;
        documentCount = 0;
        tokenCount = 0L;
        termSeenInContext.clear();
        termSeenInDocument.clear();
    }

    /**
     * Removes pivot terms accumulated during the walk.
     *
     * <p>
     * Every occurrence of each pivot is removed from the token total, and its
     * occurrence, document-frequency, and context-frequency entries are cleared.
     * Call this method once after the walk and before {@link #complete()} or
     * ranking terms.
     * </p>
     *
     * @param pivotIds dense ids of pivot terms; {@code null} and empty arrays are ignored
     * @throws IllegalArgumentException if a pivot id is outside the vocabulary
     * @throws IllegalStateException if no count vectors have been bound
     */
    public void subtractPivots(final int[] pivotIds)
    {
        if (pivotIds == null || pivotIds.length == 0) {
            return;
        }
        requireCollecting();

        long pivotTokens = 0L;
        final int vocabSize = fieldStats.vocabSize();
        for (final int pivotId : pivotIds) {
            if (pivotId <= 0 || pivotId >= vocabSize) {
                throw new IllegalArgumentException(
                    "pivotId=" + pivotId + ", expected 1.." + (vocabSize - 1));
            }
            pivotTokens += termFreq[pivotId];
            termFreq[pivotId] = 0L;
            termDocs[pivotId] = 0;
            termContexts[pivotId] = 0;
        }

        tokenCount -= pivotTokens;
    }

    /**
     * Returns the total non-pivot occurrences counted across snippet contexts.
     *
     * @return token occurrence count
     */
    public long tokenCount()
    {
        return tokenCount;
    }

    /**
     * Binds writable count vectors after validating their lengths.
     *
     * @param termFreq occurrence-count vector
     * @param termDocs document-count vector
     * @param termContexts context-count vector
     * @throws IllegalArgumentException if a vector length differs from the vocabulary size
     */
    private void bindVectors(
        final long[] termFreq,
        final int[] termDocs,
        final int[] termContexts
    ) {
        final int vocabSize = fieldStats.vocabSize();
        if (termFreq.length != vocabSize
                || termDocs.length != vocabSize
                || termContexts.length != vocabSize) {
            throw new IllegalArgumentException(
                "count-vector length mismatch: vocabSize=" + vocabSize
                    + ", termFreq.length=" + termFreq.length
                    + ", termDocs.length=" + termDocs.length
                    + ", termContexts.length=" + termContexts.length);
        }
        this.termFreq = termFreq;
        this.termDocs = termDocs;
        this.termContexts = termContexts;
    }

    /**
     * Verifies that collection is still open.
     *
     * @throws IllegalStateException if totals were already published
     */
    private void requireCollecting()
    {
        requireBound();
        if (completed) {
            throw new IllegalStateException("consumer already completed");
        }
    }

    /**
     * Verifies that writable count vectors have been bound.
     *
     * @throws IllegalStateException if no count vectors have been bound
     */
    private void requireBound()
    {
        if (termFreq == null || termDocs == null || termContexts == null) {
            throw new IllegalStateException("bindTo() must be called before collecting snippets");
        }
    }
}
