package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;

import opennlp.tools.postag.POSDictionary;
import opennlp.tools.postag.POSModel;
import opennlp.tools.util.Sequence;

/** Regression tests for authoritative POS annotations and constrained decoding. */
public class PosTaggingFilterTest
{
    private static final POSModel MODEL = LexiconHelper.loadPosModel(
        PosTaggingFilterTest.class,
        "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin");

    /** A token with its incoming or expected lexical annotations. */
    private record Token(String term, Upos pos, String lemma)
    {
        /**
         * Creates a token without a lemma override.
         * @param term token text
         * @param pos grammatical annotation
         */
        Token(final String term, final Upos pos)
        {
            this(term, pos, "");
        }
    }

    /** Repeatable upstream stream with explicit annotations, independent of French filters. */
    private static final class Fixture extends TokenStream
    {
        private final List<Token> tokens;
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final LemmaAttribute lemma = addAttribute(LemmaAttribute.class);
        private final PosAttribute pos = addAttribute(PosAttribute.class);
        private final OffsetAttribute off = addAttribute(OffsetAttribute.class);
        private final PositionIncrementAttribute inc = addAttribute(PositionIncrementAttribute.class);
        private int index;
        private int offset;

        /**
         * Creates an upstream stream with known token states.
         * @param tokens tokens to emit
         */
        Fixture(final Token... tokens)
        {
            this.tokens = List.of(tokens);
        }

        /**
         * Emits the next token, preserving the supplied annotations.
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
            off.setOffset(offset, offset + term.length());
            inc.setPositionIncrement(1);
            offset += term.length() + 1;
            return true;
        }

        /**
         * Resets the fixture for another pass.
         * @throws IOException if resetting the stream fails
         */
        @Override
        public void reset() throws IOException
        {
            super.reset();
            index = 0;
            offset = 0;
        }
    }

    /**
     * An impossible dictionary outcome must not cause annotations to be lost
     * or a partial statistical result to be applied.
     * @throws IOException if the token stream fails
     */
    @Test
    public void failedDecode() throws IOException
    {
        final POSDictionary dictionary = new POSDictionary(true);
        dictionary.put("blocked", "NOT_A_MODEL_TAG");
        final PosTagger tagger = new PosTagger(MODEL, dictionary);
        final Token age = new Token("6ans", NOUNage);
        final Token blocked = new Token("blocked", TOKEN);
        assertNull(tagger.tag(new String[] {"6ans", "blocked"}, new String[] {"NOUN", null}));
        try (TokenStream stream = new PosTaggingFilter(new Fixture(age, blocked), tagger)) {
            assertEquals(List.of(age, blocked), read(stream));
        }
    }

    /** Verifies that a per-call constraint overrides the dictionary without leaking into later calls. */
    @Test
    public void forcedTags()
    {
        final POSDictionary dictionary = new POSDictionary(true);
        dictionary.put("3ans6mois-6ans6mois", "NUM");
        dictionary.put("dit", "VERB");
        final PosTagger tagger = new PosTagger(MODEL, dictionary);
        final String[] sentence = {"3ans6mois-6ans6mois", "dit"};
        final Sequence forced = tagger.tag(sentence, new String[] {"NOUN", null});
        assertNotNull(forced);
        assertEquals(List.of("NOUN", "VERB"), forced.getOutcomes());
        final Sequence normal = tagger.tag(sentence);
        assertNotNull(normal);
        assertEquals(List.of("NUM", "VERB"), normal.getOutcomes());
        assertThrows(IllegalArgumentException.class,
            () -> tagger.tag(sentence, new String[] {"NOUN"}));
    }

    /**
     * Checks preservation of established tags, alignment across skipped tokens,
     * and repeated decoding with the same stream. A small explicit dictionary
     * makes the expected statistical assignments independent of model accuracy.
     * @throws IOException if the token stream fails
     */
    @Test
    public void preserveAnnotations() throws IOException
    {
        final POSDictionary dictionary = new POSDictionary(true);
        dictionary.put("6ans", "NUM");
        dictionary.put("de temps en temps", "NOUN");
        dictionary.put("dit", "VERB");
        dictionary.put("neuf", "ADJ");
        dictionary.put("enfant", "NOUN");
        final PosTagger tagger = new PosTagger(MODEL, dictionary);
        final Token xml = new Token("<p>", XML);
        final Token age = new Token("6ans", NOUNage);
        final Token empty = new Token("", TOKEN);
        final Token mwe = new Token("de temps en temps", ADVasp);
        final Token number = new Token("sept", NUMcard, "7");
        final Token ordinal = new Token("troisième", ADJord, "3e");
        final Token punctuation = new Token("¶", PUNCTpara);
        final Token symbol = new Token("%", SYMunit);
        final Token end = new Token(".", PUNCTsent);
        final Fixture input = new Fixture(xml, age, empty, new Token("dit", TOKEN),
            mwe, number, ordinal, symbol, punctuation, new Token("enfant", TOKEN),
            new Token("neuf", TOKEN, "9"), end);
        final List<Token> expected = List.of(xml, age, empty, new Token("dit", VERB),
            mwe, number, ordinal, symbol, punctuation, new Token("enfant", NOUN),
            new Token("neuf", ADJ, "9"), end);
        try (TokenStream stream = new PosTaggingFilter(input, tagger)) {
            assertEquals(expected, read(stream));
            // Lemma candidates must be unchanged even when their POS is not NUM.
            assertEquals(expected, read(stream));
        }
    }

    /**
     * Reads all lexical annotations and checks that offsets and positions remain intact.
     * @param stream stream to read
     * @return complete emitted token sequence
     * @throws IOException if the token stream fails
     */
    private static List<Token> read(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final LemmaAttribute lemma = stream.addAttribute(LemmaAttribute.class);
        final PosAttribute pos = stream.addAttribute(PosAttribute.class);
        final OffsetAttribute off = stream.addAttribute(OffsetAttribute.class);
        final PositionIncrementAttribute inc = stream.addAttribute(PositionIncrementAttribute.class);
        final ProbAttribute prob = stream.addAttribute(ProbAttribute.class);
        final List<Token> result = new ArrayList<>();
        int offset = 0;
        stream.reset();
        while (stream.incrementToken()) {
            assertEquals(offset, off.startOffset());
            assertEquals(offset + term.length(), off.endOffset());
            assertEquals(1, inc.getPositionIncrement());
            offset += term.length() + 1;
            final Upos tag = Upos.get(pos.getPos());
            assertNotNull(tag);
            if (tag == TOKEN) assertEquals(0.0, prob.getProb());
            else assertTrue(prob.getProb() > 0.0 && prob.getProb() <= 1.0);
            result.add(new Token(term.toString(), tag, lemma.value()));
        }
        stream.end();
        return result;
    }
}
