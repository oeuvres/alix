package com.github.oeuvres.alix.lucene.analysis.fr;

import static com.github.oeuvres.alix.common.Upos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.NumberFilter;
import com.github.oeuvres.alix.lucene.analysis.NumberFilter.Lexicon;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/** Tests the UPOS and lemma contract between tokenizer, number and age filters. */
public class AgeFilterTest
{
    /** Minimal lexical fixture, independent of production dictionary loading. */
    private static final NumberFilter.Lexicon NUMBERS = lexicon();

    /** Lexical attributes of an emitted token. */
    private record Token(String term, String lemma, Upos pos) {}

    /** Checks contextual confirmation and nominal age classification. */
    @Test
    public void ages() throws IOException
    {
        assertEquals(List.of(new Token("1ans", "", NOUNage)), analyze("un an", 2));
        assertEquals(List.of(new Token("9mois", "", NOUNage)), analyze("neuf mois", 2));
        assertEquals(List.of(new Token("3emois", "", NOUNage)), analyze("troisième mois", 2));
        assertEquals(List.of(new Token("2eannée", "", NOUNage)), analyze("seconde année", 2));
        assertEquals(List.of(new Token("0ans3mois13jours", "", NOUNage)), analyze("0 ; 3 (13)", 2));
        assertEquals(List.of(new Token("un", "1", TOKEN), new Token("enfant", "", TOKEN)),
            analyze("un enfant", 2));
        assertEquals(List.of(new Token("troisième", "3e", ADJord)), analyze("troisième", 2));
    }

    /** Checks that only confirmed numerals acquire NUMcard or ADJord. */
    @Test
    public void numbers() throws IOException
    {
        assertEquals(List.of(new Token("3,50", "3.5", NUMcard)), analyze("3,50", 1));
        assertEquals(List.of(new Token("sept", "7", NUMcard)), analyze("sept", 1));
        assertEquals(List.of(new Token("vingt et un", "21", NUMcard)), analyze("vingt et un", 1));
        assertEquals(List.of(new Token("3e", "3e", ADJord)), analyze("3e", 1));
        assertEquals(List.of(new Token("un", "1", TOKEN), new Token("neuf", "9", TOKEN),
            new Token("seconde", "2e", TOKEN)), analyze("un neuf seconde", 1));
        assertEquals(List.of(new Token("3", "3", NUMcard), new Token("×", "", SYMmath),
            new Token("4", "4", NUMcard)), analyze("3×4", 1));
    }

    /** Checks the full source span of a compounded age. */
    @Test
    public void offsets() throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        tokenizer.setReader(new StringReader("un an puis"));
        try (TokenStream stream = new AgeFilter(new NumberFilter(tokenizer, NUMBERS))) {
            final OffsetAttribute offsets = stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            assertEquals(true, stream.incrementToken());
            assertEquals(0, offsets.startOffset());
            assertEquals(5, offsets.endOffset());
            assertEquals(true, stream.incrementToken());
            assertEquals(6, offsets.startOffset());
            stream.end();
        }
    }

    /** Checks raw cardinal, ordinal, symbolic and punctuation categories. */
    @Test
    public void tokenizer() throws IOException
    {
        assertEquals(List.of(new Token("25", "", NUM), new Token("%", "", SYMunit),
            new Token("3e", "", ADJord), new Token("×", "", SYMmath),
            new Token("4", "", NUM), new Token("€", "", SYMunit),
            new Token(";", "", PUNCTclause)), analyze("25% 3e × 4€ ;", 0));
        assertEquals(List.of(new Token("a", "", TOKEN), new Token("<", "", SYMmath),
            new Token("b", "", TOKEN)), analyze("a&lt;b", 0));
    }

    /**
     * Reads lexical annotations after the chosen analysis stage.
     * @param text source text
     * @param stage 0 tokenizer, 1 NumberFilter, 2 AgeFilter
     * @return emitted tokens
     * @throws IOException on stream error
     */
    private static List<Token> analyze(final String text, final int stage) throws IOException
    {
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        tokenizer.setReader(new StringReader(text));
        final TokenStream numbers = stage == 0 ? tokenizer : new NumberFilter(tokenizer, NUMBERS);
        try (TokenStream stream = stage == 2 ? new AgeFilter(numbers) : numbers) {
            final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            final LemmaAttribute lemma = stream.addAttribute(LemmaAttribute.class);
            final PosAttribute pos = stream.addAttribute(PosAttribute.class);
            final List<Token> result = new ArrayList<>();
            stream.reset();
            while (stream.incrementToken()) {
                result.add(new Token(term.toString(), lemma.value(), Upos.get(pos.getPos())));
            }
            stream.end();
            return result;
        }
    }

    /**
     * Builds a small numerical dictionary with ambiguous candidates.
     * @return compiled numeric lexicon
     */
    private static NumberFilter.Lexicon lexicon()
    {
        final CharArrayMap<char[]> map = new CharArrayMap<>(8, true);
        map.put("un", "?1".toCharArray());
        map.put("neuf", "?9".toCharArray());
        map.put("seconde", "?2e".toCharArray());
        map.put("sept", "7".toCharArray());
        map.put("vingt", "20".toCharArray());
        map.put("vingt et un", "21".toCharArray());
        map.put("troisième", "3e".toCharArray());
        map.put("@suffix:e", "e".toCharArray());
        return new NumberFilter.Lexicon(map);
    }
}
