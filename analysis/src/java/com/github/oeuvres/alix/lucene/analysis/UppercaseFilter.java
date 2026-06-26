package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.util.Char;

/**
 * Applies custom logic to tokens whose letters are all uppercase.
 * <p>
 * Digits, punctuation, and combining marks are ignored. A token containing
 * no letters is not considered uppercase.
 */
public final class UppercaseFilter extends TokenFilter
{
    private final CharArraySet ucWords;
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final int minUcLetters;

    /**
     * Creates an uppercase token filter.
     *
     * @param input source token stream
     */
    public UppercaseFilter(
        final TokenStream input,
        final CharArraySet ucWords,
        final int minUcLetters
    ) {
        super(input);
        this.ucWords = Objects.requireNonNull(ucWords, "ucWords");
        this.minUcLetters = minUcLetters;
    }

    /**
     * Reads and processes the next token.
     *
     * @return {@code true} when a token is available; otherwise {@code false}
     * @throws IOException if the input stream cannot be read
     */
    @Override
    public boolean incrementToken()
        throws IOException {
        if (!input.incrementToken()) {
            return false;
        }
        final int len = termAtt.length();
        int letters = 0;
        int nonletters = 0; // '-
        final char[] chars = termAtt.buffer();
        for (int i=0; i < len; i++) {
            final char c = chars[i];
            if (Char.isLetter(c)) {
                letters++;
                if (Char.isLowerCase(c)) return true;
            } else {
                nonletters++;
            }
        }
        if (ucWords.contains(termAtt.buffer(), 0, termAtt.length())) {
            return true;
        }
        // Initial, keep it, fore name resolution
        if (letters == 1) {
            return true;
        }
        // Suppress short words
        if (letters < minUcLetters) {
            termAtt.setEmpty();
            return true;
        }
        // capitalize
        for (int i=1; i < len; i++) {
            chars[i] = Char.toLower(chars[i]);
        }
        return true;
    }

}
