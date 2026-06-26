/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.Char;

/**
 * Micro-optimized tokenizer for latin script languages and XML-like tags.
 * Keeps tags as tokens (flags XML), clause punctuation as tokens (flags PUNCTclause),
 * sentence punctuation runs as tokens (flags PUNCTsent), numbers as tokens (flags DIGIT).
 *
 * Trailing dot policy: a '.' appended after a letter stays in the token if it closes
 * a single-letter abbreviation or initial, including after an elision apostrophe
 * ("M.", "d'I."), a dotted abbreviation ("U.S.A.", "Ph.D.", "d'A.B."), or a listed
 * abbreviation ("etc."); otherwise it is detached and re-emitted as sentence punctuation.
 */
public class MarkupTokenizer extends Tokenizer
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
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    private final CharacterBuffer buffer = CharacterUtils.newCharacterBuffer(IO_BUFFER_SIZE);

    private int bufferIndex = 0;
    private int bufferLen = 0;
    /** Current char offset (UTF-16 code units). Points to next char to read. */
    private int offset = 0;

    /** One-char pushback (used to re-emit punctuation stripped from numbers/abbrev-dot). */
    private int pendingChar = -1;          // 0..65535, or -1
    private int pendingCharOffset = -1;    // offset where pendingChar occurs

    /**
     * Build a tokenizer with no configured abbreviation list.
     */
    public MarkupTokenizer() { 
        this(CharArraySet.EMPTY_SET);
    }

    /**
     * Build a tokenizer with a set of abbreviations (without trailing dot, e.g. "etc")
     * for which a trailing '.' is kept inside the token.
     *
     * @param keepTrailingDot abbreviations keeping their final dot; null means none.
     */
    public MarkupTokenizer(final CharArraySet keepTrailingDot) { 
        super();
        // Lucene-style: accept null as “no config”
        this.keepTrailingDot = (keepTrailingDot == null) ? CharArraySet.EMPTY_SET : keepTrailingDot;
    }

    /**
     * Set final offset at end of stream.
     */
    @Override
    public final void end() throws IOException
    {
        super.end();
        offsetAtt.setOffset(correctOffset(offset), correctOffset(offset));
    }

    /**
     * Produce next token.
     */
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

        boolean abbrevDot = false; // last appended char is '.' after a letter; pending decision
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
                    if (termLen > 0) break; // emit last token; abbrevDot resolved after loop
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
                    posAtt.setPos(XML.code);
                    break;
                }
                continue;
            }

            // Trailing-dot decision: a letter continues the token (internal dot, "Ph.D"),
            // a kept abbreviation falls through ("J.-C." may continue with '-'),
            // otherwise break; the detach itself is done once, after the loop.
            if (abbrevDot) {
                if (!Char.isLetter(c) && !keepsTrailingDot(termBuf, termLen)) {
                    break;
                }
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
                        tokenEndOff = off - 1;       // number ends BEFORE punctuation
                    }
                }
                break;
            }

            // Digits (start number if token empty; or negative number if "-" already in token)
            if (Char.isDigit(c)) {
                if (termLen == 0) {
                    inNumber = true;
                    posAtt.setPos(DIGIT.code);
                    startOffset = off;
                }
                else if (termLen == 1 && lastChar == '-') {
                    inNumber = true;
                    posAtt.setPos(DIGIT.code);
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
                posAtt.setPos(PUNCTclause.code);
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

            // Dot after a letter: may be abbrev/internal dot. Append now; decide on next char.
            if (c == '.' && termLen > 0 && Char.isLetter(termBuf[termLen - 1])) {
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
                posAtt.setPos(PUNCTsent.code);
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

                final char out;
                if (c == '\u2019' || c == '\u2018' || c == '\u02BC') out = '\'';
                else if (c == '\u2010' || c == '\u2011' || c == '\u00AD') out = '-';
                else out = c;

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

        // Trailing-dot detach: single site, reached either by in-loop break or at EOF.
        if (abbrevDot && !keepsTrailingDot(termBuf, termLen)) {
            termLen--;
            pendingChar = '.';
            pendingCharOffset = off - 1; // '.' already consumed
            tokenEndOff = off - 1;       // token ends before '.'
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

    /**
     * Reset internal state for a new input.
     */
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

    /**
     * Emit the one-char pushback as a punctuation token; sentence punctuation
     * merges with a following .?!… run in the buffer.
     */
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
            posAtt.setPos(PUNCTclause.code);
            termAtt.setLength(termLen);
            posIncAtt.setPositionIncrement(1);
            posLenAtt.setPositionLength(1);
            offsetAtt.setOffset(correctOffset(pOff), correctOffset(pOff + 1));
            return true;
        }

        // Sentence punctuation: merge with following .?!… in buffer
        if (isSentencePunct(pc)) {
            posAtt.setPos(PUNCTsent.code);

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

    /**
     * Test for clause punctuation, kept as standalone single-char tokens.
     */
    private static boolean isClausePunct(char c) {
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

    /**
     * Test for sentence punctuation, merged in runs ("?!", "...").
     */
    private static boolean isSentencePunct(char c) {
        return c == '.' || c == '…' || c == '?' || c == '!';
    }

    /**
     * Decide if a trailing '.' (buf[len-1], known to follow a letter) stays in the token.
     * True for: a single letter at token start or right after an elision apostrophe
     * ("M.", "d'I."); a dotted abbreviation, possibly after a clitic ("U.S.A.", "d'A.B.");
     * a listed abbreviation tested without its final dot ("etc.").
     * Apostrophes in buf are already normalized to '\'' by the append path.
     */
    private boolean keepsTrailingDot(final char[] buf, final int len)
    {
        final int letter = len - 2;
        if (Char.isLetter(buf[letter]) && (letter == 0 || buf[letter - 1] == '\'')) {
            return true;
        }
        // dotted-abbrev check starts after the last apostrophe, if any
        int from = 0;
        for (int i = len - 2; i > 0; i--) {
            if (buf[i - 1] == '\'' || buf[i - 1] == '’') {
                from = i;
                break;
            }
        }
        if (looksLikeDottedAbbrev(buf, from, len)) return true;
        return keepTrailingDot.contains(buf, from, len - from - 1);
    }

    /**
     * Heuristic on buf[from, len): ends with '.' and contains internal dots separating
     * short (1–3) letter-only segments. Examples: "U.S.A.", "e.g.", "Ph.D.".
     */
    private static boolean looksLikeDottedAbbrev(final char[] buf, final int from, final int len)
    {
        if (len - from < 4 || buf[len - 1] != '.') return false; // at least "A.B."
        int segLen = 0;
        boolean hasInternalDot = false;
        for (int i = from; i < len - 1; i++) { // exclude trailing '.'
            final char c = buf[i];
            if (c == '.') {
                if (segLen == 0 || segLen > 3) return false;
                hasInternalDot = true;
                segLen = 0;
                continue;
            }
            if (!Char.isLetter(c)) return false;
            segLen++;
            if (segLen > 3) return false;
        }
        return hasInternalDot && segLen > 0 && segLen <= 3;
    }

}