package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.util.Char;

/**
 * Removes unresolved tokens that are structurally unlikely to be useful words.
 *
 * <p>Tokens marked with {@link KeywordAttribute} are considered resolved by an
 * earlier dictionary or normalization filter and are always preserved.</p>
 */
public final class OCRGarbageFilter extends FilteringTokenFilter
{
    /** Minimum number of letters required for an unresolved token. */
    private static final int MIN_UNKNOWN_LETTERS = 3;

    /** The current token keyword flag. */
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);

    /** The current token term. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /**
     * Creates an OCR-garbage filter.
     *
     * @param input token stream to filter
     */
    public OCRGarbageFilter(final TokenStream input)
    {
        super(input);
    }

    /**
     * Tests whether the current token should remain in the token stream.
     *
     * @return {@code true} if the token should be preserved
     * @throws IOException if the input token stream cannot be read
     */
    @Override
    protected boolean accept() throws IOException
    {
        if (keywordAtt.isKeyword()) return true;

        final int length = termAtt.length();
        if (length == 0) return false;

        final char[] term = termAtt.buffer();
        boolean hasLowercase = false;
        boolean hasUppercase = false;
        boolean hasVowel = false;
        int letterCount = 0;
        char previousLetter = 0;
        int repeatedLetters = 0;

        for (int pos = 0; pos < length; pos++) {
            final char c = term[pos];

            if (Char.isLetter(c)) {
                if (!Char.isLatin(c)) return false;

                letterCount++;
                hasLowercase |= Character.isLowerCase(c);
                hasUppercase |= Character.isUpperCase(c);
                hasVowel |= isVowel(c);

                final char folded = Character.toLowerCase(c);
                if (folded == previousLetter) {
                    if (++repeatedLetters >= 3) return false;
                }
                else {
                    previousLetter = folded;
                    repeatedLetters = 1;
                }
                continue;
            }

            if (c != '-' && c != '\'' && c != '’') return false;
            if (pos == 0 || pos == length - 1) return false;
            if (!Char.isLetter(term[pos - 1]) || !Char.isLetter(term[pos + 1])) return false;

            previousLetter = 0;
            repeatedLetters = 0;
        }

        if (letterCount < MIN_UNKNOWN_LETTERS) return false;
        if (!hasVowel) return false;
        if (hasUppercase && !hasLowercase) return false;
        if (isRomanNumeral(term, length)) return false;

        return true;
    }

    /**
     * Consumes one decimal rank of a canonical Roman numeral.
     *
     * @param term token characters
     * @param pos first character of the rank
     * @param end exclusive end of the numeral component
     * @param one symbol representing one unit of the rank
     * @param five symbol representing five units of the rank
     * @param ten symbol representing ten units of the rank
     * @return the first unconsumed position, or {@code -1} for an invalid rank
     */
    private static int consumeRomanRank(
        final char[] term,
        int pos,
        final int end,
        final char one,
        final char five,
        final char ten
    )
    {
        if (pos + 1 < end && upperAscii(term[pos]) == one) {
            final char next = upperAscii(term[pos + 1]);
            if (next == five || next == ten) return pos + 2;
        }

        if (pos < end && upperAscii(term[pos]) == five) pos++;

        int count = 0;
        while (pos < end && upperAscii(term[pos]) == one) {
            if (++count > 3) return -1;
            pos++;
        }
        return pos;
    }

    /**
     * Tests whether a token consists of one or more canonical Roman numerals
     * separated by hyphens.
     *
     * @param term token characters
     * @param length token length
     * @return {@code true} if the complete token is Roman-numeral notation
     */
    private static boolean isRomanNumeral(final char[] term, final int length)
    {
        int start = 0;
        for (int pos = 0; pos <= length; pos++) {
            if (pos < length && term[pos] != '-') continue;
            if (!isRomanNumeralPart(term, start, pos)) return false;
            start = pos + 1;
        }
        return true;
    }

    /**
     * Tests one canonical Roman numeral component.
     *
     * @param term token characters
     * @param start inclusive component start
     * @param end exclusive component end
     * @return {@code true} if the component is a canonical Roman numeral
     */
    private static boolean isRomanNumeralPart(
        final char[] term,
        final int start,
        final int end
    )
    {
        if (start >= end) return false;

        int pos = start;
        int thousands = 0;
        while (pos < end && upperAscii(term[pos]) == 'M') {
            if (++thousands > 4) return false;
            pos++;
        }

        pos = consumeRomanRank(term, pos, end, 'C', 'D', 'M');
        if (pos < 0) return false;
        pos = consumeRomanRank(term, pos, end, 'X', 'L', 'C');
        if (pos < 0) return false;
        pos = consumeRomanRank(term, pos, end, 'I', 'V', 'X');
        return pos == end;
    }

    /**
     * Tests whether a character is treated as a vowel by this French-oriented heuristic.
     *
     * <p>{@code y} is included because this is a word-likeness heuristic, not
     * a syllabification rule. The ligatures {@code æ} and {@code œ} are also
     * counted as vowels.</p>
     *
     * @param c character to test
     * @return {@code true} if the character is treated as a vowel
     */
    private static boolean isVowel(final char c)
    {
        switch (c) {
            case 'A': case 'a':
            case 'À': case 'à':
            case 'Â': case 'â':
            case 'Ä': case 'ä':
            case 'Æ': case 'æ':
            case 'E': case 'e':
            case 'É': case 'é':
            case 'È': case 'è':
            case 'Ê': case 'ê':
            case 'Ë': case 'ë':
            case 'I': case 'i':
            case 'Î': case 'î':
            case 'Ï': case 'ï':
            case 'O': case 'o':
            case 'Ô': case 'ô':
            case 'Ö': case 'ö':
            case 'Œ': case 'œ':
            case 'U': case 'u':
            case 'Ù': case 'ù':
            case 'Û': case 'û':
            case 'Ü': case 'ü':
            case 'Y': case 'y':
            case 'Ÿ': case 'ÿ':
                return true;
            default:
                return false;
        }
    }

    /**
     * Converts an ASCII lowercase letter to uppercase without locale handling.
     *
     * @param c character to convert
     * @return uppercase ASCII character, or the original character
     */
    private static char upperAscii(final char c)
    {
        if (c >= 'a' && c <= 'z') return (char)(c - ('a' - 'A'));
        return c;
    }
}
