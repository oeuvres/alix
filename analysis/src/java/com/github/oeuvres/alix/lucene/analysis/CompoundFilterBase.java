package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource;

/**
 * Bounded, replace-only compounding of consecutive input tokens.
 *
 * <p>Subclasses recognize spans using {@link #at(int)} and emit a compound
 * using {@link #merge(int, boolean)}. Failed lookahead is replayed unchanged;
 * ordinary tokens that cannot start a match do not enter the queue. The
 * subclass owns grammatical eligibility and all linguistic output annotations.
 * No boundary attribute is required.</p>
 *
 * <p>The queue is initialized lazily, after the analysis chain has registered
 * its attributes. Buffered states are internal, read-only views valid until
 * their slots are consumed. The live attributes may change during lookahead;
 * the accepted span or the unmatched head is restored before emission.</p>
 */
public abstract class CompoundFilterBase extends TokenFilter
{
    private final int lookahead;
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    protected final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    protected final PositionLengthAttribute lenAtt = addAttribute(PositionLengthAttribute.class);
    private TokenStateQueue queue;
    private boolean exhausted;

    /**
     * Creates a compounding filter with a grammar-specific lookahead bound.
     * @param input upstream token stream
     * @param lookahead maximum number of buffered tokens, including the head
     */
    protected CompoundFilterBase(final TokenStream input, final int lookahead)
    {
        super(input);
        if (lookahead < 1) throw new IllegalArgumentException("lookahead must be positive");
        this.lookahead = lookahead;
        // Required in buffered states even when the upstream stream omits it.
        addAttribute(PositionIncrementAttribute.class);
    }

    /**
     * Emits a compound or replays the next unmatched input token.
     * @return true if a token is available
     * @throws IOException if the input cannot advance
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        if (queue == null || queue.isEmpty()) {
            if (exhausted || !input.incrementToken()) {
                exhausted = true;
                return false;
            }
            if (!mayStart(this)) {
                unmatched(false);
                return true;
            }
            if (queue == null) queue = new TokenStateQueue(lookahead, this);
            queue.addLast(this);
        }
        if (compound()) return true;
        queue.removeFirst(this);
        unmatched(true);
        return true;
    }

    /**
     * Resets the lookahead buffer for reuse. Overrides must call super.
     * @throws IOException if the input cannot reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        if (queue != null) queue.clear();
        exhausted = false;
    }

    /**
     * Reads a buffered token, extending lookahead only as needed. Non-initial
     * tokens must satisfy {@link #canJoin(AttributeSource)}. A rejected token
     * remains buffered for the next match; no read passes it.
     * @param index zero-based index relative to the current head
     * @return buffered state, or null at a barrier, bound or end of input
     * @throws IOException if the input cannot advance
     */
    protected final AttributeSource at(final int index) throws IOException
    {
        if (index < 0 || index >= lookahead) return null;
        if (index > 0 && queue.size() > 1 && index >= queue.size() - 1
                && !canJoin(queue.peekLast())) return null;
        while (queue.size() <= index) {
            if (exhausted || !input.incrementToken()) {
                exhausted = true;
                return null;
            }
            queue.addLast(this);
            if (!canJoin(queue.peekLast())) return null;
        }
        return queue.get(index);
    }

    /**
     * Tests positional continuity for a non-initial token. Subclasses may
     * impose grammatical barriers or retain a different positional policy.
     * @param token buffered state following the head
     * @return true if the token may extend the current span
     */
    protected boolean canJoin(final AttributeSource token)
    {
        return token.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() == 1
            && token.getAttribute(PositionLengthAttribute.class).getPositionLength() == 1;
    }

    /**
     * Recognizes a span at the buffered head and emits it with merge on success.
     * Buffered heads may be the tail of an earlier failed match and must be
     * checked for eligibility by the subclass. On failure, leave the queue
     * intact so the base can restore and emit its first token.
     * @return true if a compound has been emitted
     * @throws IOException if lookahead fails
     */
    protected abstract boolean compound() throws IOException;

    /**
     * Cheap test for a fresh input token, before any state is copied.
     * @param token live input attributes
     * @return true if the token may start a compound
     */
    protected abstract boolean mayStart(AttributeSource token);

    /**
     * Restores the head and consumes an accepted span. The first token's
     * attributes and position increment survive; offsets cover the full span.
     * The subclass determines the output position length, POS and lemma.
     * @param count number of accepted tokens, including the head
     * @param joinTerms true to reconstruct the input terms using source offsets;
     *                  false to retain the first term for subclass replacement
     */
    protected final void merge(final int count, final boolean joinTerms)
    {
        if (count < 1 || count > queue.size()) throw new IllegalArgumentException("invalid compound length: " + count);
        queue.restoreTo(this, 0);
        if (joinTerms) {
            int end = offsetAtt.endOffset();
            for (int i = 1; i < count; i++) {
                final AttributeSource token = queue.get(i);
                final OffsetAttribute offsets = token.getAttribute(OffsetAttribute.class);
                if (offsets.startOffset() > end) termAtt.append(' ');
                termAtt.append(token.getAttribute(CharTermAttribute.class));
                end = offsets.endOffset();
            }
        }
        offsetAtt.setOffset(offsetAtt.startOffset(),
            queue.get(count - 1).getAttribute(OffsetAttribute.class).endOffset());
        for (int i = 0; i < count; i++) queue.removeFirst();
    }

    /**
     * Optional contextual processing of an unmatched live token, after replay.
     * The default is to leave it unchanged.
     * @param attempted true if compounding was attempted at the buffered head
     */
    protected void unmatched(final boolean attempted) {}
}
