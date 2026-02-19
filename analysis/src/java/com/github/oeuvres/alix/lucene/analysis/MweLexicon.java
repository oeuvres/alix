package com.github.oeuvres.alix.lucene.analysis;

import com.github.oeuvres.alix.util.CharsDic;
import com.github.oeuvres.alix.util.IntAutomaton;

/**
 * Immutable, compiled lexicon of contiguous Multi-Word Expressions (MWEs) for {@link MweFilter}.
 *
 * <h2>Concept</h2>
 * <ul>
 *   <li>Matching is performed over integer token identifiers (typically lemma ids) using an {@link IntAutomaton}.</li>
 *   <li>Accepting automaton states yield an {@code entryId} (0-based), which indexes per-entry metadata:
 *     output form (chars), POS tag id, optional lemma id.</li>
 *   <li>Output strings are stored in a {@link CharsDic} and copied into a caller-provided scratch buffer
 *     to keep runtime allocation-free.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>This object is immutable and safe to share across analyzers/threads once constructed.</p>
 *
 * <h2>Contract used by {@link MweFilter}</h2>
 * <ul>
 *   <li>{@link #root()}, {@link #step(int, int)}, {@link #acceptEntry(int)} for traversal</li>
 *   <li>{@link #maxPatternTokens()} for bounded lookahead</li>
 *   <li>{@link #copyOutput(int, char[])} and {@link #maxOutputLen()} for term emission</li>
 *   <li>{@link #pos(int)} and {@link #lemmaId(int)} for compound token attributes</li>
 * </ul>
 */
public final class MweLexicon
{
    private final IntAutomaton automaton;

    /** Pool of output terms (canonical surface or canonical lemma form, depending on loader policy). */
    private final CharsDic outputDic;

    /** entryId -> output ordinal in {@link #outputDic}. */
    private final int[] outputOrd;

    /** entryId -> POS tag id (tagset is caller-defined). */
    private final short[] pos;

    /** entryId -> lemma id for the compound token, or -1 if not defined. */
    private final int[] lemmaId;

    /** Max number of tokens in any pattern (lookahead bound). */
    private final int maxPatternTokens;

    /** Max output length in UTF-16 code units among all entries (for scratch sizing). */
    private final int maxOutputLen;

    /**
     * Build a lexicon from already-compiled components.
     *
     * <p>All arrays are defensively checked for length consistency; they are not copied.
     * Treat passed arrays as frozen after construction.</p>
     *
     * @param automaton packed automaton; its accept ids must be entryIds in [0..entryCount) or -1
     * @param outputDic output term pool
     * @param outputOrd entryId -> output ordinal
     * @param pos entryId -> POS tag id
     * @param lemmaId entryId -> lemma id or -1
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if array lengths disagree
     */
    public MweLexicon(
            final IntAutomaton automaton,
            final CharsDic outputDic,
            final int[] outputOrd,
            final short[] pos,
            final int[] lemmaId
    ) {
        if (automaton == null) throw new NullPointerException("automaton");
        if (outputDic == null) throw new NullPointerException("outputDic");
        if (outputOrd == null) throw new NullPointerException("outputOrd");
        if (pos == null) throw new NullPointerException("pos");
        if (lemmaId == null) throw new NullPointerException("lemmaId");

        final int n = outputOrd.length;
        if (pos.length != n) throw new IllegalArgumentException("pos.length != outputOrd.length");
        if (lemmaId.length != n) throw new IllegalArgumentException("lemmaId.length != outputOrd.length");

        this.automaton = automaton;
        this.outputDic = outputDic;
        this.outputOrd = outputOrd;
        this.pos = pos;
        this.lemmaId = lemmaId;

        this.maxPatternTokens = Math.max(1, automaton.maxLen());

        // Prefer O(1) if you implement it in CharsDic; otherwise compute once here.
        int mol = 0;
        // If your CharsDic exposes maxTermLength(), use it:
        // mol = outputDic.maxTermLength();
        // Otherwise, compute from entry ords:
        for (int i = 0; i < n; i++) {
            final int ord = outputOrd[i];
            final int len = outputDic.termLength(ord);
            if (len > mol) mol = len;
        }
        this.maxOutputLen = mol;

        // Optional: validate accept ids are within bounds (cheap enough for debug builds).
        // validateAcceptIds(n);
    }

    /**
     * Root automaton state (always 0).
     */
    public int root()
    {
        return automaton.root();
    }

    /**
     * Transition function: follow an arc labeled {@code tokenId} from {@code state}.
     *
     * @return next state id, or -1 if no transition exists
     */
    public int step(final int state, final int tokenId)
    {
        return automaton.step(state, tokenId);
    }

    /**
     * Returns an {@code entryId} if {@code state} is accepting, or -1 otherwise.
     *
     * <p>The returned value indexes {@link #pos(int)}, {@link #lemmaId(int)}, and {@link #copyOutput(int, char[])}.</p>
     */
    public int acceptEntry(final int state)
    {
        return automaton.accept(state);
    }

    /**
     * Maximum number of tokens in any MWE pattern.
     *
     * <p>This is the correct lookahead bound for longest-match algorithms in a linear TokenStream.</p>
     */
    public int maxPatternTokens()
    {
        return maxPatternTokens;
    }

    /**
     * Backward-compatible alias for {@link #maxPatternTokens()}.
     *
     * <p>Avoid exposing "len" without unit; prefer {@link #maxPatternTokens()} in new code.</p>
     */
    public int maxLen()
    {
        return maxPatternTokens;
    }

    /**
     * Number of MWE entries in this lexicon.
     */
    public int size()
    {
        return outputOrd.length;
    }

    /**
     * POS tag id for the compound token produced by this entry.
     *
     * @param entryId entry id returned by {@link #acceptEntry(int)}
     */
    public short pos(final int entryId)
    {
        checkEntry(entryId);
        return pos[entryId];
    }

    /**
     * Optional lemma id for the compound token produced by this entry.
     *
     * @param entryId entry id returned by {@link #acceptEntry(int)}
     * @return lemma id, or -1 if none is defined
     */
    public int lemmaId(final int entryId)
    {
        checkEntry(entryId);
        return lemmaId[entryId];
    }

    /**
     * Copies the output term for {@code entryId} into {@code dst[0..len)} and returns {@code len}.
     *
     * <p>This is the hot-path method used by {@link MweFilter} to emit the canonical MWE term text
     * without allocating.</p>
     *
     * <p>Caller responsibility: ensure {@code dst.length >= maxOutputLen()}.</p>
     *
     * @param entryId entry id returned by {@link #acceptEntry(int)}
     * @param dst destination buffer (start at index 0)
     * @return length in UTF-16 code units
     * @throws IllegalArgumentException if {@code entryId} is invalid or {@code dst} too small
     * @throws NullPointerException if {@code dst} is null
     */
    public int copyOutput(final int entryId, final char[] dst)
    {
        checkEntry(entryId);
        if (dst == null) throw new NullPointerException("dst");

        final int ord = outputOrd[entryId];

        // Preferred if you add the safe copy-out API to CharsDic:
        // return outputDic.get(ord, dst);

        // Fallback using current CharsDic API (slab + offsets) while keeping it encapsulated here:
        final int len = outputDic.termLength(ord);
        if (dst.length < len) {
            throw new IllegalArgumentException("dst too small: dst.length=" + dst.length + " need=" + len);
        }
        final int off = outputDic.termOffset(ord);
        System.arraycopy(outputDic.slab(), off, dst, 0, len);
        return len;
    }

    /**
     * Maximum output term length among all entries, in UTF-16 code units.
     *
     * <p>Use this value to allocate a reusable scratch buffer for {@link #copyOutput(int, char[])}.</p>
     */
    public int maxOutputLen()
    {
        return maxOutputLen;
    }

    /**
     * Debug helper: returns the output term as a String (allocates).
     *
     * @param entryId entry id returned by {@link #acceptEntry(int)}
     */
    public String outputAsString(final int entryId)
    {
        checkEntry(entryId);
        final int ord = outputOrd[entryId];
        final int off = outputDic.termOffset(ord);
        final int len = outputDic.termLength(ord);
        return new String(outputDic.slab(), off, len);
    }

    /**
     * Exposes the internal automaton (advanced use).
     */
    public IntAutomaton automaton()
    {
        return automaton;
    }

    /**
     * Exposes the output dictionary (advanced use; immutable by convention).
     */
    public CharsDic outputDic()
    {
        return outputDic;
    }

    private void checkEntry(final int entryId)
    {
        if (entryId < 0 || entryId >= outputOrd.length) {
            throw new IllegalArgumentException("bad entryId " + entryId);
        }
    }

    @SuppressWarnings("unused")
    private void validateAcceptIds(final int entryCount)
    {
        // IntAutomaton stores accept per state; we can only validate by scanning all states if you expose state count.
        // If you later add IntAutomaton.stateCount(), validate accept ids here.
    }
}
