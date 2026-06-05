package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.util.fr.FrenchCliticTokenizer;

/**
 * Unit tests for {@link MweLexicon}.
 *
 * <p>The lexicon is tokenizer-agnostic. These tests therefore do not assume
 * lowercasing, lemmatization, or analyzer-side normalization. Expressions are
 * tokenized with {@link FrenchCliticTokenizer}, then inserted exactly as emitted
 * by that tokenizer.</p>
 */
public class MweLexiconTest {
    private MweLexicon lexicon;

    /**
     * Builds a frozen lexicon for each test.
     */
    @BeforeEach
    void build() {
        lexicon = new MweLexicon(8);

        final WordTokenizer tokenizer = new FrenchCliticTokenizer();

        for (String expression : List.of(
                "machine learning",
                "New York",
                "New York City",
                "state of the art",
                "single",
                "")) {
            final List<String> words = tokenizer.tokenize(expression);
            lexicon.addExpression(words, expression);
        }

        lexicon.freeze();
    }

    /**
     * Verifies that canonical forms are stored in the shared vocabulary.
     */
    @Test
    void canonicalFormsAreInVocab() {
        final CharsDic charsDic = lexicon.charsDic();

        assertTrue(charsDic.ord("machine learning") >= 0);
        assertTrue(charsDic.ord("New York") >= 0);
        assertTrue(charsDic.ord("New York City") >= 0);
        assertTrue(charsDic.ord("state of the art") >= 0);
    }

    /**
     * Verifies that component tokens are stored exactly as supplied by the tokenizer.
     */
    @Test
    void componentTokensAreInVocab() {
        final CharsDic charsDicb = lexicon.charsDic();

        assertTrue(charsDicb.ord("machine") >= 0);
        assertTrue(charsDicb.ord("learning") >= 0);
        assertTrue(charsDicb.ord("New") >= 0);
        assertTrue(charsDicb.ord("York") >= 0);
        assertTrue(charsDicb.ord("City") >= 0);
        assertTrue(charsDicb.ord("state") >= 0);
        assertTrue(charsDicb.ord("of") >= 0);
        assertTrue(charsDicb.ord("the") >= 0);
        assertTrue(charsDicb.ord("art") >= 0);
    }

    /**
     * Verifies that case is significant because {@link MweLexicon} does not normalize.
     */
    @Test
    void differentlyCasedTokensDoNotMatch() {
        final int state = lexicon.step(lexicon.root(), chars("new"), 3);

        assertEquals(-1, state, "lowercase new must not match stored token New");
    }

    /**
     * Verifies matching for a four-token expression.
     */
    @Test
    void fourTokenMatch() {
        final int acceptOrd = walkAndAccept("state", "of", "the", "art");

        assertTrue(acceptOrd >= 0);
        assertEquals("state of the art", lexicon.asString(acceptOrd));
    }

    /**
     * Verifies that a known first token followed by a wrong known token fails.
     */
    @Test
    void knownTokenButNoSequenceMatch() {
        final int state1 = lexicon.step(lexicon.root(), chars("machine"), 7);

        assertTrue(state1 >= 0, "first token should advance state");
        assertEquals(-1, lexicon.accept(state1));

        final int state2 = lexicon.step(state1, chars("state"), 5);

        assertEquals(-1, state2);
    }

    /**
     * Verifies that the longest registered expression length is reported.
     */
    @Test
    void maxLenIsLongestExpression() {
        assertEquals(4, lexicon.maxLen());
    }

    /**
     * Verifies that a branch/leaf state accepts the shorter expression.
     */
    @Test
    void maximalMunchShortMatchAtStep2() {
        final int state1 = lexicon.step(lexicon.root(), chars("New"), 3);
        final int state2 = lexicon.step(state1, chars("York"), 4);

        assertTrue(state2 >= 0);

        final int acceptOrd = lexicon.accept(state2);

        assertTrue(acceptOrd >= 0, "should accept at New York");
        assertEquals("New York", lexicon.asString(acceptOrd));
    }

    /**
     * Verifies that the longer expression sharing a prefix also accepts.
     */
    @Test
    void maximalMunchLongMatchAtStep3() {
        final int state1 = lexicon.step(lexicon.root(), chars("New"), 3);
        final int state2 = lexicon.step(state1, chars("York"), 4);
        final int state3 = lexicon.step(state2, chars("City"), 4);

        assertTrue(state3 >= 0);

        final int acceptOrd = lexicon.accept(state3);

        assertTrue(acceptOrd >= 0, "should accept at New York City");
        assertEquals("New York City", lexicon.asString(acceptOrd));
    }

    /**
     * Verifies that a branch/leaf state still has an outgoing arc.
     */
    @Test
    void maximalMunchShortMatchBranchLeafHasOutgoingArc() {
        final int state1 = lexicon.step(lexicon.root(), chars("New"), 3);
        final int state2 = lexicon.step(state1, chars("York"), 4);
        final int state3 = lexicon.step(state2, chars("City"), 4);

        assertTrue(state2 >= 0, "New York state must exist");
        assertTrue(state3 >= 0, "New York state must still have a City arc");
    }

    /**
     * Verifies that a prefix state is not accepting when no expression ends there.
     */
    @Test
    void prefixOnlyNotAccepting() {
        final int state = lexicon.step(lexicon.root(), chars("New"), 3);

        assertTrue(state >= 0);
        assertEquals(-1, lexicon.accept(state), "prefix state must not accept");
    }

    /**
     * Verifies that repeated component tokens share the same ordinal.
     */
    @Test
    void sharedComponentHasOneOrdinal() {
        final CharsDic charsDic = lexicon.charsDic();
        final int ord1 = charsDic.ord("New");
        final int ord2 = charsDic.ord("New");
        assertEquals(ord1, ord2);
        assertTrue(ord1 >= 0);
    }

    /**
     * Verifies that a single-token expression is skipped entirely.
     */
    @Test
    void singleTokenExpressionSkipped() {
        assertEquals(-1, lexicon.charsDic().ord("single"));
    }

    /**
     * Verifies that a failed walk does not affect later walks from root.
     */
    @Test
    void stateResetBetweenAttempts() {
        lexicon.step(lexicon.root(), chars("philosophy"), 10);

        final int acceptOrd = walkAndAccept("machine", "learning");

        assertTrue(acceptOrd >= 0, "fresh walk from root must succeed after a failed one");
        assertEquals("machine learning", lexicon.asString(acceptOrd));
    }

    /**
     * Verifies matching for a two-token lowercase expression.
     */
    @Test
    void twoTokenMatch() {
        final int acceptOrd = walkAndAccept("machine", "learning");

        assertTrue(acceptOrd >= 0, "expected accepting state");
        assertEquals("machine learning", lexicon.asString(acceptOrd));
    }

    /**
     * Verifies matching for a two-token mixed-case expression.
     */
    @Test
    void twoTokenMatchPreservesCase() {
        final int acceptOrd = walkAndAccept("New", "York");

        assertTrue(acceptOrd >= 0);
        assertEquals("New York", lexicon.asString(acceptOrd));
    }

    /**
     * Verifies that an unknown token fast-fails.
     */
    @Test
    void unknownTokenFastFail() {
        final int state = lexicon.step(lexicon.root(), chars("philosophy"), 10);

        assertEquals(-1, state);
    }

    /**
     * Verifies that unknown tokens are absent from the vocabulary.
     */
    @Test
    void unknownTokenNotInVocab() {
        assertEquals(-1, lexicon.charsDic().ord("philosophy"));
    }

    /**
     * Returns a character array for a token.
     *
     * @param token the token
     * @return the character array
     */
    private static char[] chars(final String token) {
        return token.toCharArray();
    }

    /**
     * Walks the automaton token by token and returns the accept ordinal at the
     * final state.
     *
     * @param tokens the token sequence
     * @return the accept ordinal, or -1 if the walk dies or is not accepting
     */
    private int walkAndAccept(final String... tokens) {
        int state = lexicon.root();

        for (String token : tokens) {
            state = lexicon.step(state, chars(token), token.length());
            if (state < 0) {
                return -1;
            }
        }

        return lexicon.accept(state);
    }
}