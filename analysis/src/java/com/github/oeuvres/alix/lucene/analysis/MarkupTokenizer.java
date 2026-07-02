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
import java.util.ArrayDeque;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.lucene.analysis.TokenStateQueue.OverflowPolicy;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.Char;

/**
 * Micro-optimized tokenizer for Latin-script languages and XML-like tags.
 * Keeps tags as tokens (flags XML), clause punctuation as tokens (flags PUNCTclause),
 * sentence punctuation runs as tokens (flags PUNCTsent), numbers as tokens (flags DIGIT).
 *
 * <p>An attached trailing dot is always retained by the raw character pass. Configured or
 * structurally recognized brevidots keep it unconditionally. Other dotted tokens are buffered
 * until a following token resolves the dot:</p>
 * <ul>
 *   <li>a lowercase word, comma, semicolon, or colon keeps all pending dots attached;</li>
 *   <li>an uppercase/titlecase word, a number, sentence punctuation, or end of input detaches
 *       only the last pending dot and emits it as sentence punctuation;</li>
 *   <li>XML tokens are transparent to the decision;</li>
 *   <li>wrapper punctuation is transparent but separates abbreviation-like dotted sequences:
 *       the following lexical token is still tested as sentence-start evidence;</li>
 *   <li>another unresolved dotted token extends the pending sequence only when no transparent
 *       punctuation intervenes.</li>
 * </ul>
 *
 * <p>A brevidot never emits a sentence-boundary event. This deliberately favours common forms
 * such as {@code Dr. Martin} and {@code J.-J. Rousseau} over detecting the uncommon case where
 * an abbreviation also ends a sentence.</p>
 *
 * <p>The five predefined XML entities ({@code &amp;amp;}, {@code &amp;apos;},
 * {@code &amp;gt;}, {@code &amp;lt;}, and {@code &amp;quot;}) are decoded as character data.
 * The decoded character is then classified normally instead of being appended blindly to the
 * current term. For example, {@code B’&amp;gt;} produces {@code B'} rather than {@code B'>}.</p>
 */
public class MarkupTokenizer extends Tokenizer
{
    /**
     * Synthetic sentence-dot event scheduled after a given number of queued tokens.
     */
    private static final class SentenceDot
    {
        /** Number of queued tokens that must be emitted before this dot. */
        private final int afterTokenCount;

        /** Corrected end offset of the source dot. */
        private final int endOffset;

        /** Corrected start offset of the source dot. */
        private final int startOffset;

        /**
         * Create a scheduled sentence-dot event.
         *
         * @param afterTokenCount number of queued tokens preceding the event
         * @param startOffset corrected start offset of the source dot
         * @param endOffset corrected end offset of the source dot
         */
        private SentenceDot(
            final int afterTokenCount,
            final int startOffset,
            final int endOffset
        ) {
            this.afterTokenCount = afterTokenCount;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    /** Max size of a word-like token (not tags). */
    private static final int TOKEN_MAX_SIZE = 256;

    /** IO buffer size (chars). Tune for your workload; 2 MiB per instance is usually wasteful. */
    private static final int IO_BUFFER_SIZE = 32 * 1024;

    /** Initial number of token states reserved for trailing-dot lookahead. */
    private static final int LOOKAHEAD_INITIAL_CAPACITY = 8;

    /** Hard guard against malformed input with no resolving token. */
    private static final int LOOKAHEAD_MAX_CAPACITY = 4096;

    /** Configured brevidots, stored without their final dot. */
    private final CharArraySet brevidots;

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

    /** Decoded or literal punctuation already consumed from the source. */
    private int pendingChar = -1;          // 0..65535, or -1
    private int pendingCharOffset = -1;    // source start offset
    private int pendingCharEndOffset = -1; // source end offset

    /** Complete token states waiting for a trailing-dot decision or ordered replay. */
    private TokenStateQueue lookahead;

    /** Sentence-dot events waiting to be emitted among queued token states. */
    private final ArrayDeque<SentenceDot> pendingSentenceDots = new ArrayDeque<>();

    /** Number of token states already emitted from the current lookahead queue. */
    private int emittedLookaheadTokens;

    /**
     * Build a tokenizer with no configured abbreviation list.
     */
    public MarkupTokenizer() { 
        this(CharArraySet.EMPTY_SET);
    }

    /**
     * Build a tokenizer with configured brevidots. Entries omit the trailing dot, for example
     * {@code "Dr"}, {@code "etc"}, or {@code "Var"}.
     *
     * @param brevidots forms whose final dot always remains inside the token; null means none
     */
    public MarkupTokenizer(final CharArraySet brevidots) { 
        super();
        this.brevidots = (brevidots == null) ? CharArraySet.EMPTY_SET : brevidots;
    }

    /**
     * Set final offset at end of stream.
     *
     * @throws IOException if the input stream cannot be finalized
     */
    @Override
    public final void end() throws IOException
    {
        super.end();
        offsetAtt.setOffset(correctOffset(offset), correctOffset(offset));
    }

    /**
     * Produce the next contextually resolved token.
     *
     * @return {@code true} if a token was emitted
     * @throws IOException if the input cannot be read
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        if (hasQueuedOutput()) {
            return emitQueuedOutput();
        }

        if (!readToken()) {
            return false;
        }

        if (!isUnknownDottedToken(this)) {
            return true;
        }

        resolveDottedSequence();
        return emitQueuedOutput();
    }

    /**
     * Read one raw token from the character stream. Attached final dots remain in word tokens;
     * contextual resolution is performed by {@link #incrementToken()}.
     *
     * @return {@code true} if a token was read
     * @throws IOException if the input cannot be read
     */
    private boolean readToken() throws IOException
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

        boolean trailingDot = false; // last appended char is '.' after a letter
        int amp = -1;              // position of a possible XML entity in termBuf

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
                    posAtt.setPos(XML.code);
                    break;
                }
                continue;
            }

            // A following letter continues an internal dotted form. A recognized brevidot may
            // also continue with a hyphen, as in "J.-J.". Otherwise the dotted token ends here.
            if (trailingDot) {
                if (!Char.isLetter(c) && !isBrevidot(termBuf, termLen)) {
                    break;
                }
                trailingDot = false;
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
                        pendingCharEndOffset = off;
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

            // Decode the five predefined XML entities. Unknown entities remain verbatim.
            if (c == ';' && amp >= 0 && termLen >= amp + 2) {
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = ';';
                bi++; off++; lastChar = c;

                final int entityTermStart = amp;
                final int entityStartOffset = off - (termLen - entityTermStart);
                final char decoded = decodeXmlEntity(termBuf, entityTermStart, termLen);
                amp = -1;

                if (decoded == 0) {
                    continue;
                }

                // Remove the source spelling (&name;) before classifying the decoded character.
                termLen = entityTermStart;

                // Escaped angle brackets are character data. They must not enter the XML-tag
                // branch and are discarded just like ordinary non-token delimiters.
                if (decoded == '<' || decoded == '>') {
                    if (termLen > 0) {
                        tokenEndOff = entityStartOffset;
                        break;
                    }
                    lastChar = decoded;
                    continue;
                }

                if (isClausePunct(decoded) || isSentencePunct(decoded)) {
                    pendingChar = decoded;
                    pendingCharOffset = entityStartOffset;
                    pendingCharEndOffset = off;

                    if (termLen > 0) {
                        tokenEndOff = entityStartOffset;
                        break;
                    }

                    bufferIndex = bi;
                    bufferLen = bl;
                    offset = off;
                    return emitPendingPunct();
                }

                if (Char.isToken(decoded)) {
                    if (termLen == 0) startOffset = entityStartOffset;
                    if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                    termBuf[termLen++] = normalizeTokenChar(decoded);
                    lastChar = decoded;
                    continue;
                }

                // Any other decoded character behaves as an ordinary delimiter.
                if (termLen > 0) {
                    tokenEndOff = entityStartOffset;
                    break;
                }
                lastChar = decoded;
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
                trailingDot = true;
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

            // No more joker '*' support, this tokenizer is too heavy for query parser
            /*
            if (c == '*' && termLen > 0) {
                if (termLen == termBuf.length) termBuf = termAtt.resizeBuffer(termLen + 1);
                termBuf[termLen++] = '*';
                bi++; off++; lastChar = c;
                continue;
            }
            */

            // Word-like token char
            if (Char.isToken(c)) {
                if (termLen == 0) startOffset = off;

                if (c == '&') amp = termLen;

                final char out = normalizeTokenChar(c);

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

    /**
     * Emit the next queued token or the synthetic sentence dot scheduled between queued tokens.
     *
     * @return {@code true}; this method is called only when queued output exists
     */
    private boolean emitQueuedOutput()
    {
        final SentenceDot sentenceDot = pendingSentenceDots.peekFirst();
        if (sentenceDot != null && sentenceDot.afterTokenCount == emittedLookaheadTokens) {
            return emitSentenceDot();
        }

        clearAttributes();
        lookahead.removeFirst(this);
        emittedLookaheadTokens++;

        if (lookahead.isEmpty() && pendingSentenceDots.isEmpty()) {
            emittedLookaheadTokens = 0;
        }
        return true;
    }

    /**
     * Emit the next sentence dot detached from an unresolved dotted token.
     *
     * @return {@code true}
     */
    private boolean emitSentenceDot()
    {
        final SentenceDot sentenceDot = pendingSentenceDots.removeFirst();

        clearAttributes();
        termAtt.append('.');
        posAtt.setPos(PUNCTsent.code);
        posIncAtt.setPositionIncrement(1);
        posLenAtt.setPositionLength(1);
        offsetAtt.setOffset(sentenceDot.startOffset, sentenceDot.endOffset);

        if (lookahead.isEmpty() && pendingSentenceDots.isEmpty()) {
            emittedLookaheadTokens = 0;
        }
        return true;
    }

    /**
     * Test whether resolved token states or synthetic dots remain to be emitted.
     *
     * @return {@code true} when {@link #emitQueuedOutput()} may be called
     */
    private boolean hasQueuedOutput()
    {
        return !pendingSentenceDots.isEmpty() || (lookahead != null && !lookahead.isEmpty());
    }

    /**
     * Test whether a token is a comma, semicolon, or colon. These marks resolve all pending dots
     * as non-sentential.
     *
     * @param source token state to inspect
     * @return {@code true} for comma, semicolon, or colon
     */
    private static boolean isNonSentenceResolver(final AttributeSource source)
    {
        final CharTermAttribute term = source.getAttribute(CharTermAttribute.class);
        if (term.length() != 1) return false;
        final char c = term.charAt(0);
        return c == ',' || c == ';' || c == ':';
    }

    /**
     * Test whether a token consists only of sentence punctuation.
     *
     * @param source token state to inspect
     * @return {@code true} for a non-empty {@code .?!…} run
     */
    private static boolean isSentencePunctuationToken(final AttributeSource source)
    {
        final CharTermAttribute term = source.getAttribute(CharTermAttribute.class);
        final int length = term.length();
        if (length == 0) return false;
        for (int i = 0; i < length; i++) {
            if (!isSentencePunct(term.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Test whether a punctuation token is transparent while resolving an ambiguous trailing dot.
     * Transparent punctuation is preserved in the output queue but does not itself decide whether
     * the preceding dot is lexical or sentential. The next significant token remains decisive.
     *
     * <p>Comma, semicolon, and colon are intentionally excluded: they resolve pending dots as
     * non-sentential. Sentence punctuation is handled separately.</p>
     *
     * @param source token state to inspect
     * @return {@code true} for quotes, parentheses, en dash, or em dash
     */
    private static boolean isTransparentForDotDecision(final AttributeSource source)
    {
        final CharTermAttribute term = source.getAttribute(CharTermAttribute.class);
        return term.length() == 1 && isTransparentForDotDecision(term.charAt(0));
    }

    /**
     * Test whether a punctuation character is transparent for trailing-dot resolution.
     *
     * @param c punctuation character to inspect
     * @return {@code true} when lookahead must continue beyond this character
     */
    private static boolean isTransparentForDotDecision(final char c)
    {
        switch (c) {
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
     * Test whether a token is an unresolved word with an immediately attached final dot.
     *
     * @param source token state to inspect
     * @return {@code true} when contextual lookahead is required
     */
    private boolean isUnknownDottedToken(final AttributeSource source)
    {
        final CharTermAttribute term = source.getAttribute(CharTermAttribute.class);
        final int length = term.length();
        return length > 1
            && term.charAt(length - 1) == '.'
            && Char.isLetter(term.charAt(length - 2))
            && !isBrevidot(term.buffer(), length);
    }

    /**
     * Test whether a token is an XML-like tag emitted by this tokenizer.
     *
     * @param source token state to inspect
     * @return {@code true} for a token delimited by {@code <} and {@code >}
     */
    private static boolean isXmlToken(final AttributeSource source)
    {
        final CharTermAttribute term = source.getAttribute(CharTermAttribute.class);
        final int length = term.length();
        return length >= 2 && term.charAt(0) == '<' && term.charAt(length - 1) == '>';
    }

    /**
     * Prefix an adjacent sentence-punctuation token with the dot detached from the preceding word.
     *
     * @param punctuation queued sentence-punctuation state
     * @param dotStart corrected start offset of the detached dot
     */
    private static void mergeDotIntoSentencePunctuation(
        final AttributeSource punctuation,
        final int dotStart
    ) {
        final CharTermAttribute term = punctuation.getAttribute(CharTermAttribute.class);
        final int length = term.length();
        final char[] buffer = term.resizeBuffer(length + 1);
        System.arraycopy(buffer, 0, buffer, 1, length);
        buffer[0] = '.';
        term.setLength(length + 1);

        final OffsetAttribute offsets = punctuation.getAttribute(OffsetAttribute.class);
        offsets.setOffset(dotStart, offsets.endOffset());
    }

    /**
     * Remove the final dot from a queued word token and shorten its end offset accordingly.
     *
     * @param candidate queued dotted-token state
     * @param dotStart corrected start offset of the final dot
     */
    private static void removeTrailingDot(
        final AttributeSource candidate,
        final int dotStart
    ) {
        final CharTermAttribute term = candidate.getAttribute(CharTermAttribute.class);
        term.setLength(term.length() - 1);

        final OffsetAttribute offsets = candidate.getAttribute(OffsetAttribute.class);
        offsets.setOffset(offsets.startOffset(), dotStart);
    }

    /**
     * Buffer an unresolved dotted-token sequence and read ordinary raw tokens until its last dot
     * can be classified. The current token is the first unresolved dotted token.
     *
     * @throws IOException if the input cannot be read
     */
    private void resolveDottedSequence() throws IOException
    {
        if (lookahead == null) {
            lookahead = new TokenStateQueue(
                LOOKAHEAD_INITIAL_CAPACITY,
                LOOKAHEAD_MAX_CAPACITY,
                OverflowPolicy.GROW,
                this
            );
        }

        lookahead.addLast(this);
        int lastCandidateIndex = 0;
        int lastDotStart = correctOffset(offset - 1);
        int lastDotEnd = correctOffset(offset);
        boolean crossedTransparentPunctuation = false;

        while (readToken()) {
            final boolean unknownDotted = isUnknownDottedToken(this);
            lookahead.addLast(this);
            final int currentIndex = lookahead.size() - 1;

            if (isXmlToken(this)) {
                continue;
            }

            if (isTransparentForDotDecision(this)) {
                crossedTransparentPunctuation = true;
                continue;
            }

            if (isNonSentenceResolver(this)) {
                return;
            }

            if (unknownDotted) {
                if (crossedTransparentPunctuation && startsSentence(this)) {
                    removeTrailingDot(lookahead.get(lastCandidateIndex), lastDotStart);
                    scheduleSentenceDot(lastCandidateIndex, lastDotStart, lastDotEnd);
                }

                lastCandidateIndex = currentIndex;
                lastDotStart = correctOffset(offset - 1);
                lastDotEnd = correctOffset(offset);
                crossedTransparentPunctuation = false;
                continue;
            }

            if (isSentencePunctuationToken(this)) {
                final AttributeSource punctuation = lookahead.get(currentIndex);
                final OffsetAttribute offsets = punctuation.getAttribute(OffsetAttribute.class);
                removeTrailingDot(lookahead.get(lastCandidateIndex), lastDotStart);
                if (offsets.startOffset() == lastDotEnd) {
                    mergeDotIntoSentencePunctuation(punctuation, lastDotStart);
                }
                else {
                    scheduleSentenceDot(lastCandidateIndex, lastDotStart, lastDotEnd);
                }
                return;
            }

            if (startsSentence(this)) {
                removeTrailingDot(lookahead.get(lastCandidateIndex), lastDotStart);
                scheduleSentenceDot(lastCandidateIndex, lastDotStart, lastDotEnd);
            }
            return;
        }

        removeTrailingDot(lookahead.get(lastCandidateIndex), lastDotStart);
        scheduleSentenceDot(lastCandidateIndex, lastDotStart, lastDotEnd);
    }

    /**
     * Schedule a detached dot after its queued lexical token.
     *
     * @param candidateIndex logical queue index of the token losing its dot
     * @param dotStart corrected start offset of the dot
     * @param dotEnd corrected end offset of the dot
     */
    private void scheduleSentenceDot(
        final int candidateIndex,
        final int dotStart,
        final int dotEnd
    ) {
        pendingSentenceDots.addLast(new SentenceDot(candidateIndex + 1, dotStart, dotEnd));
    }

    /**
     * Test whether a token provides sentence-start evidence for a pending dot.
     *
     * @param source token state to inspect
     * @return {@code true} for a number or a token beginning with uppercase/titlecase
     */
    private static boolean startsSentence(final AttributeSource source)
    {
        final CharTermAttribute term = source.getAttribute(CharTermAttribute.class);
        final int length = term.length();
        if (length == 0) return false;

        final char first = term.charAt(0);
        if (Char.isDigit(first)) return true;
        if (first == '-' && length > 1 && Char.isDigit(term.charAt(1))) return true;
        return Character.isUpperCase(first) || Character.isTitleCase(first);
    }

    /**
     * Reset internal state for a new input.
     *
     * @throws IOException if the input stream cannot be reset
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
        pendingCharEndOffset = -1;
        pendingSentenceDots.clear();
        emittedLookaheadTokens = 0;
        if (lookahead != null) lookahead.clear();
        buffer.reset();
    }

    /**
     * Emit already consumed punctuation as a token. Its source span may be one literal character
     * or a complete entity such as {@code &amp;quot;}. Sentence punctuation merges with a following
     * {@code .?!…} run in the buffer.
     *
     * @return {@code true}
     * @throws IOException if the input cannot be read
     */
    private boolean emitPendingPunct() throws IOException
    {
        // pendingChar already consumed earlier; current (offset, bufferIndex) point after it.
        final int pOff = pendingCharOffset;
        final int pEnd = pendingCharEndOffset;
        final char pc = (char) pendingChar;
        pendingCharOffset = -1;
        pendingCharEndOffset = -1;
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
            offsetAtt.setOffset(correctOffset(pOff), correctOffset(pEnd));
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
        offsetAtt.setOffset(correctOffset(pOff), correctOffset(pEnd));
        return true;
    }

    /**
     * Decode one of the five predefined XML entities stored in a term buffer.
     *
     * @param buf token buffer containing the entity source spelling
     * @param from index of {@code '&'}
     * @param to index immediately after {@code ';'}
     * @return decoded character, or {@code 0} when the entity is unknown
     */
    private static char decodeXmlEntity(final char[] buf, final int from, final int to)
    {
        final int length = to - from;
        if (length == 4) {
            if (buf[from + 1] == 'g' && buf[from + 2] == 't') return '>';
            if (buf[from + 1] == 'l' && buf[from + 2] == 't') return '<';
            return 0;
        }
        if (length == 5) {
            if (buf[from + 1] == 'a' && buf[from + 2] == 'm' && buf[from + 3] == 'p') return '&';
            return 0;
        }
        if (length == 6) {
            if (buf[from + 1] == 'a' && buf[from + 2] == 'p'
                    && buf[from + 3] == 'o' && buf[from + 4] == 's') return '\'';
            if (buf[from + 1] == 'q' && buf[from + 2] == 'u'
                    && buf[from + 3] == 'o' && buf[from + 4] == 't') return '"';
        }
        return 0;
    }

    /**
     * Normalize a character accepted in a word-like token.
     *
     * @param c source or entity-decoded character
     * @return normalized token character
     */
    private static char normalizeTokenChar(final char c)
    {
        if (c == '\u2019' || c == '\u2018' || c == '\u02BC') return '\'';
        if (c == '\u2010' || c == '\u2011' || c == '\u00AD') return '-';
        return c;
    }

    /**
     * Test for clause punctuation, kept as standalone single-char tokens.
     *
     * @param c character to test
     * @return {@code true} for clause punctuation
     */
    private static boolean isClausePunct(final char c) {
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
     *
     * @param c character to test
     * @return {@code true} for sentence punctuation
     */
    private static boolean isSentencePunct(final char c) {
        return c == '.' || c == '…' || c == '?' || c == '!';
    }

    /**
     * Test whether a dotted token is a brevidot whose final dot must remain attached.
     * Recognized forms are configured entries, single-letter initials, dotted short-segment
     * abbreviations, and hyphenated initial chains.
     *
     * @param buf token buffer ending with a dot
     * @param len token length
     * @return {@code true} when the final dot must remain attached
     */
    private boolean isBrevidot(final char[] buf, final int len)
    {
        if (len < 2 || buf[len - 1] != '.') return false;

        final int letter = len - 2;
        if (Char.isLetter(buf[letter]) && (letter == 0 || buf[letter - 1] == '\'')) {
            return true;
        }

        int from = 0;
        for (int i = len - 2; i > 0; i--) {
            if (buf[i - 1] == '\'' || buf[i - 1] == '’') {
                from = i;
                break;
            }
        }

        if (looksLikeDottedAbbrev(buf, from, len)) return true;
        if (looksLikeHyphenatedInitials(buf, from, len)) return true;
        return brevidots.contains(buf, from, len - from - 1);
    }

    /**
     * Test for a dotted abbreviation made of short letter-only segments.
     *
     * @param buf token buffer
     * @param from first character to inspect
     * @param len token length
     * @return {@code true} for forms such as {@code U.S.A.}, {@code e.g.}, or {@code Ph.D.}
     */
    private static boolean looksLikeDottedAbbrev(
        final char[] buf,
        final int from,
        final int len
    ) {
        if (len - from < 4 || buf[len - 1] != '.') return false;
        int segmentLength = 0;
        boolean hasInternalDot = false;

        for (int i = from; i < len - 1; i++) {
            final char c = buf[i];
            if (c == '.') {
                if (segmentLength == 0 || segmentLength > 3) return false;
                hasInternalDot = true;
                segmentLength = 0;
                continue;
            }
            if (!Char.isLetter(c)) return false;
            segmentLength++;
            if (segmentLength > 3) return false;
        }
        return hasInternalDot && segmentLength > 0 && segmentLength <= 3;
    }

    /**
     * Test for a hyphenated chain of one-letter initials.
     *
     * @param buf token buffer
     * @param from first character to inspect
     * @param len token length
     * @return {@code true} for forms such as {@code J.-J.} or {@code J.-C.}
     */
    private static boolean looksLikeHyphenatedInitials(
        final char[] buf,
        final int from,
        final int len
    ) {
        int groups = 0;
        int index = from;

        while (index < len) {
            if (index + 1 >= len || !Char.isLetter(buf[index]) || buf[index + 1] != '.') {
                return false;
            }
            groups++;
            index += 2;
            if (index == len) return groups >= 2;
            if (buf[index] != '-') return false;
            index++;
        }
        return false;
    }

}