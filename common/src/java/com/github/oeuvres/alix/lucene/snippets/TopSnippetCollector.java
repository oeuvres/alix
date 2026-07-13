package com.github.oeuvres.alix.lucene.snippets;

import java.util.Objects;

import com.github.oeuvres.alix.util.TopSlot;

/**
 * Collects the globally best snippet hits from one exhaustive span walk.
 *
 * <p>
 * The result unit is the snippet, not the document. For every merged snippet,
 * this collector computes a score from its token-position window and proposes
 * that score to a fixed-capacity {@link TopSlot}. Match offsets are copied
 * only when the candidate is accepted by the top-k container.
 * </p>
 *
 * <p>
 * The supplied {@link DocSnippets} buffer must use
 * {@link DocSnippets.Usage#OFFSETS}. Although offsets are decoded for all
 * accepted span matches by {@link SpanWalker}, persistent offset arrays are
 * retained only by the current top snippet hits.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class TopSnippetCollector implements SpanWalker.SnippetsConsumer
{
    /** Token context added on both sides for scoring. */
    private final int context;

    /** Number of documents that produced at least one snippet. */
    private int docCount;

    /** Scorer applied to every merged snippet window. */
    private final SnippetScorer scorer;

    /** Total number of merged snippets seen. */
    private int snippetCount;

    /** Fixed-capacity globally ranked snippet hits. */
    private final TopSlot<SnippetHit> top;

    /**
     * Creates a global top-snippet collector.
     *
     * @param size    maximum number of snippet hits to retain
     * @param context token context added on each side of the merged snippet
     *                before scoring
     * @param scorer  snippet scorer
     * @throws IllegalArgumentException if {@code size <= 0} or
     *         {@code context < 0}
     * @throws NullPointerException if {@code scorer} is {@code null}
     */
    public TopSnippetCollector(
        final int size,
        final int context,
        final SnippetScorer scorer
    ) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0: " + size);
        }
        if (context < 0) {
            throw new IllegalArgumentException("context must be >= 0: " + context);
        }
        this.context = context;
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.top = new TopSlot<>(SnippetHit::new, size);
    }

    /**
     * Returns the number of documents that produced at least one merged
     * snippet during collection.
     *
     * @return document count
     */
    public int docCount()
    {
        return docCount;
    }

    /**
     * Scores and collects the snippets of one document.
     *
     * <p>
     * The supplied buffer is reused by {@link SpanWalker} after this method
     * returns. Every accepted top-k candidate therefore copies its identifying
     * positions and match offsets into its own reusable {@link SnippetHit}.
     * </p>
     *
     * @param docId    Lucene internal document id
     * @param snippets finished snippets for {@code docId}; must use
     *                 {@link DocSnippets.Usage#OFFSETS}
     * @throws IllegalArgumentException if {@code snippets} does not use
     *         {@link DocSnippets.Usage#OFFSETS}
     */
    @Override
    public void docSnippets(
        final int docId,
        final DocSnippets snippets
    ) {
        if (snippets.usage() != DocSnippets.Usage.OFFSETS) {
            throw new IllegalArgumentException(
                "TopSnippetCollector requires DocSnippets.Usage.OFFSETS, got "
                    + snippets.usage()
            );
        }

        final int count = snippets.count();
        if (count <= 0) {
            return;
        }

        docCount++;
        snippetCount += count;

        for (int snipOrd = 0; snipOrd < count; snipOrd++) {
            final int startPosition = snippets.snipStartPosition(snipOrd);
            final int endPosition = snippets.snipEndPosition(snipOrd);
            final int scoreStartPosition = Math.max(0, startPosition - context);
            final int scoreEndPosition = endPosition + context;
            final double score = scorer.score(
                docId,
                scoreStartPosition,
                scoreEndPosition
            );

            final SnippetHit hit = top.insert(score);
            if (hit == null) {
                continue;
            }
            hit.copyFrom(docId, snipOrd, snippets);
        }
    }

    /**
     * Returns the selected top snippet hits.
     *
     * <p>
     * Iteration over the returned container yields
     * {@code TopSlot.Entry<SnippetHit>} values in descending score order.
     * The score belongs to the entry; the {@link SnippetHit} contains only
     * snippet identity and rendering data.
     * </p>
     *
     * @return live top-k container
     */
    public TopSlot<SnippetHit> hits()
    {
        return top;
    }

    /**
     * Returns the total number of merged snippets seen during collection.
     *
     * @return snippet count
     */
    public int snippetCount()
    {
        return snippetCount;
    }
}
