package com.github.oeuvres.alix.util.fr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FrenchCliticTokenizer}.
 */
public class FrenchCliticTokenizerTest {
    /**
     * Tests that apostrophe prefixes are split and expanded.
     */
    @Test
    public void apostrophePrefixes() {
        assertTokens("le_bon_Dieu", "le_bon_Dieu");
        assertTokens("J'aime", "je", "aime");
        assertTokens("m'appelle", "me", "appelle");
        assertTokens("N'oublie", "ne", "oublie");
        assertTokens("qu'on", "que", "on");
        assertTokens("puisqu'il", "puisque", "il");
        assertTokens("l'enfant", "l'", "enfant");
    }

    /**
     * Tests that apostrophe variants are normalized before tokenization.
     */
    @Test
    public void apostropheVariants() {
        assertTokens("j’aime", "je", "aime");
        assertTokens("j‘aime", "je", "aime");
        assertTokens("jʼaime", "je", "aime");
    }

    /**
     * Tests that the tokenizer can be cleared and reused.
     */
    @Test
    public void clear() {
        final FrenchCliticTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset("j'aime");
        assertEquals(true, tokenizer.next());
        assertEquals("je", tokenizer.word().toString());

        tokenizer.clear();

        assertEquals(false, tokenizer.next());
        assertThrows(IllegalStateException.class, tokenizer::word);
    }

    /**
     * Tests that digits are kept inside raw tokens.
     */
    @Test
    public void digits() {
        assertTokens("abc123 456def", "abc123", "456def");
    }

    /**
     * Tests that hyphen suffixes are split and expanded.
     */
    @Test
    public void hyphenSuffixes() {
        assertTokens("donne-le-moi", "donne", "le", "moi");
        assertTokens("parle-t-il", "parle", "il");
        assertTokens("vas-y", "vas", "y");
        assertTokens("prends-en", "prends", "en");
        assertTokens("celui-ci", "celui");
        assertTokens("celui-là", "celui");
    }

    /**
     * Tests that hyphen variants are normalized before tokenization.
     */
    @Test
    public void hyphenVariants() {
        assertTokens("donne‐le", "donne", "le");
        assertTokens("donne-le", "donne", "le");
        // assertTokens("donne­-le", "donne", "le");
    }

    /**
     * Tests that listed expressions are kept as one token.
     */
    @Test
    public void keepAsIs() {
        assertTokens("c'est-à-dire", "c'est-à-dire");
        assertTokens("d'abord", "d'abord");
        assertTokens("d'accord", "d'accord");
        assertTokens("n'importe", "n'importe");
        assertTokens("qu'est-ce", "qu'est-ce");
        assertTokens("quelqu'un", "quelqu'un");
    }

    /**
     * Tests that null input produces no token.
     */
    @Test
    public void nullInput() {
        final FrenchCliticTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset(null);

        assertEquals(false, tokenizer.next());
    }

    /**
     * Tests that punctuation separates tokens.
     */
    @Test
    public void punctuation() {
        assertTokens("Bonjour, j'aime; Paris.", "Bonjour", "je", "aime", "Paris");
    }

    /**
     * Tests that reset replaces the previous input.
     */
    @Test
    public void reset() {
        final FrenchCliticTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset("j'aime");
        assertEquals(true, tokenizer.next());
        assertEquals("je", tokenizer.word().toString());

        tokenizer.reset("donne-le");

        assertEquals(true, tokenizer.next());
        assertEquals("donne", tokenizer.word().toString());
        assertEquals(true, tokenizer.next());
        assertEquals("le", tokenizer.word().toString());
        assertEquals(false, tokenizer.next());
    }

    /**
     * Tests that simple words are emitted unchanged.
     */
    @Test
    public void simpleWords() {
        assertTokens("Bonjour monde", "Bonjour", "monde");
    }

    /**
     * Tests that too many hyphen splits keep the raw token unchanged.
     */
    @Test
    public void tooManyHyphens() {
        assertTokens("a-b-c-d-e-f-g-h-i-j", "a-b-c-d-e-f-g-h-i-j");
    }

    /**
     * Tests that incomplete clitic-looking tokens are kept unchanged.
     */
    @Test
    public void trailingSeparators() {
        assertTokens("j' donne-", "j'", "donne-");
    }

    /**
     * Tests that word cannot be called before next has emitted a token.
     */
    @Test
    public void wordBeforeNext() {
        final FrenchCliticTokenizer tokenizer = new FrenchCliticTokenizer();

        tokenizer.reset("j'aime");

        assertThrows(IllegalStateException.class, tokenizer::word);
    }

    /**
     * Asserts the token sequence produced by the tokenizer.
     *
     * @param input the input text
     * @param expected the expected tokens
     */
    private static void assertTokens(final CharSequence input, final String... expected) {
        final FrenchCliticTokenizer tokenizer = new FrenchCliticTokenizer();
        final List<String> actual = new ArrayList<>();

        tokenizer.reset(input);
        while (tokenizer.next()) {
            actual.add(tokenizer.word().toString());
        }

        assertEquals(Arrays.asList(expected), actual);
    }
}