package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.Objects;

import com.github.oeuvres.alix.util.Detagger;
import com.github.oeuvres.alix.util.Markup;

/**
 * A snippet retained by a global top-k ranking.
 *
 * <p>
 * Instances are mutable because {@link com.github.oeuvres.alix.util.TopSlot}
 * pre-allocates and reuses its value slots. A retained hit owns a copy of the
 * match offsets required for later rendering; it does not retain a reference
 * to the reusable {@link DocSnippets} buffer supplied by {@link SpanWalker}.
 * </p>
 *
 * <p>
 * The score is deliberately not stored here. It is owned by the enclosing
 * {@code TopSlot.Entry<SnippetHit>} and must be read from that entry.
 * </p>
 */
public final class SnippetHit
{
    /** Lucene internal document id. */
    private int docId;

    /** End token position of the merged snippet, exclusive. */
    private int endPosition;

    /** Number of retained query-match offset pairs. */
    private int matchCount;

    /** Packed query-match offsets: high int is start, low int is end. */
    private long[] matchOffsets;

    /** Document-local snippet ordinal. */
    private int snipOrd;

    /** Start token position of the merged snippet, inclusive. */
    private int startPosition;

    /**
     * Returns the Lucene internal document id.
     *
     * @return document id
     */
    public int docId()
    {
        return docId;
    }

    /**
     * Returns the character end offset of the last query match.
     *
     * @return final match end offset
     * @throws IllegalStateException if this hit contains no matches
     */
    public int endOffset()
    {
        requireMatches();
        return unpackEnd(matchOffsets[matchCount - 1]);
    }

    /**
     * Returns the end token position of the merged snippet.
     *
     * @return end position, exclusive
     */
    public int endPosition()
    {
        return endPosition;
    }

    /**
     * Returns the number of retained query matches.
     *
     * @return match count
     */
    public int matchCount()
    {
        return matchCount;
    }

    /**
     * Returns the character end offset of one retained query match.
     *
     * @param matchOrd match ordinal inside this snippet
     * @return character end offset
     * @throws IndexOutOfBoundsException if {@code matchOrd} is outside
     *         {@code [0, matchCount())}
     */
    public int matchEndOffset(final int matchOrd)
    {
        checkMatchOrd(matchOrd);
        return unpackEnd(matchOffsets[matchOrd]);
    }

    /**
     * Returns the character start offset of one retained query match.
     *
     * @param matchOrd match ordinal inside this snippet
     * @return character start offset
     * @throws IndexOutOfBoundsException if {@code matchOrd} is outside
     *         {@code [0, matchCount())}
     */
    public int matchStartOffset(final int matchOrd)
    {
        checkMatchOrd(matchOrd);
        return unpackStart(matchOffsets[matchOrd]);
    }

    /**
     * Returns the document-local snippet ordinal.
     *
     * @return snippet ordinal, zero-based
     */
    public int snipOrd()
    {
        return snipOrd;
    }

    /**
     * Returns the character start offset of the first query match.
     *
     * @return first match start offset
     * @throws IllegalStateException if this hit contains no matches
     */
    public int startOffset()
    {
        requireMatches();
        return unpackStart(matchOffsets[0]);
    }

    /**
     * Returns the start token position of the merged snippet.
     *
     * @return start position, inclusive
     */
    public int startPosition()
    {
        return startPosition;
    }
    
    /**
     * Emits one snippet paragraph: left context, highlighted query matches
     * with interleaved text, and right context.
     *
     * <p>
     * The supplied hit must contain at least one retained match. Match offsets
     * are interpreted against {@code content}; therefore the content must come
     * from the same indexed document and field as the hit.
     * </p>
     *
     * @param writer   destination
     * @param detagger HTML detagger used for non-highlighted context
     * @param content  stored source content of the hit document
     * @param context  number of visible words requested on each side
     * @throws IOException if writing fails
     * @throws IllegalArgumentException if {@code context < 0}
     * @throws IllegalStateException if the hit contains no match
     * @throws NullPointerException if an argument is {@code null}
     */
    public void write(
        final Appendable writer,
        final Detagger detagger,
        final String content,
        final int context
    ) throws IOException {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(detagger, "detagger");
        Objects.requireNonNull(content, "content");

        if (context < 0) {
            throw new IllegalArgumentException("context must be >= 0: " + context);
        }

        final int matchCount = matchCount();
        if (matchCount <= 0) {
            throw new IllegalStateException("snippet hit contains no match offsets");
        }

        final int firstMatchStartOffset = matchStartOffset(0);
        final int lastMatchEndOffset = matchEndOffset(matchCount - 1);

        writer.append("<p>");

        final int leftOffset = Markup.leftBoundary(
            content,
            firstMatchStartOffset,
            context,
            -1
        );
        detagger.detag(
            writer,
            content,
            leftOffset,
            firstMatchStartOffset
        );

        for (int matchOrd = 0; matchOrd < matchCount; matchOrd++) {
            final int startOffset = matchStartOffset(matchOrd);
            final int endOffset = matchEndOffset(matchOrd);

            if (matchOrd > 0) {
                detagger.detag(
                    writer,
                    content,
                    matchEndOffset(matchOrd - 1),
                    startOffset
                );
            }

            writer.append("<mark class=\"hit pivot\">");
            writer.append(content, startOffset, endOffset);
            writer.append("</mark>");
        }

        final int rightOffset = Markup.rightBoundary(
            content,
            lastMatchEndOffset,
            context,
            -1
        );
        detagger.detag(
            writer,
            content,
            lastMatchEndOffset,
            rightOffset
        );

        writer.append("</p>\n");
    }

    /**
     * Copies one snippet and its query-match offsets from a reusable document
     * buffer into this retained hit.
     *
     * @param docId    Lucene internal document id
     * @param snipOrd  document-local snippet ordinal
     * @param snippets finished snippets for {@code docId}, in
     *                 {@link DocSnippets.Usage#OFFSETS} mode
     * @throws IllegalStateException if the snippet contains no collected
     *         query match
     */
    void copyFrom(
        final int docId,
        final int snipOrd,
        final DocSnippets snippets
    ) {
        final int firstMatchOrd = snippets.snipStartMatch(snipOrd);
        final int lastMatchOrd = snippets.snipEndMatch(snipOrd);
        if (firstMatchOrd < 0 || lastMatchOrd < firstMatchOrd) {
            throw new IllegalStateException(
                "snippet " + snipOrd + " in doc " + docId + " has no collected match"
            );
        }

        this.docId = docId;
        this.snipOrd = snipOrd;
        this.startPosition = snippets.snipStartPosition(snipOrd);
        this.endPosition = snippets.snipEndPosition(snipOrd);
        this.matchCount = lastMatchOrd - firstMatchOrd + 1;

        ensureMatchCapacity(matchCount);
        for (int src = firstMatchOrd, dst = 0; src <= lastMatchOrd; src++, dst++) {
            matchOffsets[dst] = pack(
                snippets.matchStartOffset(src),
                snippets.matchEndOffset(src)
            );
        }
    }

    /**
     * Checks a match ordinal.
     *
     * @param matchOrd match ordinal
     */
    private void checkMatchOrd(final int matchOrd)
    {
        if (matchOrd < 0 || matchOrd >= matchCount) {
            throw new IndexOutOfBoundsException(
                "match index " + matchOrd + " outside [0, " + matchCount + ")"
            );
        }
    }

    /**
     * Ensures capacity for retained match offsets without shrinking an
     * existing reusable array.
     *
     * @param required required number of offset pairs
     */
    private void ensureMatchCapacity(final int required)
    {
        if (matchOffsets != null && matchOffsets.length >= required) {
            return;
        }
        final int current = matchOffsets == null ? 0 : matchOffsets.length;
        final int grown = current == 0 ? 4 : current << 1;
        matchOffsets = new long[Math.max(required, grown)];
    }

    /**
     * Packs a character-offset pair into one long.
     *
     * @param startOffset start offset, inclusive
     * @param endOffset   end offset, exclusive
     * @return packed offset pair
     */
    private static long pack(final int startOffset, final int endOffset)
    {
        return ((long) startOffset << 32) | (endOffset & 0xffffffffL);
    }

    /**
     * Verifies that at least one match is retained.
     */
    private void requireMatches()
    {
        if (matchCount <= 0) {
            throw new IllegalStateException("snippet hit contains no match offsets");
        }
    }

    /**
     * Unpacks the end offset from a packed pair.
     *
     * @param packed packed offset pair
     * @return end offset
     */
    private static int unpackEnd(final long packed)
    {
        return (int) packed;
    }

    /**
     * Unpacks the start offset from a packed pair.
     *
     * @param packed packed offset pair
     * @return start offset
     */
    private static int unpackStart(final long packed)
    {
        return (int) (packed >>> 32);
    }
}
