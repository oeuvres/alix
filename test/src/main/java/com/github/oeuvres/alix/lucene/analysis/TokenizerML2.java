package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.util.Char;

/**
 * Tokenizer for latin-script languages with optional XML-like tags kept as tokens.
 * FlagsAttribute is set with a category code (see Upos).
 */
public class TokenizerML2 extends Tokenizer
{
    /** Max buffered size of a lexical token (characters). */
    private static final int TOKEN_MAX_SIZE = 256;

    /** Max buffered size of a tag token; offsets still span the full tag. */
    private static final int TAG_MAX_SIZE = 4096;

    /** Initial IO buffer size (characters). Keep reasonable; Lucene will refill as needed. */
    private static final int IO_BUFFER_SIZE = 32 * 1024;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

    private final CharacterBuffer buffer = CharacterUtils.newCharacterBuffer(IO_BUFFER_SIZE);

    private int bufferIndex = 0;
    private int bufferLen = 0;

    /** Current char offset in input (UTF-16 code units, as Lucene expects). */
    private int offset = 0;

    @Override
    public final boolean incrementToken() throws IOException
    {
        clearAttributes();

        boolean inTag = false;
        boolean inNumber = false;

        // Index (in termAtt) of the last '&' starting a possible entity, or -1.
        int entityAmp = -1;

        int startOffset = -1;
        int endOffset = -1;

        char lastConsumed = 0;

        while (true) {
            if (bufferIndex >= bufferLen) {
                CharacterUtils.fill(buffer, input);
                bufferLen = buffer.getLength();
                bufferIndex = 0;

                if (bufferLen == 0) {
                    if (!termAtt.isEmpty()) {
                        endOffset = offset; // end of stream ends current token
                        break;
                    }
                    return false;
                }
            }

            final char[] io = buffer.getBuffer();
            final char c = io[bufferIndex];
            final int curOffset = offset; // offset of c (exclusive end is offset+1 if consumed)

            // If we are currently producing a sentence punctuation token, it must stop
            // as soon as the next char is not sentence punctuation.
            if (!termAtt.isEmpty() && flagsAtt.getFlags() == PUNCTsent.code && !isSentencePunct(c)) {
                endOffset = curOffset; // do not consume c
                break;
            }

            // Start of a tag: emit pending token first, otherwise enter tag mode.
            if (!inTag && c == '<') {
                if (!termAtt.isEmpty()) {
                    endOffset = curOffset;
                    break; // do not consume '<' now
                }
                inTag = true;
                flagsAtt.setFlags(XML.code);
                startOffset = curOffset;
                appendTagChar('<');
                consumeOne();
                lastConsumed = '<';
                continue;
            }

            // Inside tag: consume until '>' (buffering only up to TAG_MAX_SIZE).
            if (inTag) {
                appendTagChar(c);
                consumeOne();
                lastConsumed = c;
                if (c == '>') {
                    endOffset = offset; // already consumed '>'
                    break;
                }
                continue;
            }

            // Entity start: explicitly accept '&' into a token so "&gt;" can be decoded.
            if (c == '&') {
                if (termAtt.isEmpty()) {
                    startOffset = curOffset;
                    flagsAtt.setFlags(X.code);
                }
                // Always buffer '&' (subject to TOKEN_MAX_SIZE like any lexical token)
                if (termAtt.length() < TOKEN_MAX_SIZE) termAtt.append('&');
                entityAmp = termAtt.length() - 1;
                consumeOne();
                lastConsumed = '&';
                continue;
            }

            // Entity end
            if (c == ';' && entityAmp >= 0) {
                if (termAtt.length() < TOKEN_MAX_SIZE) termAtt.append(';');
                decodeBasicEntityInPlace(entityAmp);
                entityAmp = -1;
                consumeOne();
                lastConsumed = ';';
                continue;
            }

            // Digits: can be appended to existing lexical token, but if number-mode started,
            // it ends when a non-digit arrives (no backtracking).
            if (Char.isDigit(c)) {
                if (termAtt.isEmpty()) {
                    startOffset = curOffset;
                    flagsAtt.setFlags(DIGIT.code);
                    inNumber = true;
                }
                // negative number already started with '-'
                if (termAtt.length() == 1 && lastConsumed == '-' && inNumber == false) {
                    flagsAtt.setFlags(DIGIT.code);
                    inNumber = true;
                }
                termAtt.append(c);
                consumeOne();
                lastConsumed = c;
                continue;
            }

            // Decimal separator inside a number only if followed by a digit (in-buffer lookahead).
            if (inNumber && (c == '.' || c == ',') && isNextCharDigit()) {
                termAtt.append(c);
                consumeOne();
                lastConsumed = c;
                continue;
            }

            // Number ends: do not consume current char; it will be processed next call.
            if (inNumber && !Char.isDigit(c)) {
                inNumber = false;
                endOffset = curOffset;
                break;
            }

            // Clause punctuation: always a standalone token.
            if (isClausePunct(c)) {
                if (!termAtt.isEmpty()) {
                    endOffset = curOffset;
                    break; // do not consume punctuation now
                }
                startOffset = curOffset;
                endOffset = curOffset + 1;
                flagsAtt.setFlags(PUNCTclause.code);
                termAtt.append(c);
                consumeOne();
                lastConsumed = c;
                break;
            }

            // Sentence punctuation: emit sequences like "..." / "!!" as one token.
            if (isSentencePunct(c)) {
                if (!termAtt.isEmpty()) {
                    endOffset = curOffset;
                    break; // do not consume punctuation now
                }
                startOffset = curOffset;
                flagsAtt.setFlags(PUNCTsent.code);
                termAtt.append(c);
                consumeOne();
                lastConsumed = c;
                continue; // keep accumulating sentence punctuation
            }

            // Asterisk joker: keep old behavior, but allow start-of-token '*' as well.
            if (c == '*') {
                if (termAtt.isEmpty()) {
                    startOffset = curOffset;
                    flagsAtt.setFlags(X.code);
                }
                termAtt.append(c);
                consumeOne();
                lastConsumed = c;
                if (termAtt.length() >= TOKEN_MAX_SIZE) {
                    endOffset = offset;
                    break;
                }
                continue;
            }

            // Lexical token char
            if (Char.isToken(c)) {
                if (termAtt.isEmpty()) {
                    startOffset = curOffset;
                    flagsAtt.setFlags(X.code);
                }

                char out = c;
                if (out == '’') out = '\'';          // normalize apostrophe
                if (out != (char) 0xAD) termAtt.append(out); // ignore soft hyphen

                consumeOne();
                lastConsumed = c;

                if (termAtt.length() >= TOKEN_MAX_SIZE) {
                    endOffset = offset;
                    break; // keep old behavior: split long tokens
                }
                continue;
            }

            // Dot handling: keep '.' inside token only when it is clearly internal:
            // - one-letter abbreviation "M." (keep as token), or
            // - between letters like "U.S.A" (internal dots)
            if (c == '.' && !termAtt.isEmpty() && Char.isLetter(lastConsumed)) {
                final int tlen = termAtt.length();
                if (tlen == 1 && Char.isLetter(termAtt.charAt(0))) {
                    termAtt.append('.');
                    consumeOne();
                    lastConsumed = '.';
                    endOffset = offset;
                    break; // "M." is complete as a token
                }
                if (isNextCharLetter()) {
                    termAtt.append('.');
                    consumeOne();
                    lastConsumed = '.';
                    continue; // internal dot, keep going
                }
                // otherwise '.' is punctuation: end token before dot, do not consume it
                endOffset = curOffset;
                break;
            }

            // Any other non-token char: if we have a token buffered, emit it; else skip.
            if (!termAtt.isEmpty()) {
                endOffset = curOffset;
                break;
            }

            // skip separators
            consumeOne();
            lastConsumed = c;
        }

        // Defensive defaults
        if (startOffset < 0) startOffset = 0;
        if (endOffset < 0) endOffset = startOffset;

        // If we produced a decoded tag via entities (e.g., "&lt;p&gt;"), classify as XML.
        final int f = flagsAtt.getFlags();
        if (f == 0 || f == X.code) {
            final int l = termAtt.length();
            if (l >= 3 && termAtt.charAt(0) == '<' && termAtt.charAt(l - 1) == '>') {
                flagsAtt.setFlags(XML.code);
            }
            else if (f == 0) {
                flagsAtt.setFlags(X.code);
            }
        }

        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));
        return true;
    }

    @Override
    public final void end() throws IOException
    {
        super.end();
        offsetAtt.setOffset(correctOffset(offset), correctOffset(offset));
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        bufferIndex = 0;
        bufferLen = 0;
        offset = 0;
        buffer.reset();
    }

    private void consumeOne() {
        bufferIndex++;
        offset++;
    }

    private void appendTagChar(char c) {
        if (termAtt.length() < TAG_MAX_SIZE) termAtt.append(c);
    }

    private boolean isNextCharDigit() {
        // in-buffer lookahead only; if at boundary, we prefer to *not* treat as decimal separator
        if (bufferIndex + 1 >= bufferLen) return false;
        return Char.isDigit(buffer.getBuffer()[bufferIndex + 1]);
    }

    private boolean isNextCharLetter() {
        if (bufferIndex + 1 >= bufferLen) return false;
        return Char.isLetter(buffer.getBuffer()[bufferIndex + 1]);
    }

    private static boolean isClausePunct(char c) {
        return c == ',' || c == ';' || c == ':' || c == '(' || c == ')' ||
               c == '—' || c == '–' || c == '"' || c == '«' || c == '»';
    }

    private static boolean isSentencePunct(char c) {
        return c == '.' || c == '…' || c == '?' || c == '!';
    }

    private void decodeBasicEntityInPlace(int ampPos) {
        // termAtt contains "...&name;" (ampPos points to '&')
        final int len = termAtt.length();
        final int nameLen = len - ampPos - 2; // exclude '&' and ';'
        if (nameLen <= 0) return;

        char repl = 0;
        if (nameLen == 2) {
            final char a = termAtt.charAt(ampPos + 1);
            final char b = termAtt.charAt(ampPos + 2);
            if (a == 'g' && b == 't') repl = '>';
            else if (a == 'l' && b == 't') repl = '<';
        }
        else if (nameLen == 3) {
            final char a = termAtt.charAt(ampPos + 1);
            final char b = termAtt.charAt(ampPos + 2);
            final char c = termAtt.charAt(ampPos + 3);
            if (a == 'a' && b == 'm' && c == 'p') repl = '&';
        }

        if (repl != 0) {
            termAtt.setLength(ampPos);
            termAtt.append(repl);
        }
    }
}
