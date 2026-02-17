package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.CharArraySet;
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
 * Micro-optimized tokenizer for latin script languages and XML-like tags.
 * Keeps tags as tokens (flags XML), clause punctuation as tokens (flags PUNCTclause),
 * sentence punctuation runs as tokens (flags PUNCTsent), numbers as tokens (flags DIGIT).
 *
 * Differences vs original TokenizerML:
 * - Entity decoding uses direct checks (no Map iteration).
 * - No buffer backtracking; uses a one-char pushback slot.
 * - Sentence punctuation token cannot absorb following letters (e.g., "!Word" no longer possible).
 */
public class TokenizerML extends Tokenizer
{
    /** Max size of a word-like token (not tags). */
    private static final int TOKEN_MAX_SIZE = 256;

    /** IO buffer size (chars). Tune for your workload; 2 MiB per instance is usually wasteful. */
    private static final int IO_BUFFER_SIZE = 32 * 1024;
    
    /** Dr., etc. */
    private final CharArraySet keepTrailingDot;
    
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

    private final CharacterBuffer buffer = CharacterUtils.newCharacterBuffer(IO_BUFFER_SIZE);

    private int bufferIndex = 0;
    private int bufferLen = 0;
    /** Current char offset (UTF-16 code units). Points to next char to read. */
    private int offset = 0;

    /** One-char pushback (used to re-emit punctuation stripped from numbers/abbrev-dot). */
    private int pendingChar = -1;          // 0..65535, or -1
    private int pendingCharOffset = -1;    // offset where pendingChar occurs

    public TokenizerML() { 
        this(CharArraySet.EMPTY_SET);
    }

    public TokenizerML(final CharArraySet keepTrailingDot) { 
        super();
        // Lucene-style: accept null as “no config”
        this.keepTrailingDot = (keepTrailingDot == null) ? CharArraySet.EMPTY_SET : keepTrailingDot;
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        clearAttributes();

        // Fast path: emit pending punctuation first (no buffer rewinds).
        if (pendingCharOffset >= 0) {
            return emitPendingPunct();
        }

        char[] termBuf = termAtt.buffer();
        int termLen = 0;

        boolean inTag = false;
        boolean inNumber = false;
        boolean inSentPunct = false;

        boolean abbrevDot = false; // last appended '.' after a letter; may need to detach next char
        int amp = -1;              // position of '&' in termBuf (for &gt;/&lt;/&amp;)

        int startOffset = -1;
        int tokenEndOff = -1; // if set, overrides "off" for offsetAtt end

        int bi = bufferIndex;
        int bl = bufferLen;
        int off = offset;
        char[] io = buffer.getBuffer();

        char lastChar = 0;

        while (true) {
            // refill
            if (bi >= bl) {
                CharacterUtils.fill(buffer, input);
                bl = buffer.getLength();
                bi = 0;
                io = buffer.getBuffer();
                if (bl == 0) {
                    if (termLen > 0) break; // emit last token
                    // store back cursor
                    bufferIndex = bi; bufferLen = bl; offset = off;
                    return false;
                }
            }

            final char c = io[bi];

            // If currently emitting sentence punctuation, stop as soon as next char is not .?!…
            if (inSentPunct) {
                if (isSentencePunct(c)) {
                    // append and consume
                    if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                    termBuf[termLen++] = c;
                    bi++; off++; lastChar = c;
                    continue;
                }
                break; // do not consume delimiter
            }

            // Inside a tag: append until '>' inclusive
            if (inTag) {
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = c;
                bi++; off++; lastChar = c;
                if (c == '>') {
                    flagsAtt.setFlags(XML.code);
                    break;
                }
                continue;
            }

            // Abbrev-dot resolution: previous char appended '.' after a letter.
            // Decide whether '.' stays with the token (internal) or becomes punctuation.
            if (abbrevDot) {
                if (!Char.isLetter(c)) {
                    
                    // 1) keep dot for 1-letter abbreviation ("M.") — existing policy
                    final boolean oneLetterAbbrev = (termLen == 2 && Char.isLetter(termBuf[0]));
                    
                    // 2) optional: keep dot for configured abbreviations (termBuf ends with '.')
                    //    test WITHOUT the final dot: [0, termLen-1)
                    final boolean listedAbbrev =
                            !oneLetterAbbrev
                            && keepTrailingDot != CharArraySet.EMPTY_SET
                            && keepTrailingDot.contains(termBuf, 0, termLen - 1);
                    
                    if (!oneLetterAbbrev && !listedAbbrev) {
                        // detach '.' and re-emit as punctuation
                        termLen--;
                        pendingChar = '.';
                        pendingCharOffset = off - 1; // '.' already consumed
                        tokenEndOff = off - 1;       // token ends before '.'
                        abbrevDot = false;
                        break;
                    }
                    // else: keep the dot in the token; the delimiter will end the token naturally
                }
                // internal dot case: next char is a letter => keep dot, continue normally
                abbrevDot = false;
            }

            // Start of tag '<'
            if (c == '<') {
                if (termLen > 0) break; // emit pending token; tag will be processed next call
                inTag = true;
                startOffset = off;
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = c;
                bi++; off++; lastChar = c;
                continue;
            }

            // Number mode
            if (inNumber) {
                if (Char.isDigit(c)) {
                    if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                    termBuf[termLen++] = c;
                    bi++; off++; lastChar = c;
                    continue;
                }
                // Decimal separator candidate (original behavior: accept, then remove if it ends number)
                if ((c == '.' || c == ',') && lastChar != '.' && lastChar != ',') {
                    if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                    termBuf[termLen++] = c;
                    bi++; off++; lastChar = c;
                    continue;
                }

                // End of number: do not consume current c; strip trailing '.'/',' if present and push back.
                inNumber = false;
                if (termLen > 0) {
                    final char last = termBuf[termLen - 1];
                    if (last == '.' || last == ',') {
                        termLen--;
                        pendingChar = last;
                        pendingCharOffset = off - 1; // punctuation position
                        tokenEndOff = off - 1;       // <-- number ends BEFORE punctuation
                    }
                }
                break;
            }

            // Digits (start number if token empty; or negative number if "-" already in token)
            if (Char.isDigit(c)) {
                if (termLen == 0) {
                    inNumber = true;
                    flagsAtt.setFlags(DIGIT.code);
                    startOffset = off;
                }
                else if (termLen == 1 && lastChar == '-') {
                    inNumber = true;
                    flagsAtt.setFlags(DIGIT.code);
                }

                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = c;
                bi++; off++; lastChar = c;
                continue;
            }

            // XML entities: handle &gt; &lt; &amp; (keeps unknown entities verbatim).
            if (c == ';' && amp >= 0 && termLen >= amp + 2) {
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = ';';
                bi++; off++; lastChar = c;

                // name length between '&' and ';'
                final int nameLen = termLen - amp - 2;
                if (nameLen == 2) {
                    final char a = termBuf[amp + 1], b = termBuf[amp + 2];
                    if (a == 'g' && b == 't') {
                        termLen = amp;
                        if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                        termBuf[termLen++] = '>';
                    }
                    else if (a == 'l' && b == 't') {
                        termLen = amp;
                        if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                        termBuf[termLen++] = '<';
                    }
                }
                else if (nameLen == 3) {
                    final char a = termBuf[amp + 1], b = termBuf[amp + 2], d = termBuf[amp + 3];
                    if (a == 'a' && b == 'm' && d == 'p') {
                        termLen = amp;
                        if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                        termBuf[termLen++] = '&';
                    }
                }
                amp = -1;
                continue;
            }

            // Clause punctuation: standalone token
            if (isClausePunct(c)) {
                if (termLen > 0) break; // emit pending token; punctuation next call
                startOffset = off;
                flagsAtt.setFlags(PUNCTclause.code);
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(1);
                termBuf[0] = c;
                termLen = 1;
                bi++; off++; lastChar = c;

                // finalize and return immediately
                termAtt.setLength(termLen);
                posIncAtt.setPositionIncrement(1);
                posLenAtt.setPositionLength(1);
                final int endOffset = (tokenEndOff >= 0) ? tokenEndOff : off;
                offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));
                bufferIndex = bi; bufferLen = bl; offset = off;
                return true;
            }

            // Dot after a letter: may be abbrev/internal dot. Append now; decide next char whether to detach.
            if (c == '.' && termLen > 0 && Char.isLetter(c)) {
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = '.';
                bi++; off++; lastChar = '.';
                abbrevDot = true;
                continue;
            }

            // Sentence punctuation: standalone run token
            if (isSentencePunct(c)) {
                if (termLen > 0) break; // emit pending token; punctuation next call
                inSentPunct = true;
                flagsAtt.setFlags(PUNCTsent.code);
                startOffset = off;
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(1);
                termBuf[0] = c;
                termLen = 1;
                bi++; off++; lastChar = c;
                continue;
            }

            // Joker '*' only appended if token already started (original behavior).
            if (c == '*' && termLen > 0) {
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = '*';
                bi++; off++; lastChar = c;
                continue;
            }

            // Word-like token char
            if (Char.isToken(c)) {
                if (termLen == 0) startOffset = off;

                if (c == '&') amp = termLen;

                char out = c;
                if (out == '’') out = '\'';
                if (out != (char)0xAD) { // ignore soft hyphen
                    if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                    termBuf[termLen++] = out;
                }

                bi++; off++; lastChar = c;

                if (termLen >= TOKEN_MAX_SIZE) {
                    // original behavior: cut overly long tokens
                    break;
                }
                continue;
            }

            // Delimiter / whitespace / control / etc.
            if (termLen > 0) break;   // emit current token; do not consume delimiter
            bi++; off++; lastChar = c; // skip delimiter and continue
        }

        // Finalize token built in this call
        termAtt.setLength(termLen);
        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        final int endOffset = (tokenEndOff >= 0) ? tokenEndOff : off;
        offsetAtt.setOffset(correctOffset(startOffset), correctOffset(endOffset));

        bufferIndex = bi;
        bufferLen = bl;
        offset = off;

        return true;
    }

    private boolean emitPendingPunct() throws IOException
    {
        // pendingChar already consumed earlier; current (offset, bufferIndex) point after it.
        final int pOff = pendingCharOffset;
        final char pc = (char) pendingChar;
        pendingCharOffset = -1;
        pendingChar = -1;

        char[] termBuf = termAtt.buffer();
        if (termBuf.length == 0) termBuf = termAtt.resizeBuffer(1);
        termBuf[0] = pc;
        int termLen = 1;

        int bi = bufferIndex;
        int bl = bufferLen;
        int off = offset;
        char[] io = buffer.getBuffer();

        // Clause punctuation: standalone
        if (isClausePunct(pc)) {
            flagsAtt.setFlags(PUNCTclause.code);
            termAtt.setLength(termLen);
            posIncAtt.setPositionIncrement(1);
            posLenAtt.setPositionLength(1);
            offsetAtt.setOffset(correctOffset(pOff), correctOffset(pOff + 1));
            return true;
        }

        // Sentence punctuation: merge with following .?!… in buffer
        if (isSentencePunct(pc)) {
            flagsAtt.setFlags(PUNCTsent.code);

            while (true) {
                if (bi >= bl) {
                    CharacterUtils.fill(buffer, input);
                    bl = buffer.getLength();
                    bi = 0;
                    io = buffer.getBuffer();
                    if (bl == 0) break;
                }
                final char c = io[bi];
                if (!isSentencePunct(c)) break;

                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = c;
                bi++; off++;
            }

            termAtt.setLength(termLen);
            posIncAtt.setPositionIncrement(1);
            posLenAtt.setPositionLength(1);
            offsetAtt.setOffset(correctOffset(pOff), correctOffset(off));
            bufferIndex = bi; bufferLen = bl; offset = off;
            return true;
        }

        // Fallback: emit as a single-char token with no flags (should not happen with current uses).
        termAtt.setLength(termLen);
        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        offsetAtt.setOffset(correctOffset(pOff), correctOffset(pOff + 1));
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
        pendingChar = -1;
        pendingCharOffset = -1;
        buffer.reset();
    }

    // --- fast classification helpers ---

    private static boolean isSentencePunct(char c) {
        return c == '.' || c == '…' || c == '?' || c == '!';
    }

    private static boolean isClausePunct(char c) {
        // switch is generally compiled efficiently
        switch (c) {
            case ',':
            case ';':
            case ':':
            case '(':
            case ')':
            case '—':
            case '–':
            case '"':
            case '«':
            case '»':
                return true;
            default:
                return false;
        }
    }

}

