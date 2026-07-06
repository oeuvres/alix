package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;

/**
 * {@link SnippetsConsumer} that fills a {@link CoocProfile} with per-distance cooccurrence counts in
 * a single walk at the widest distance. It holds no accumulation of its own — the counts live in the
 * {@link CoocProfile} — only the scanning scratch needed to bin one document.
 * <p>
 * {@link TermRail#scanPositions} feeds the sink a term id in ascending position order and never the
 * position, so distance is carried by the mask, not the callback. For ascending radii the per-document
 * windows {@code M[i] = ⋃ snippets [snipStart - left[i], snipEnd + right[i])} nest, so the shells
 * {@code S[0] = M[0]} and {@code S[i] = M[i] \ M[i-1]} partition every context position by its
 * distance-to-nearest-pivot band; scanning each shell bins occurrences by distance with no change to
 * {@link TermRail}. A position covered by two pivots' windows is attributed to the nearer one.
 * </p>
 * <p>
 * Each shell's occurrences are written to the matching band column of the {@link CoocProfile}; the
 * band totals are added per shell, and each {@code (term, document)} pair as well as each document is
 * credited to its <em>minimal</em> band so that {@link CoocProfile#cumulate()} yields correct
 * cumulative document counts. The window keeps the matched span, so a pivot's own tokens count at
 * distance 0 and a single tick reproduces the keep-model of {@link TopCoocSnippets}. This class is not
 * thread-safe, and the profile it fills is single-use.
 * </p>
 *
 * @see CoocProfile
 * @see TopCoocSnippets
 */
public final class CoocProfileSnippets implements SnippetsConsumer
{
    /** Document column of the band currently being scanned. */
    private int[] curDocsCol;

    /** Occurrence column of the band currently being scanned. */
    private long[] curFreqCol;

    /** Index of the lowest band that fired in the current document, or {@code -1} if none did. */
    private int docFirstShell;

    /** Left context width per tick, in positions; non-decreasing. */
    private final int[] left;

    /** Per-document accumulated window positions, one bitset per tick. */
    private final BitSet[] mask;

    /** Profile being filled. */
    private final CoocProfile profile;

    /** Forward positional rail for the pivot field. */
    private final TermRail rail;

    /** Right context width per tick, in positions; non-decreasing. */
    private final int[] right;

    /** Reusable single-shell bitset, {@code mask[i] \ mask[i-1]}. */
    private final BitSet shell;

    /** Occurrences counted in the band currently being scanned. */
    private long shellTokenCount;

    /** Per-document set of term ids already credited to a document count, across all bands. */
    private final BitSet termSeen;

    /** Band currently being scanned; read by {@link #count(int)}. */
    private int tickScanning;

    /** Sink handed to {@link TermRail#scanPositions}; bound once to avoid per-shell allocation. */
    private final IntConsumer sink = this::count;

    /**
     * Constructs a consumer that fills {@code profile}. The radii are parallel {@code left} and
     * {@code right} arrays so asymmetric windows are expressible; both must be non-decreasing so the
     * windows nest, and their length must match the profile's tick count.
     *
     * @param profile profile to fill; must have as many ticks as the radius arrays
     * @param stats   field statistics for the pivot field, for buffer geometry
     * @param rail    forward positional rail for the same field
     * @param left    left context width per tick, in positions; non-decreasing, each {@code >= 0}
     * @param right   right context width per tick, in positions; non-decreasing, each {@code >= 0}
     * @throws IllegalArgumentException if the arrays are of unequal length, disagree with the
     * profile's tick count, contain a negative value, or are not non-decreasing
     * @throws NullPointerException if any argument is {@code null}
     */
    public CoocProfileSnippets(
        final CoocProfile profile,
        final TermStats stats,
        final TermRail rail,
        final int[] left,
        final int[] right
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(stats, "stats");
        this.rail = Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        final int n = left.length;
        if (n != right.length || n != profile.ticks().length) {
            throw new IllegalArgumentException(
                "left, right and profile ticks must have equal length; got left.length=" + n
                    + ", right.length=" + right.length + ", ticks=" + profile.ticks().length);
        }
        for (int i = 0; i < n; i++) {
            if (left[i] < 0 || right[i] < 0) {
                throw new IllegalArgumentException(
                    "left and right must be >= 0; got left[" + i + "]=" + left[i]
                        + ", right[" + i + "]=" + right[i]);
            }
            if (i > 0 && (left[i] < left[i - 1] || right[i] < right[i - 1])) {
                throw new IllegalArgumentException(
                    "left and right must be non-decreasing so windows nest; broken at tick " + i);
            }
        }
        this.left = left.clone();
        this.right = right.clone();
        final int width = stats.maxWidth() + right[n - 1];
        this.mask = new BitSet[n];
        for (int i = 0; i < n; i++) {
            this.mask[i] = new BitSet(width);
        }
        this.shell = new BitSet(width);
        this.termSeen = new BitSet(stats.vocabSize());
        this.docFirstShell = -1;
    }

    @Override
    public void docSnippets(
        final int docId,
        final Snippets snippets
    )
        throws IOException {
        final int n = left.length;
        for (int i = 0; i < n; i++) {
            mask[i].clear();
        }
        termSeen.clear();
        docFirstShell = -1;

        final int snipCount = snippets.count();
        for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
            final int snipStartPosition = snippets.snipStartPosition(snipOrd);
            final int snipEndPosition = snippets.snipEndPosition(snipOrd);
            for (int i = 0; i < n; i++) {
                final int winStartPosition = Math.max(0, snipStartPosition - left[i]);
                final int winEndPosition = snipEndPosition + right[i];
                if (winEndPosition > winStartPosition) {
                    mask[i].set(winStartPosition, winEndPosition);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            shell.clear();
            shell.or(mask[i]);
            if (i > 0) {
                shell.andNot(mask[i - 1]);
            }
            tickScanning = i;
            curFreqCol = profile.freqColumn(i);
            curDocsCol = profile.docsColumn(i);
            shellTokenCount = 0L;
            rail.scanPositions(docId, shell, sink);
            profile.addTokens(i, shellTokenCount);
        }

        if (docFirstShell >= 0) {
            profile.addDoc(docFirstShell);
        }
    }

    /**
     * Records one resolved term id found in the band currently being scanned: increments the band's
     * occurrence column and token counter, credits the band's document column on the term's first
     * appearance anywhere in the document, and notes the lowest band that fired.
     *
     * @param termId resolved non-gap term id
     */
    private void count(final int termId)
    {
        curFreqCol[termId]++;
        shellTokenCount++;
        if (!termSeen.get(termId)) {
            termSeen.set(termId);
            curDocsCol[termId]++;
        }
        if (docFirstShell < 0) {
            docFirstShell = tickScanning;
        }
    }
}