package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.lucene.analysis.fr.FrenchLexicons;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;

/** Regression tests for the French resource and the generic number filter. */
public class NumberFilterTest
{
    private static final NumberFilter.Lexicon NUMBERS = FrenchLexicons.buildNumberLexicon();
    private record Token(String term, String lemma, int pos, int start, int end, int increment, int length)
    {
        /** Returns the confirmed numerical normalization, otherwise the surface term.
         * @return canonical numerical value or original term
         */
        String confirmedForm()
        {
            return (pos == NUMcard.ordinal() || pos == ADJord.ordinal()) && !lemma.isEmpty() ? lemma : term;
        }
    }

    /**
     * Runs the tokenizer and numeric filter without a POS model.
     * @param text source text
     * @return all emitted tokens and attributes
     * @throws IOException on stream error
     */
    private static List<Token> analyze(final String text) throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        tokenizer.setReader(new StringReader(text));
        try (TokenStream stream = new NumberFilter(new MarkupBoundaryFilter(tokenizer), NUMBERS)) {
            return read(stream);
        }
    }

    /**
     * Reads a stream, including its position and offset attributes.
     * @param stream stream with a reader already assigned
     * @return all emitted tokens
     * @throws IOException on stream error
     */
    private static List<Token> read(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final PosAttribute pos = stream.addAttribute(PosAttribute.class);
        final LemmaAttribute lemma = stream.addAttribute(LemmaAttribute.class);
        final OffsetAttribute off = stream.addAttribute(OffsetAttribute.class);
        final PositionIncrementAttribute inc = stream.addAttribute(PositionIncrementAttribute.class);
        final PositionLengthAttribute len = stream.addAttribute(PositionLengthAttribute.class);
        final List<Token> result = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            result.add(new Token(term.toString(), lemma.value(), pos.getPos(), off.startOffset(), off.endOffset(),
                inc.getPositionIncrement(), len.getPositionLength()));
        }
        stream.end();
        return result;
    }

    /**
     * Compares complete output terms, including punctuation and unmatched words.
     * @param text source text
     * @param expected expected terms in order
     * @throws IOException on stream error
     */
    private static void terms(final String text, final String... expected) throws IOException
    {
        assertEquals(List.of(expected), analyze(text).stream().map(Token::confirmedForm).toList(), text);
    }

    /** Tests cardinal forms, decimal conventions and Swiss French.
     * @throws IOException on stream error
     */
    @Test
    void cardinals() throws IOException
    {
        terms("six enfants", "6", "enfants");
        terms("Vingt-deux", "22");
        terms("vingt et un", "21");
        terms("vingt-et-une", "21");
        terms("quatre-vingts", "80");
        terms("quatre-vingt-dix", "90");
        terms("soixante et onze", "71");
        terms("septante-cinq et huitante-deux et nonante-neuf", "75", "et", "82", "et", "99");
        terms("deux cent trente", "230");
        terms("deux cents", "200");
        terms("mille neuf cent cinquante", "1950");
        terms("deux millions trois mille quatre", "2003004");
        terms("deux millions mille deux", "2001002");
        terms("un million", "1000000");
        terms("2 millions", "2000000");
        terms("moins trois", "-3");
        terms("trois virgule cinq", "3.5");
        terms("un virgule zéro cinq", "1.05");
        terms("trois virgule 05", "3.05");
        terms("trois virgule cinquante", "3.5");
        terms("deux mille trois cent quatre-vingt-dix-sept", "2397");
        terms("3,50 7.5 −12,5", "3.5", "7.5", "−", "12.5");
    }

    /** Checks that independent numbers, ambiguous words, and punctuation survive.
     * @throws IOException on stream error
     */
    @Test
    void conservativeMatching() throws IOException
    {
        terms("un enfant une chose un objet neuf la seconde", "un", "enfant", "une", "chose", "un", "objet", "neuf", "la", "seconde");
        terms("neuf enfants", "neuf", "enfants");
        terms("deux et trois", "2", "et", "3");
        terms("deux à trois", "2", "à", "3");
        terms("vingt et fleurs", "20", "et", "fleurs");
        terms("deux. Cent", "2", ".", "100");
        terms("7 125", "7", "125");
        terms("1\u00a0234,56 1\u202f234.56 1\u2009234", "1234.56", "1234.56", "1234");
        terms("0 ; 3 (13)", "0", ";", "3", "(", "13", ")");
        terms("1,2,3", "1.2", ",", "3");
        terms("7-8", "7", "-", "8");
        terms("7‐8", "7", "-", "8");
        terms("7‑8", "7", "-", "8");
        terms("7–8", "7", "–", "8");
        terms("−8", "-8");
        terms("3e10 3E-10", "3e10", "3e-10");
        terms("3 e 12 euros", "3", "e", "12", "euros");
        terms("millions de gens", "millions", "de", "gens");
        terms("I V X CIVIL", "I", "V", "X", "CIVIL");
        terms("stade I puis III", "stade", "1", "puis", "3");
    }

    /** Tests exact vulgar fractions and preserved numeric symbols.
     * @throws IOException on stream error
     */
    @Test
    void fractionsAndSymbols() throws IOException
    {
        terms("½ 2½ ¼ ¾ ⅓ 2⅓", "0.5", "2.5", "0.25", "0.75", "1/3", "7/3");
        terms("−½", "-0.5");
        terms("25% 20°C 3×4 x=2 2/3", "25", "%", "20", "°", "C", "3", "×", "4", "x", "=", "2", "2", "/", "3");
        final List<Token> tokens = analyze("25% 3×4 7-8 €");
        assertEquals(List.of(NUMcard.ordinal(), SYMunit.ordinal(), NUMcard.ordinal(),
            SYMmath.ordinal(), NUMcard.ordinal(), NUMcard.ordinal(), PUNCTclause.ordinal(),
            NUMcard.ordinal(), SYMunit.ordinal()), tokens.stream().map(Token::pos).toList());
        assertEquals(List.of("25", "", "3", "", "4", "7", "", "8", ""),
            tokens.stream().map(Token::lemma).toList());
    }

    /** Tests that numerical annotation preserves surfaces and leaves ambiguity to the tagger.
     * @throws IOException on stream error
     */
    @Test
    void lemmaCandidates() throws IOException
    {
        final List<Token> tokens = analyze("un objet neuf une seconde");
        assertEquals(List.of("un", "objet", "neuf", "une", "seconde"),
            tokens.stream().map(Token::term).toList());
        assertEquals(List.of("1", "", "9", "1", "2e"),
            tokens.stream().map(Token::lemma).toList());
        assertEquals(List.of(TOKEN.ordinal(), TOKEN.ordinal(), TOKEN.ordinal(),
            TOKEN.ordinal(), TOKEN.ordinal()), tokens.stream().map(Token::pos).toList());
        final List<Token> confirmed = analyze("sept 3,50 vingtième vingt et un");
        assertEquals(List.of("sept", "3,50", "vingtième", "vingt et un"),
            confirmed.stream().map(Token::term).toList());
        assertEquals(List.of("7", "3.5", "20e", "21"),
            confirmed.stream().map(Token::lemma).toList());
        assertEquals(List.of(NUMcard.ordinal(), NUMcard.ordinal(), ADJord.ordinal(), NUMcard.ordinal()),
            confirmed.stream().map(Token::pos).toList());
    }

    /** Tests full span offsets, collapsed positions, and lookahead replay.
     * @throws IOException on stream error
     */
    @Test
    void offsetsAndPositions() throws IOException
    {
        final List<Token> tokens = analyze("deux cent trente enfants");
        assertEquals(new Token("deux cent trente", "230", NUMcard.ordinal(), 0, 16, 1, 1), tokens.get(0));
        assertEquals("enfants", tokens.get(1).term());
        assertEquals(17, tokens.get(1).start());
        assertEquals(1, tokens.get(1).increment());
        terms("deux <hi>cent</hi> trente enfants", "230", "enfants");
        terms("deux</p><p>cent", "2", "¶", "100");
    }

    /**
     * Tests numerical recognition directly on tokenizer events, without a markup filter.
     * @throws IOException on stream error
     */
    @Test
    void rawStructuralBarriers() throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        try (TokenStream stream = new NumberFilter(tokenizer, NUMBERS)) {
            tokenizer.setReader(new StringReader("deux\n\ncent"));
            assertEquals(List.of("2", "↵↵", "100"),
                read(stream).stream().map(Token::confirmedForm).toList());
            tokenizer.setReader(new StringReader("stade\n\nI"));
            assertEquals(List.of("stade", "↵↵", "I"),
                read(stream).stream().map(Token::confirmedForm).toList());
            tokenizer.setReader(new StringReader("deux</p><p>cent"));
            assertEquals(List.of("2", "</p>", "<p>", "100"),
                read(stream).stream().map(Token::confirmedForm).toList());
        }
    }

    /** Checks that canonical ordinals remain distinguishable from cardinals.
     * @throws IOException on stream error
     */
    @Test
    void ordinals() throws IOException
    {
        terms("première troisième vingt-troisième deux cent trentième", "1er", "3e", "23e", "230e");
        terms("16 16ème 16ᵉ 16 ème 1re XIXe Ⅲ", "16", "16e", "16e", "16e", "1er", "19e", "3");
        terms("deux millième", "2000e");
        terms("2nd 2nde", "2e", "2e");
        final List<Token> tokens = analyze("16 et 16ème");
        assertEquals(NUMcard.ordinal(), tokens.get(0).pos());
        assertEquals(ADJord.ordinal(), tokens.get(2).pos());
    }

    /**
     * Tests that ordinary capitalized words are not Roman ordinals, while
     * explicitly marked or contextually licensed numerals remain accepted.
     * @throws IOException on stream error
     */
    @Test
    void romanOrdinals() throws IOException
    {
        final String text = "Le Les Ce Ces De Des Ver LE LES CE CES DE DES VER";
        final List<Token> tokens = analyze(text);
        assertEquals(List.of(text.split(" ")), tokens.stream().map(Token::term).toList());
        assertEquals(List.of(text.split(" ")), tokens.stream().map(Token::confirmedForm).toList());
        assertEquals(List.of(""), tokens.stream().map(Token::lemma).distinct().toList());
        assertEquals(List.of(TOKEN.ordinal()), tokens.stream().map(Token::pos).distinct().toList());
        terms("Le texte. Ces enfants. Des objets.", "Le", "texte", ".", "Ces", "enfants", ".", "Des", "objets", ".");
        terms("Ve Ie Ier", "Ve", "Ie", "Ier");
        terms("stade I puis III", "stade", "1", "puis", "3");
        terms("stade Ve", "stade", "5e");
        terms("chapitre Ier", "chapitre", "1er");
        terms("chapitre XIXe", "chapitre", "19e");
        terms("stade. Le", "stade", ".", "Le");
    }

    /** Tests reuse of the same stream and its buffered state.
     * @throws IOException on stream error
     */
    @Test
    void reuse() throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        try (TokenStream stream = new NumberFilter(tokenizer, NUMBERS)) {
            tokenizer.setReader(new StringReader("vingt et arbre"));
            assertEquals(List.of("20", "et", "arbre"), read(stream).stream().map(Token::confirmedForm).toList());
            // Lucene requires close before setting a new Reader on a tokenizer.
            stream.close();
            tokenizer.setReader(new StringReader("un"));
            assertEquals(List.of(new Token("un", "1", TOKEN.ordinal(), 0, 2, 1, 1)), read(stream));
        }
    }
    /**
     * Tests that upstream numerical annotations survive uppercase cleanup.
     * @throws IOException on stream error
     */
    @Test
    void uppercasePreservation() throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        tokenizer.setReader(new StringReader("II III IV XIXe AB"));
        try (TokenStream stream = new UppercaseFilter(
                new NumberFilter(tokenizer, NUMBERS), CharArraySet.EMPTY_SET, 4)) {
            final List<Token> tokens = read(stream);
            assertEquals(List.of("II", "III", "IV", "XIXe", ""),
                tokens.stream().map(Token::term).toList());
            assertEquals(List.of("2", "3", "4", "19e", ""),
                tokens.stream().map(Token::lemma).toList());
            assertEquals(List.of(NUMcard.ordinal(), NUMcard.ordinal(), NUMcard.ordinal(),
                ADJord.ordinal(), TOKEN.ordinal()), tokens.stream().map(Token::pos).toList());
        }
    }

}
