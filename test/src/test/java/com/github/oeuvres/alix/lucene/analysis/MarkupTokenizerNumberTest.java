package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.junit.jupiter.api.Test;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/** Regression tests for numeric and XML character recognition. */
public class MarkupTokenizerNumberTest
{
    private record Token(String term, int pos, int start, int end) {}

    /**
     * Tokenizes the source without additional filters.
     * @param text source text
     * @return terms and attributes
     * @throws IOException on stream error
     */
    private static List<Token> tokenize(final String text) throws IOException
    {
        try (MarkupTokenizer tokenizer = new MarkupTokenizer()) {
            tokenizer.setReader(new StringReader(text));
            final CharTermAttribute term = tokenizer.addAttribute(CharTermAttribute.class);
            final PosAttribute pos = tokenizer.addAttribute(PosAttribute.class);
            final OffsetAttribute off = tokenizer.addAttribute(OffsetAttribute.class);
            final List<Token> result = new ArrayList<>();
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                result.add(new Token(term.toString(), pos.getPos(), off.startOffset(), off.endOffset()));
            }
            tokenizer.end();
            return result;
        }
    }

    /**
     * Compares the tokenizer's complete output.
     * @param source source text
     * @param expected expected terms
     * @throws IOException on stream error
     */
    private static void terms(final String source, final String... expected) throws IOException
    {
        assertEquals(List.of(expected), tokenize(source).stream().map(Token::term).toList(), source);
    }

    /** Tests numeric characters and ambiguous ordinary spaces. */
    @Test
    void numbers() throws IOException
    {
        terms("1 234,56", "1", "234,56");
        terms("1\u00a0234,56", "1234,56");
        terms("1\u202f234,56", "1234,56");
        terms("1\u2009234,56", "1234,56");
        terms("12.", "12", ".");
        terms("12.</p>", "12", ".", "</p>");
        terms("1,2,3", "1,2", ",", "3");
        terms("7-8 7‐8 7‑8 7–8", "7", "-", "8", "7", "-", "8", "7", "-", "8", "7", "–", "8");
        terms("-8", "-8");
        terms("−8", "−8");
        terms("½ 2½ −½ ¼ ⅓", "½", "2½", "−", "½", "¼", "⅓");
        terms("−½", "−½");
        terms("16ème 3e10 3e-10 3E+10", "16ème", "3e10", "3e-10", "3E+10");
        terms("25% 20°C 3×4 x=2", "25", "%", "20", "°", "C", "3", "×", "4", "x", "=", "2");
        terms("2&lt;3", "2", "<", "3");
        terms("é e\u0301 x²", "é", "e\u0301", "x²");
        terms("a\n\n\nb", "a", "↵↵↵", "b");
        final List<Token> tokens = tokenize("1\u00a0234,56.");
        assertEquals(new Token("1234,56", DIGIT.code, 0, 8), tokens.get(0));
        assertEquals(new Token(".", PUNCTsent.code, 8, 9), tokens.get(1));
    }

    /** Checks XML markup, quoted delimiters, and visible CDATA content. */
    @Test
    void markup() throws IOException
    {
        terms("<ref n=\"a>b\">12.</ref>", "<ref n=\"a>b\">", "12", ".", "</ref>");
        terms("a<!-- x > y -->b", "a", "<!-- x > y -->", "b");
        terms("<?test a>b?>x", "<?test a>b?>", "x");
        terms("<!DOCTYPE p [<!ENTITY x '>'>]><p>2</p>", "<!DOCTYPE p [<!ENTITY x '>'>]>", "<p>", "2", "</p>");
        terms("<![CDATA[3<4]]>", "<![CDATA[", "3", "<", "4", "]]>");
        final List<Token> tokens = tokenize("<ref n='>'>12</ref>");
        assertEquals(XML.code, tokens.get(0).pos());
        assertEquals(11, tokens.get(0).end());
        assertEquals(11, tokens.get(1).start());
    }

    /** Exercises lookahead at the CharacterBuffer refill boundary. */
    @Test
    void bufferRefill() throws IOException
    {
        terms(" ".repeat(32766) + "1\u00a0234,56", "1234,56");
        terms(" ".repeat(32765) + "<!-- > -->12", "<!-- > -->", "12");
    }
}
