package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import com.github.oeuvres.alix.util.Char;

/**
 * Merges a one-letter uppercase given-name initial with the following
 * capitalized family-name token.
 *
 * <p>Examples:</p>
 *
 * <pre>
 * E Meyerson      -> E. Meyerson
 * E. Meyerson     -> E. Meyerson
 * I Meyerson      -> I. Meyerson
 * </pre>
 *
 * <p>The filter works on the token stream, not on the original character
 * stream. With {@code StandardTokenizer}, punctuation such as the dot in
 * {@code E. Meyerson} is usually not available as a token, so this filter
 * reconstructs the normalized dotted form.</p>
 *
 * <p>This filter should usually be placed immediately after the tokenizer and
 * before lowercasing, stemming, or lemmatization.</p>
 */
public final class PersInitialFilter extends TokenFilter {

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);

    private boolean exhausted;
    private State bufferedState;

    /**
     * Creates a filter using a maximum offset gap of {@code 2}.
     *
     * <p>This accepts both common tokenization cases:</p>
     *
     * <pre>
     * E Meyerson   // gap 1
     * E. Meyerson  // gap 2 if the dot was not emitted as a token
     * </pre>
     *
     * @param input source token stream
     */
    public PersInitialFilter(final TokenStream input) {
        this(input, 2);
    }

    /**
     * Creates a filter using the provided maximum offset gap between the
     * initial token and the family-name token.
     *
     * @param input source token stream
     * @param maxOffsetGap maximum accepted offset gap
     */
    public PersInitialFilter(final TokenStream input, final int maxOffsetGap) {
        super(input);

        if (maxOffsetGap < 0) {
            throw new IllegalArgumentException("maxOffsetGap must be >= 0");
        }
    }

    /**
     * Emits the next token, merging an initial and a following family name when
     * the pattern is recognized.
     *
     * @return {@code true} if a token was emitted, otherwise {@code false}
     * @throws IOException if the wrapped token stream fails
     */
    @Override
    public boolean incrementToken() throws IOException {
        if (!loadCurrent()) {
            return false;
        }
        
        if (!isInitial()) {
            return true;
        }

        final char[] firstBuffer = termAtt.buffer();



        final char initial = firstBuffer[0];
        final int firstPosInc = posIncAtt.getPositionIncrement();
        final int firstPosLen = posLenAtt.getPositionLength();
        final int firstStartOffset = offsetAtt.startOffset();
        final State firstState = captureState();

        if (!input.incrementToken()) {
            exhausted = true;
            restoreState(firstState);
            return true;
        }

        final int secondLength = termAtt.length();

        //  I. <span class=\"sc\">Meyerson</span>, offset gap is not relevant
        if (
            posIncAtt.getPositionIncrement() != 1
            || !isFamilyName()
        ) {
            bufferedState = captureState();
            restoreState(firstState);
            return true;
        }

        final int secondEndOffset = offsetAtt.endOffset();
        final int secondPosLen = posLenAtt.getPositionLength();

        final int mergedLength = secondLength + 3;
        final char[] mergedBuffer = termAtt.resizeBuffer(mergedLength);

        System.arraycopy(mergedBuffer, 0, mergedBuffer, 3, secondLength);
        mergedBuffer[0] = initial;
        mergedBuffer[1] = '.';
        mergedBuffer[2] = ' ';

        termAtt.setLength(mergedLength);
        offsetAtt.setOffset(firstStartOffset, secondEndOffset);
        posIncAtt.setPositionIncrement(firstPosInc);
        posLenAtt.setPositionLength(firstPosLen + secondPosLen);

        return true;
    }

    /**
     * Resets this filter for reuse.
     *
     * @throws IOException if the wrapped token stream fails
     */
    @Override
    public void reset() throws IOException {
        super.reset();
        bufferedState = null;
        exhausted = false;
    }

    /**
     * Returns whether the candidate token has a plausible family-name surface
     * form.
     *
     * @param buffer token buffer
     * @param length token length
     * @return {@code true} if the token starts with an uppercase letter and
     *         contains only letters, apostrophes, or hyphens
     */
    private boolean isFamilyName() {
        final int length = termAtt.length();
        if (length < 2) return false;
        char c0 = termAtt.charAt(0);
        if (!Char.isUpperCase(c0)) return false;
        // ensure that other chars are letters? 
        for (int i = 1; i < length; i++) {
            final char c = termAtt.charAt(i);

            if (Char.isLetter(c) || c == '-' || c == '\'' || c == '’') {
                continue;
            }

            return false;
        }
        return true;
    }

    /**
     * Returns whether the token is a one-letter uppercase initial, optionally
     * already followed by a dot.
     *
     * @return {@code true} for forms such as {@code E} or {@code E.}
     */
    private boolean isInitial() {
        final int length = termAtt.length();
        if (length < 1) return false;
        if (length > 2) return false;
        char c0 = termAtt.charAt(0);
        if (!Char.isUpperCase(c0)) return false;
        if (length > 1 && termAtt.charAt(1) != '.') return false;
        if (c0 == 'M') return false; // avoid M. (Monsieur)
        // É -> E
        ASCIIFoldingFilter.foldToASCII(termAtt.buffer(), 0, termAtt.buffer(), 0, 1);
        return true;
    }



    /**
     * Loads the current token either from a buffered lookahead state or from the
     * wrapped input stream.
     *
     * @return {@code true} if a current token is available
     * @throws IOException if the wrapped token stream fails
     */
    private boolean loadCurrent() throws IOException {
        if (bufferedState != null) {
            restoreState(bufferedState);
            bufferedState = null;
            return true;
        }

        if (exhausted) {
            return false;
        }

        if (!input.incrementToken()) {
            exhausted = true;
            return false;
        }

        return true;
    }
}

