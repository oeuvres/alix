package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.QueryTokenizerAttribute;
import com.github.oeuvres.alix.util.Char;

import static com.github.oeuvres.alix.lucene.analysis.tokenattributes.QueryTokenizerAttribute.Type.*;

/**
 * Lucene tokenizer for user query text.
 *
 * <p>The tokenizer preserves query syntax needed by a query builder. Quoted
 * text is emitted as one keyword token without the quote delimiters.
 * Parentheses are emitted as standalone keyword tokens. Wildcard characters
 * {@code *} and {@code ?} are kept inside ordinary tokens.</p>
 *
 * <p>This tokenizer does not perform French clitic splitting, stopword
 * removal, term lookup, MWE resolution, or Lucene query construction.</p>
 */
public final class QueryTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final QueryTokenizerAttribute typeAtt = addAttribute(QueryTokenizerAttribute.class);

    private int finalOffset;
    private int offset;
    private String text = "";

    /**
     * Constructs a query tokenizer.
     */
    public QueryTokenizer() {
        // Lucene creates tokenizers by constructor.
    }

    /**
     * Closes this tokenizer.
     *
     * @throws IOException if the underlying reader cannot be closed
     */
    @Override
    public void close() throws IOException {
        super.close();
        text = "";
        offset = 0;
        finalOffset = 0;
    }

    /**
     * Ends tokenization and sets the final offset.
     *
     * @throws IOException if Lucene cannot end the token stream
     */
    @Override
    public void end() throws IOException {
        super.end();
        final int corrected = correctOffset(finalOffset);
        offsetAtt.setOffset(corrected, corrected);
    }

    /**
     * Emits the next query token.
     *
     * @return true if a token was emitted
     * @throws IOException if the input cannot be read
     */
    @Override
    public boolean incrementToken() throws IOException {
        clearAttributes();

        final int length = text.length();
        while (offset < length) {
            final char c = normalizeChar(text.charAt(offset));
            if (isQuote(c) || c == '(' || c == ')' || isTokenChar(c)) {
                break;
            }
            offset++;
        }

        if (offset >= length) {
            finalOffset = length;
            return false;
        }

        final char c = normalizeChar(text.charAt(offset));
        if (isQuote(c)) {
            emitQuoted();
            return true;
        }

        if (c == '(' || c == ')') {
            emitParenthesis(c);
            return true;
        }

        emitWord();
        return true;
    }

    /**
     * Resets this tokenizer with a new reader.
     *
     * @throws IOException if the input cannot be read
     */
    @Override
    public void reset() throws IOException {
        super.reset();
        text = readInput();
        offset = 0;
        finalOffset = 0;
    }

    private void emitParenthesis(final char c) {
        final int start = offset;
        offset++;

        termAtt.append(c);
        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        posIncAtt.setPositionIncrement(1);
        if (c == '(') typeAtt.set(PAREN_OPEN);
        else if (c == ')') typeAtt.set(PAREN_CLOSE);

        finalOffset = offset;
    }

    private void emitQuoted() {
        final int start = offset;
        offset++;

        final int length = text.length();
        while (offset < length) {
            final char c = normalizeChar(text.charAt(offset));
            if (isQuote(c)) {
                offset++;
                break;
            }
            termAtt.append(c);
            offset++;
        }

        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        posIncAtt.setPositionIncrement(1);
        typeAtt.set(QUOTED);

        finalOffset = offset;
    }

    private void emitWord() {
        final int start = offset;
        boolean pattern = false;

        final int length = text.length();
        while (offset < length) {
            final char c = normalizeChar(text.charAt(offset));
            if (!isTokenChar(c)) {
                break;
            }
            if (c == '*' || c == '?') {
                pattern = true;
            }
            termAtt.append(c);
            offset++;
        }

        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        posIncAtt.setPositionIncrement(1);
        typeAtt.set(pattern ? PATTERN : WORD);

        finalOffset = offset;
    }

    private static boolean isQuote(final char c) {
        return c == '"' || c == '“' || c == '”';
    }

    private static boolean isTokenChar(final char c) {
        return Char.isToken(c)
            || c == '\''
            || c == '-'
            || c == '_'
            || c == '.'
            || c == '*'
            || c == '?';
    }

    private static char normalizeChar(final char c) {
        return switch (c) {
            case '\u2018', '\u2019', '\u02BC', '\uFF07' -> '\'';
            case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212' -> '-';
            case '\u00A0', '\u202F' -> ' ';
            default -> c;
        };
    }

    private String readInput() throws IOException {
        final StringBuilder builder = new StringBuilder(256);
        final char[] buffer = new char[4096];

        int read;
        while ((read = input.read(buffer)) >= 0) {
            builder.append(buffer, 0, read);
        }

        return builder.toString();
    }
}