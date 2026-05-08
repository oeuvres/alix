package com.github.oeuvres.alix.util.fr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.util.WordTokenizer;

/**
 * Tests for {@link FrenchCliticTokenizer}.
 */
public class FrenchCliticTokenizerTest {
    /**
     * Verifies that apostrophe elisions are split and normalized.
     */
    @Test
    void apostropheElisions() {
        assertTokens("j'aime", "je", "aime");
        assertTokens("j’aime", "je", "aime");
        assertTokens("qu'il vient", "que", "il", "vient");
        assertTokens("s'il m'écoute", "se", "il", "me", "écoute");
    }

    /**
     * Verifies that {@link WordTokenizer#clear()} releases scan state.
     */
    @Test
    void clearInvalidatesCurrentWord() {
        final WordTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset("j'aime");
        assertTrue(tokenizer.next());
        assertEquals("je", tokenizer.word().toString());

        tokenizer.clear();

        assertThrows(IllegalStateException.class, tokenizer::word);
        assertFalse(tokenizer.next());
    }

    /**
     * Verifies that the default materializing API returns stable strings.
     */
    @Test
    void defaultTokenizeReturnsStableStrings() {
        final WordTokenizer tokenizer = new FrenchCliticTokenizer();

        final List<String> tokens = tokenizer.tokenize("j'aime donne-le-moi");

        assertEquals(List.of("je", "aime", "donne", "le", "moi"), tokens);
        assertEquals("je", tokens.get(0));
        assertEquals("moi", tokens.get(4));
    }

    /**
     * Verifies that epenthetic {@code -t-} forms are handled by dropping
     * {@code -t} and emitting the following suffix.
     */
    @Test
    void epentheticT() {
        assertTokens("habite-t-il ici", "habite", "il", "ici");
        assertTokens("parle-t-elle", "parle", "elle");
        assertTokens("a-t-on raison", "a", "on", "raison");
    }

    /**
     * Verifies that lexicalized forms configured as keep-as-is are not split.
     */
    @Test
    void keepAsIs() {
        assertTokens("d'accord", "d'accord");
        assertTokens("quelqu'un", "quelqu'un");
        assertTokens("qu'est-ce", "qu'est-ce");
        assertTokens("c'est-à-dire", "c'est-à-dire");
    }

    /**
     * Verifies that punctuation and repeated separators are ignored.
     */
    @Test
    void punctuationAndSeparators() {
        assertTokens("  j’aime, donne-le-moi ! ", "je", "aime", "donne", "le", "moi");
        assertTokens("j'aime;\nqu'il\tvient", "je", "aime", "que", "il", "vient");
    }

    /**
     * Verifies that proper names with uppercase elided prefixes are not split.
     */
    @Test
    void properNamesAreNotSplit() {
        assertTokens("D'Alembert", "D'Alembert");
        assertTokens("L'Oréal", "L'Oréal");
        assertTokens("D’Alembert et L’Oréal", "D'Alembert", "et", "L'Oréal");
    }

    /**
     * Verifies that {@link WordTokenizer#reset(CharSequence)} can retarget the
     * same tokenizer instance.
     */
    @Test
    void resetRetargetsTokenizer() {
        final WordTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset("j'aime");
        assertCollected(tokenizer, "je", "aime");

        tokenizer.reset("donne-le-moi");
        assertCollected(tokenizer, "donne", "le", "moi");
    }

    /**
     * Verifies that hyphen suffix clitics are split in reading order.
     */
    @Test
    void suffixClitics() {
        assertTokens("donne-le-moi", "donne", "le", "moi");
        assertTokens("allons-y", "allons", "y");
        assertTokens("dit-elle", "dit", "elle");
        assertTokens("rends-les-lui", "rends", "les", "lui");
    }

    /**
     * Verifies that suffixes configured as recognized but dropped are dropped.
     */
    @Test
    void suffixesDropped() {
        assertTokens("ceux-ci", "ceux");
        assertTokens("ceux-là", "ceux");
        assertTokens("habite-t-il", "habite", "il");
    }

    /**
     * Verifies that Unicode apostrophe and hyphen variants are normalized.
     */
    @Test
    void unicodeVariants() {
        assertTokens("j’aime", "je", "aime");
        assertTokens("allons-y", "allons", "y");
        assertTokens("dit‐elle", "dit", "elle");
    }

    /**
     * Verifies that {@link WordTokenizer#word()} is invalid before iteration
     * and after iteration end.
     */
    @Test
    void wordRequiresCurrentToken() {
        final WordTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset("j'aime");

        assertThrows(IllegalStateException.class, tokenizer::word);

        assertTrue(tokenizer.next());
        assertEquals("je", tokenizer.word().toString());
        assertTrue(tokenizer.next());
        assertEquals("aime", tokenizer.word().toString());
        assertFalse(tokenizer.next());

        assertThrows(IllegalStateException.class, tokenizer::word);
    }

    /**
     * Asserts that a tokenizer emits the expected words.
     *
     * @param tokenizer the tokenizer
     * @param expected the expected words
     */
    private static void assertCollected(final WordTokenizer tokenizer, final String... expected) {
        final ArrayList<String> actual = new ArrayList<>();

        while (tokenizer.next()) {
            actual.add(tokenizer.word().toString());
        }

        assertEquals(List.of(expected), actual);
    }

    /**
     * Asserts that a fresh tokenizer emits the expected words for an input.
     *
     * @param text the input text
     * @param expected the expected words
     */
    private static void assertTokens(final String text, final String... expected) {
        final WordTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset(text);
        assertCollected(tokenizer, expected);
    }
}