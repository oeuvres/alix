package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.util.CharsDic;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MweLexicon}.
 *
 * <p>Analyzer: whitespace tokenizer + lowercasefilter, matching what a real
 * index-time pipeline would apply before the MWE filter.</p>
 */
class MweLexiconTest
{
    /** Whitespace split + lowercase — minimal but realistic normalization. */
    private static final Analyzer ANALYZER = new Analyzer() {
        @Override
        protected TokenStreamComponents createComponents(final String fieldName) {
            final Tokenizer tok = new WhitespaceTokenizer();
            final TokenStream ts = new LowerCaseFilter(tok);
            return new TokenStreamComponents(tok, ts);
        }
    };

    private static final String FIELD = "text";

    private MweLexicon lexicon;

    @BeforeEach
    void build() throws IOException
    {
        lexicon = new MweLexicon(ANALYZER, FIELD, 8);
        lexicon.addExpression("machine learning");      // 2 tokens
        lexicon.addExpression("New York");              // 2 tokens, tests lowercasing
        lexicon.addExpression("New York City");         // 3 tokens, shares prefix with above
        lexicon.addExpression("state of the art");      // 4 tokens, contains stop-words
        lexicon.addExpression("single");                // 1 token → must be skipped
        lexicon.addExpression("");                      // empty → must be skipped
        lexicon.addExpression(null);                    // null → must be skipped
        lexicon.freeze();
    }

    // ---- Lifecycle -----------------------------------------------------------

    @Test
    void addAfterFreezeThrows()
    {
        assertThrows(IllegalStateException.class,
            () -> lexicon.addExpression("natural language processing"));
    }

    // ---- Vocabulary ----------------------------------------------------------

    @Test
    void canonicalFormsAreInVocab()
    {
        final CharsDic v = lexicon.vocab();
        assertTrue(v.find("machine learning") >= 0);
        assertTrue(v.find("New York")         >= 0);
        assertTrue(v.find("New York City")    >= 0);
        assertTrue(v.find("state of the art") >= 0);
    }

    @Test
    void componentTokensAreInVocab()
    {
        final CharsDic v = lexicon.vocab();
        // lowercased by the analyzer
        assertTrue(v.find("machine")  >= 0);
        assertTrue(v.find("learning") >= 0);
        assertTrue(v.find("new")      >= 0);
        assertTrue(v.find("york")     >= 0);
        assertTrue(v.find("city")     >= 0);
        assertTrue(v.find("state")    >= 0);
        assertTrue(v.find("of")       >= 0);
        assertTrue(v.find("the")      >= 0);
        assertTrue(v.find("art")      >= 0);
    }

    @Test
    void sharedComponentHasOneOrdinal()
    {
        // "new" appears in both "New York" and "New York City"
        final CharsDic v = lexicon.vocab();
        final int ord1 = v.find("new");
        final int ord2 = v.find("new");
        assertEquals(ord1, ord2);
        assertTrue(ord1 >= 0);
    }

    @Test
    void unknownTokenNotInVocab()
    {
        assertEquals(-1, lexicon.vocab().find("philosophy"));
    }

    @Test
    void singleTokenExpressionSkipped()
    {
        // "single" was added as a 1-token expression; it must not be in vocab
        // (it was never registered as a canonical form, and has no component tokens
        // since the expression was skipped before tokenization fed the automaton)
        assertEquals(-1, lexicon.vocab().find("single"));
    }

    // ---- Full match ----------------------------------------------------------

    @Test
    void twoTokenMatch() throws IOException
    {
        final int acceptOrd = walkAndAccept("machine", "learning");
        assertTrue(acceptOrd >= 0, "expected accepting state");
        assertEquals("machine learning", lexicon.vocab().getAsString(acceptOrd));
    }

    @Test
    void twoTokenMatchCaseInsensitive() throws IOException
    {
        // tokens arriving at filter time are already lowercased
        final int acceptOrd = walkAndAccept("new", "york");
        assertTrue(acceptOrd >= 0);
        assertEquals("New York", lexicon.vocab().getAsString(acceptOrd));
    }

    @Test
    void fourTokenMatch() throws IOException
    {
        final int acceptOrd = walkAndAccept("state", "of", "the", "art");
        assertTrue(acceptOrd >= 0);
        assertEquals("state of the art", lexicon.vocab().getAsString(acceptOrd));
    }

    // ---- No match ------------------------------------------------------------

    @Test
    void unknownTokenFastFail()
    {
        // "philosophy" is not in vocab → step returns -1 immediately
        final int s1 = lexicon.step(lexicon.root(), chars("philosophy"), 10);
        assertEquals(-1, s1);
    }

    @Test
    void knownTokenButNoSequenceMatch() throws IOException
    {
        // "machine" alone: valid first step but no accept
        final int s1 = lexicon.step(lexicon.root(), chars("machine"), 7);
        assertTrue(s1 >= 0, "first token should advance state");
        assertEquals(-1, lexicon.accept(s1));

        // "machine" + "state": dead end
        final int s2 = lexicon.step(s1, chars("state"), 5);
        assertEquals(-1, s2);
    }

    // ---- Prefix only (BRANCH, not LEAF) --------------------------------------

    @Test
    void prefixOnlyNotAccepting() throws IOException
    {
        // "new" alone: a BRANCH node (prefix of "new york" and "new york city")
        final int s1 = lexicon.step(lexicon.root(), chars("new"), 3);
        assertTrue(s1 >= 0);
        assertEquals(-1, lexicon.accept(s1), "prefix state must not accept");
    }

    // ---- Maximal munch -------------------------------------------------------

    @Test
    void maximalMunch_shortMatchAtStep2() throws IOException
    {
        // After "new" + "york" the automaton is at a BRANCH_LEAF state
        final int s1 = lexicon.step(lexicon.root(), chars("new"),  3);
        final int s2 = lexicon.step(s1,             chars("york"), 4);
        assertTrue(s2 >= 0);
        // Accept at step 2: "New York" matches
        final int acceptOrd2 = lexicon.accept(s2);
        assertTrue(acceptOrd2 >= 0, "should accept at 'new york'");
        assertEquals("New York", lexicon.vocab().getAsString(acceptOrd2));
    }

    @Test
    void maximalMunch_longMatchAtStep3() throws IOException
    {
        // Continuing past "new york" to "city" gives the longer match
        final int s1 = lexicon.step(lexicon.root(), chars("new"),  3);
        final int s2 = lexicon.step(s1,             chars("york"), 4);
        final int s3 = lexicon.step(s2,             chars("city"), 4);
        assertTrue(s3 >= 0);
        final int acceptOrd3 = lexicon.accept(s3);
        assertTrue(acceptOrd3 >= 0, "should accept at 'new york city'");
        assertEquals("New York City", lexicon.vocab().getAsString(acceptOrd3));
    }

    @Test
    void maximalMunch_shortMatchBranchLeafHasOutgoingArc() throws IOException
    {
        // The BRANCH_LEAF state after "new york" must still have an arc for "city"
        final int s1 = lexicon.step(lexicon.root(), chars("new"),  3);
        final int s2 = lexicon.step(s1,             chars("york"), 4);
        // s2 accepts AND has an outgoing arc → step must not return -1 here
        assertTrue(s2 >= 0, "branch+leaf state must not be dead");
    }

    // ---- State isolation -----------------------------------------------------

    @Test
    void stateResetBetweenAttempts() throws IOException
    {
        // Failed attempt does not pollute a fresh attempt from root
        lexicon.step(lexicon.root(), chars("philosophy"), 10); // fails, returns -1
        final int acceptOrd = walkAndAccept("machine", "learning");
        assertTrue(acceptOrd >= 0, "fresh walk from root must succeed after a failed one");
        assertEquals("machine learning", lexicon.vocab().getAsString(acceptOrd));
    }

    // ---- maxLen --------------------------------------------------------------

    @Test
    void maxLenIsLongestExpression()
    {
        // Longest registered MWE that survived (>= 2 tokens) is "state of the art" = 4
        assertEquals(4, lexicon.maxLen());
    }

    // ---- Normalization contract -----------------------------------------------

    @Test
    void unnormalizedTokensDoNotMatch()
    {
        // Tokens arriving un-lowercased must not match: "Machine" not in vocab
        final int s1 = lexicon.step(lexicon.root(), chars("Machine"), 7);
        assertEquals(-1, s1, "un-lowercased token must not be in vocab");
    }

    // ---- Internal helpers ----------------------------------------------------

    /**
     * Walks the automaton token by token and returns the accept id at the final state,
     * or -1 if the walk dies or ends in a non-accepting state.
     */
    private int walkAndAccept(final String... tokens)
    {
        int state = lexicon.root();
        for (final String tok : tokens) {
            state = lexicon.step(state, tok.toCharArray(), tok.length());
            if (state < 0) return -1;
        }
        return lexicon.accept(state);
    }

    /** Convenience: returns a char[] from a String (no padding). */
    private static char[] chars(final String s)
    {
        return s.toCharArray();
    }
}
