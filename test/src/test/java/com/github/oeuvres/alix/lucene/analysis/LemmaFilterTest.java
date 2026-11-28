package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.LemmaLexicon;

/** Regression tests for contextual numerical and lexical lemma resolution. */
public class LemmaFilterTest
{
    /** Explicit upstream or expected output token. */
    private record Token(String term, String lemma, Upos pos, boolean keyword, String type)
    {
        /** Creates an ordinary token. */
        Token(String term, String lemma, Upos pos) {
            this(term, lemma, pos, false, TypeAttribute.DEFAULT_TYPE);
        }
    }

    /**
     * Verifies preservation of numerical and other canonical annotations.
     * @throws IOException on stream error
     */
    @Test
    public void confirmedAnnotations() throws IOException
    {
        final LemmaLexicon lex = new LemmaLexicon(16);
        lex.put("6ans", "incorrect");
        lex.put("prendre conscience", "incorrect");
        lex.put("paris", "incorrect");
        final List<Token> tokens = List.of(
            new Token("sept", "7", NUMcard),
            new Token("troisième", "3e", ADJord),
            new Token("6ans", "", NOUNage),
            new Token("25kg", "", NOUNqty),
            new Token("%", "", SYMunit),
            new Token("prendre conscience", "", VERB, false, MweFilter.TYPE_MWE),
            new Token("paris", "", PROPNplace),
            new Token("went", "go", VERB),
            new Token("6", "", NUM));
        check(lex, tokens, tokens);
    }

    /**
     * Checks the exact POS, base UPOS and independent lemma lookup precedence.
     * @throws IOException on stream error
     */
    @Test
    public void lexicalLookup() throws IOException
    {
        final LemmaLexicon lex = new LemmaLexicon(16);
        lex.put("saw", "see");
        lex.put("saw", VERB.ordinal(), "see");
        lex.put("saw", NOUN.ordinal(), "saw");
        lex.put("prises", NOUN.ordinal(), "prise");
        lex.put("prises", VERB.ordinal(), "prendre");
        lex.put("prises", VERBpartpast.ordinal(), "pris");
        lex.put("kilogrammes", NOUN.ordinal(), "kilogramme");
        lex.put("children", "child");
        lex.put("mice", "mouse");
        check(lex, List.of(
            new Token("saw", "", VERB),
            new Token("saw", "", NOUN),
            new Token("prises", "", VERBpartpast),
            new Token("kilogrammes", "", NOUNunit),
            new Token("children", "", NOUN),
            new Token("mice", "", TOKEN)), List.of(
            new Token("saw", "see", VERB),
            new Token("saw", "", NOUN),
            new Token("prises", "pris", VERBpartpast),
            new Token("kilogrammes", "kilogramme", NOUNunit),
            new Token("children", "child", NOUN),
            new Token("mice", "mouse", TOKEN)));
    }

    /**
     * Checks cardinal and ordinal candidates against contextual POS, including
     * rejected candidates for which the independent mapping is inappropriate.
     * @throws IOException on stream error
     */
    @Test
    public void numericalCandidates() throws IOException
    {
        final LemmaLexicon lex = new LemmaLexicon(16);
        lex.put("un", DET.ordinal(), "un");
        lex.put("une", DET.ordinal(), "un");
        lex.put("neuf", ADJ.ordinal(), "neuf");
        lex.put("secondes", NOUN.ordinal(), "seconde");
        lex.put("seconde", "second");
        lex.put("second", "2e");
        check(lex, List.of(
            new Token("un", "1", DET),
            new Token("un", "1", NUM),
            new Token("une", "1", DETart),
            new Token("neuf", "9", ADJ),
            new Token("neuf", "9", NUM),
            new Token("neuf", "9", TOKEN),
            new Token("seconde", "2e", NOUN),
            new Token("secondes", "2e", NOUN),
            new Token("seconde", "2e", ADJ),
            new Token("second", "2e", NUM),
            new Token("second", "2e", NOUN)), List.of(
            new Token("un", "", DET),
            new Token("un", "1", NUMcard),
            new Token("une", "un", DETart),
            new Token("neuf", "", ADJ),
            new Token("neuf", "9", NUMcard),
            new Token("neuf", "", TOKEN),
            new Token("seconde", "", NOUN),
            new Token("secondes", "seconde", NOUN),
            new Token("seconde", "2e", ADJord),
            new Token("second", "2e", ADJord),
            new Token("second", "", NOUN)));
    }

    /**
     * Checks keyword protection, capitalized words and sentence boundaries.
     * @throws IOException on stream error
     */
    @Test
    public void protectedForms() throws IOException
    {
        final LemmaLexicon lex = new LemmaLexicon(16);
        lex.put("mais", CCONJ.ordinal(), "mais");
        lex.put("pas", ADV.ordinal(), "pas");
        lex.put("cats", NOUN.ordinal(), "cat");
        final Token keyword = new Token("cats", "", NOUN, true, TypeAttribute.DEFAULT_TYPE);
        final Token dot = new Token(".", "", PUNCTsent);
        final Token xml = new Token("<p>", "", XML);
        final CharArraySet propn = new CharArraySet(List.of("Pas"), false);
        final List<Token> tokens = List.of(xml, new Token("Mais", "", CCONJ),
            new Token("Pas", "", ADV), keyword, dot, xml, new Token("Pas", "", ADV));
        final List<Token> expected = List.of(xml, new Token("mais", "", CCONJ),
            new Token("Pas", "", ADV), keyword, dot, xml, new Token("pas", "", ADV));
        try (TokenStream stream = new LemmaFilter(new Fixture(tokens), lex, propn)) {
            assertEquals(expected, read(stream));
            assertEquals(expected, read(stream));
        }
    }

    /**
     * Compares a complete output stream with the expected token states.
     * @param lex lemma dictionary
     * @param tokens upstream tokens
     * @param expected expected output
     * @throws IOException on stream error
     */
    private static void check(final LemmaLexicon lex, final List<Token> tokens,
        final List<Token> expected) throws IOException
    {
        try (TokenStream stream = new LemmaFilter(new Fixture(tokens), lex)) {
            assertEquals(expected, read(stream));
        }
    }

    /**
     * Reads terms, lemmas, POS, keywords and token types from a stream.
     * @param stream input stream
     * @return token states
     * @throws IOException on stream error
     */
    private static List<Token> read(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final LemmaAttribute lemma = stream.addAttribute(LemmaAttribute.class);
        final PosAttribute pos = stream.addAttribute(PosAttribute.class);
        final KeywordAttribute keyword = stream.addAttribute(KeywordAttribute.class);
        final TypeAttribute type = stream.addAttribute(TypeAttribute.class);
        final List<Token> result = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            result.add(new Token(term.toString(), lemma.value(), Upos.get(pos.getPos()),
                keyword.isKeyword(), type.type()));
        }
        stream.end();
        return result;
    }

    /** Controlled upstream token states, independent of statistical predictions. */
    private static final class Fixture extends TokenStream
    {
        private final List<Token> tokens;
        private int index;
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final LemmaAttribute lemma = addAttribute(LemmaAttribute.class);
        private final PosAttribute pos = addAttribute(PosAttribute.class);
        private final KeywordAttribute keyword = addAttribute(KeywordAttribute.class);
        private final TypeAttribute type = addAttribute(TypeAttribute.class);

        /**
         * Creates a reusable stream from explicit token states.
         * @param tokens input states
         */
        Fixture(final List<Token> tokens) { this.tokens = tokens; }

        /**
         * Emits the next token with its supplied annotations.
         * @return true if a token is available
         */
        @Override
        public boolean incrementToken()
        {
            if (index == tokens.size()) return false;
            clearAttributes();
            final Token token = tokens.get(index++);
            term.append(token.term());
            lemma.append(token.lemma());
            pos.setPos(token.pos().ordinal());
            keyword.setKeyword(token.keyword());
            type.setType(token.type());
            return true;
        }

        /**
         * Resets the fixture for reuse.
         * @throws IOException on stream error
         */
        @Override
        public void reset() throws IOException
        {
            super.reset();
            index = 0;
        }
    }
}
