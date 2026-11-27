package com.github.oeuvres.alix.lucene.analysis.fr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.lucene.analysis.MarkupBoundaryFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.NumberFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/** Tests the age grammar, precision policy and lossless failed lookahead. */
public class AgeFilterTest
{
    private static final NumberFilter.Lexicon NUMBERS = FrenchLexicons.buildNumberLexicon();
    /** A token with its lexical and positional attributes. */
    private record Token(String term, String lemma, int pos, int start, int end, int inc, int len, int boundary) {}

    /**
     * Tests an artificial upstream stream carrying numerical lemmas.
     * This isolates AgeFilter from tokenization and numerical dictionary lookup.
     */
    private static final class Fixture extends TokenStream
    {
        final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        final LemmaAttribute lemma = addAttribute(LemmaAttribute.class);
        final PosAttribute pos = addAttribute(PosAttribute.class);
        final OffsetAttribute off = addAttribute(OffsetAttribute.class);
        final PositionIncrementAttribute inc = addAttribute(PositionIncrementAttribute.class);
        final PositionLengthAttribute len = addAttribute(PositionLengthAttribute.class);
        final BoundaryAttribute boundary = addAttribute(BoundaryAttribute.class);
        final Token[] tokens;
        int index;

        /**
         * Creates a stream of explicit token states.
         * @param tokens source tokens
         */
        Fixture(final Token... tokens) { this.tokens = tokens; }

        /**
         * Emits the next fixture token.
         * @return true if available
         */
        @Override
        public boolean incrementToken()
        {
            if (index == tokens.length) return false;
            clearAttributes();
            final Token t = tokens[index++];
            term.append(t.term);
            lemma.append(t.lemma);
            pos.setPos(t.pos);
            off.setOffset(t.start, t.end);
            inc.setPositionIncrement(t.inc);
            len.setPositionLength(t.len);
            boundary.setBoundary(t.boundary);
            return true;
        }

        /**
         * Resets the fixture for reuse.
         * @throws IOException on stream error
         */
        @Override
        public void reset() throws IOException { super.reset(); index = 0; }
    }

    /**
     * Tests cardinal ages, half units and compounds.
     * @throws IOException on stream error
     */
    @Test
    public void cardinals() throws IOException
    {
        terms(false, "sept ans", "7ans");
        terms(false, "un an", "1ans");
        terms(false, "neuf mois", "9mois");
        terms(false, "deux ans et demi", "2½ans");
        terms(false, "une année et demie", "1½ans");
        terms(false, "deux ans six mois", "2ans6mois");
        terms(false, "deux ans et six mois", "2ans6mois");
        terms(false, "trois mois et deux jours", "3mois2jours");
        terms(false, "une semaine et trois jours", "1semaines3jours");
        terms(false, "1,5 ans", "1.5ans");
    }

    /**
     * Tests exact and rounded ages.
     * @throws IOException on stream error
     */
    @Test
    public void discretization() throws IOException
    {
        terms(true, "0 ; 0 (9)", "1semaines");
        terms(true, "0 ; 3 (13)", "3½mois");
        terms(true, "0 ; 7 (13)", "7mois");
        terms(true, "1,5 ans", "18mois");
        terms(true, "1 ; 4", "15mois");
        terms(true, "2 ; 6", "2½ans");
        terms(true, "4 ; 8", "5ans");
        terms(true, "1 ; 3 à 1 ; 6", "15-18mois");
    }

    /**
     * Tests that rejected prefixes and lookahead survive unchanged.
     * @throws IOException on stream error
     */
    @Test
    public void failedMatches() throws IOException
    {
        terms(false, "un enfant neuf", "un", "enfant", "neuf");
        terms(false, "de trois enfants", "de", "trois", "enfants");
        terms(false, "2 ans et demi puis 3 ans", "2½ans", "puis", "3ans");
        terms(false, "2 ans et enfant", "2ans", "et", "enfant");
        terms(false, "entre 2 ans et enfant", "entre", "2ans", "et", "enfant");
        terms(false, "3 ; 12", "3", ";", "12");
        terms(false, "0 ; 3 (32)", "0ans3mois", "(", "32", ")");
        terms(false, "2. Ans", "2", ".", "Ans");
        terms(false, "2</p><p>ans", "2", "¶", "ans");
        assertEquals(List.of(
            new Token("un", "1", TOKEN.ordinal(), 0, 2, 1, 1, 0),
            new Token("enfant", "", TOKEN.ordinal(), 3, 9, 1, 1, 0),
            new Token("neuf", "9", TOKEN.ordinal(), 10, 14, 1, 1, 0)),
            analyze("un enfant neuf", false));
        assertEquals(List.of(
            new Token("de", "", TOKEN.ordinal(), 0, 2, 1, 1, 0),
            new Token("trois", "3", NUMcard.ordinal(), 3, 8, 1, 1, 0),
            new Token("enfants", "", TOKEN.ordinal(), 9, 16, 1, 1, 0)),
            analyze("de trois enfants", false));
    }

    /**
     * Tests numerical lemmas, offsets, POS, gaps and structural boundaries.
     * @throws IOException on stream error
     */
    @Test
    public void lemmaAndPositions() throws IOException
    {
        final Token neuf = new Token("neuf", "9", TOKEN.ordinal(), 0, 4, 1, 1, 0);
        final Token ans = new Token("ans", "", TOKEN.ordinal(), 5, 8, 1, 1, 0);
        final Token noun = new Token("enfant", "", TOKEN.ordinal(), 9, 15, 1, 1, 0);
        try (AgeFilter f = new AgeFilter(new Fixture(neuf, ans, noun))) {
            assertEquals(List.of(new Token("9ans", "", NOUNage.ordinal(), 0, 8, 1, 1, 0), noun), read(f));
            assertEquals(List.of(new Token("9ans", "", NOUNage.ordinal(), 0, 8, 1, 1, 0), noun), read(f));
        }
        final Token gap = new Token("ans", "", TOKEN.ordinal(), 5, 8, 2, 1, 0);
        try (AgeFilter f = new AgeFilter(new Fixture(neuf, gap))) {
            assertEquals(List.of(neuf, gap), read(f));
        }
        final Token boundary = new Token("ans", "", TOKEN.ordinal(), 5, 8, 1, 1, BoundaryAttribute.SENTENCE);
        try (AgeFilter f = new AgeFilter(new Fixture(neuf, boundary))) {
            assertEquals(List.of(neuf, boundary), read(f));
        }
        final Token un = new Token("un", "1", TOKEN.ordinal(), 0, 2, 1, 1, 0);
        try (AgeFilter f = new AgeFilter(new Fixture(un, noun))) {
            assertEquals(List.of(un, noun), read(f));
        }
        final Token semi = new Token(";", "", PUNCTclause.ordinal(), 3, 4, 1, 1, 0);
        final Token deux = new Token("deux", "2", NUMcard.ordinal(), 5, 9, 1, 1, 0);
        try (AgeFilter f = new AgeFilter(new Fixture(un, semi, deux))) {
            assertEquals(List.of(un, semi, deux), read(f));
        }
        // An unannotated ambiguous surface is not a numerical candidate.
        final Token rawNeuf = new Token("neuf", "", TOKEN.ordinal(), 0, 4, 1, 1, 0);
        try (AgeFilter f = new AgeFilter(new Fixture(rawNeuf, ans))) {
            assertEquals(List.of(rawNeuf, ans), read(f));
        }
        final Token sept = new Token("sept", "7", NUMcard.ordinal(), 0, 4, 1, 1, 0);
        try (AgeFilter f = new AgeFilter(new Fixture(sept, ans))) {
            assertEquals(List.of(new Token("7ans", "", NOUNage.ordinal(), 0, 8, 1, 1, 0)), read(f));
        }
    }

    /**
     * Tests ordinal periods, which are not chronological ages.
     * @throws IOException on stream error
     */
    @Test
    public void ordinals() throws IOException
    {
        terms(false, "troisième mois", "3emois");
        terms(true, "première année", "1erannée");
        terms(false, "seconde semaine", "2esemaine");
        assertEquals(List.of(new Token("3emois", "", NOUNage.ordinal(), 0, 14, 1, 1, 0)),
            analyze("troisième mois", false));
        assertEquals(List.of(new Token("troisième", "3e", ADJord.ordinal(), 0, 9, 1, 1, 0)),
            analyze("troisième", false));
    }

    /**
     * Tests the corpus-specific notation.
     * @throws IOException on stream error
     */
    @Test
    public void piaget() throws IOException
    {
        terms(false, "0 ; 3 (13)", "0ans3mois13jours");
        terms(false, "0 ; 0 (9)", "0ans0mois9jours");
        terms(false, "4 ; 5 à 5 ; 11", "4ans5mois-5ans11mois");
        terms(false, "8 ; 6 à 10 ans", "8ans6mois-10ans");
        terms(false, "0 ; 2 (3) à 0 ; 2 (6)", "0ans2mois3jours-0ans2mois6jours");
        terms(false, "4 ; 5 ans", "4ans5mois");
        terms(false, "un ; deux", "un", ";", "deux");
    }

    /**
     * Tests shared, repeated and mixed-unit intervals.
     * @throws IOException on stream error
     */
    @Test
    public void ranges() throws IOException
    {
        terms(false, "7 à 8 ans", "7-8ans");
        terms(false, "7-8 ans", "7-8ans");
        terms(false, "de trois à quatre ans", "3-4ans");
        terms(false, "entre trois et quatre ans", "3-4ans");
        terms(false, "entre trois ans et quatre ans", "3-4ans");
        terms(false, "six mois à un an", "6mois-1ans");
        terms(false, "entre six mois et un an", "6mois-1ans");
        terms(false, "entre deux ans et six mois", "2ans-6mois");
        terms(false, "de deux ans et demi à trois ans", "2½-3ans");
        terms(false, "trois ans et quatre ans", "3ans", "et", "4ans");
    }

    /**
     * Runs the current tokenizer and NumberFilter with no statistical model.
     * @param text source text
     * @param discretize true for analytical bins
     * @return all emitted tokens
     * @throws IOException on stream error
     */
    private static List<Token> analyze(final String text, final boolean discretize) throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        tokenizer.setReader(new StringReader(text));
        try (TokenStream stream = new AgeFilter(new NumberFilter(new MarkupBoundaryFilter(tokenizer),
                NUMBERS), discretize)) {
            return read(stream);
        }
    }

    /**
     * Captures the complete output of a stream.
     * @param stream input stream
     * @return emitted tokens
     * @throws IOException on stream error
     */
    private static List<Token> read(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final LemmaAttribute lemma = stream.addAttribute(LemmaAttribute.class);
        final PosAttribute pos = stream.addAttribute(PosAttribute.class);
        final OffsetAttribute off = stream.addAttribute(OffsetAttribute.class);
        final PositionIncrementAttribute inc = stream.addAttribute(PositionIncrementAttribute.class);
        final PositionLengthAttribute len = stream.addAttribute(PositionLengthAttribute.class);
        final BoundaryAttribute boundary = stream.addAttribute(BoundaryAttribute.class);
        final List<Token> result = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            result.add(new Token(term.toString(), lemma.value(), pos.getPos(), off.startOffset(), off.endOffset(),
                inc.getPositionIncrement(), len.getPositionLength(), boundary.getBoundary()));
        }
        stream.end();
        return result;
    }

    /**
     * Compares complete output terms, including any failed lookahead.
     * @param discretize true for analytical output
     * @param source source text
     * @param expected expected terms
     * @throws IOException on stream error
     */
    private static void terms(final boolean discretize, final String source, final String... expected) throws IOException
    {
        assertEquals(List.of(expected), analyze(source, discretize).stream().map(Token::term).toList(), source);
    }
}
