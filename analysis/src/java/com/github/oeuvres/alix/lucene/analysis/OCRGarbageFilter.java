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
                hasLowercase |= Char.isLowerCase(c);
                hasUppercase |= Char.isUpperCase(c);
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

            previousLetter = 0;
            repeatedLetters = 0;
        }

        if (letterCount < MIN_UNKNOWN_LETTERS) return false;
        if (!hasVowel) return false;

        return true;
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
