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
import java.util.Arrays;

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
 * Tokenizer for Latin-script languages and XML-like tags. Keeps tags as tokens (flags XML),
 * clause punctuation as tokens (flags PUNCTclause), sentence punctuation runs as tokens
 * (flags PUNCTsent), numbers as tokens (flags DIGIT).
 *
 * <p>An attached trailing dot is always retained by the raw character pass. Configured or
 * structurally recognized brevidots keep it unconditionally. Other dotted tokens are buffered
 * until a following token resolves the dot:</p>
 * <ul>
 *   <li>a lowercase word, comma, semicolon, or colon keeps all pending dots attached;</li>
 *   <li>an uppercase/titlecase word, a number, sentence punctuation, or end of input detaches
 *       the rightmost pending dot and emits it as sentence punctuation;</li>
 *   <li>after a dot is detached, its bare token becomes evidence for the preceding candidate;
 *       resolution therefore cascades right-to-left while that token begins with uppercase,
 *       titlecase, or a digit;</li>
 *   <li>a configured block tag (opening or closing, for example {@code </p>}) marks a
 *       boundary the sentence cannot cross, and detaches pending dots exactly like end of
 *       input;</li>
 *   <li>other XML tokens, quotes, parentheses, en dashes, and em dashes are transparent to
 *       the decision and remain in their original output order;</li>
 *   <li>another unresolved dotted token extends the pending sequence.</li>
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
 *
 * <p>Implementation: {@link #readToken()} is a dispatcher over the first significant character;
 * one small method per token kind reads the rest through the {@link #peek()}/{@link #skip()}
 * cursor. Trailing-dot resolution buffers whole token states in a {@link TokenStateQueue};
 * detached dots are spliced into that same queue by {@link #insertDetachedDots(int, boolean)},
 * so emission is a plain {@code removeFirst}.</p>
 */
public class MarkupTokenizer extends Tokenizer
{
    /** Max size of a word-like token (not tags). */
    private static final int TOKEN_MAX_SIZE = 256;

    /** IO buffer size (chars). Tune for your workload; 2 MiB per instance is usually wasteful. */
    private static final int IO_BUFFER_SIZE = 32 * 1024;

    /** Initial number of token states reserved for trailing-dot lookahead. */
    private static final int LOOKAHEAD_INITIAL_CAPACITY = 8;

    /**
     * Hard guard against malformed input with no resolving token, counted in lookahead
     * tokens. The queue itself is sized at twice this value because each buffered token
     * may additionally receive one detached sentence dot.
     */
    private static final int LOOKAHEAD_MAX_CAPACITY = 4096;

    /**
     * Default block-level element local-names, comma-separated. A tag with one of these
     * names ends any sentence pending a dot decision. Callers may extend the default, for
     * example {@code MarkupTokenizer.BLOCK_TAGS + ", head, item"} for TEI sources.
     */
    public static final String BLOCK_TAGS =
        "aside, blockquote, div, figcaption, h1, h2, h3, h4, h5, h6, li, p, section, td, th";

    /** Configured brevidots, stored with their final dot, for example {@code "etc."}. */
    private final CharArraySet brevidots;

    /** Local-names of block tags, compiled case-insensitive. */
    private final CharArraySet blockTags;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    private final CharacterBuffer buffer = CharacterUtils.newCharacterBuffer(IO_BUFFER_SIZE);

    /** Read position in {@link #buffer}. */
    private int bufferIndex;

    /** Number of valid chars in {@link #buffer}. */
    private int bufferLength;

    /** Source offset (UTF-16 code units) of the next char to read. */
    private int offset;

    /** Punctuation already consumed from the source ({@code -1} when none). */
    private int pendingChar = -1;

    /** Raw source start offset of {@link #pendingChar}; may span an entity such as {@code &quot;}. */
    private int pendingStart;

    /** Raw source end offset of {@link #pendingChar}. */
    private int pendingEnd;

    /** Buffered token states during trailing-dot resolution, then replayed in order. */
    private TokenStateQueue lookahead;

    /** Queue indices of unresolved dotted tokens in the current lookahead sequence. */
    private int[] candidateIndices = new int[LOOKAHEAD_INITIAL_CAPACITY];

    /** Corrected start offsets of unresolved final dots. */
    private int[] candidateDotStarts = new int[LOOKAHEAD_INITIAL_CAPACITY];

    /** Corrected end offsets of unresolved final dots. */
    private int[] candidateDotEnds = new int[LOOKAHEAD_INITIAL_CAPACITY];

    /** Number of unresolved dotted tokens stored in the candidate arrays. */
    private int candidateCount;

    /**
     * Build a tokenizer with no configured abbreviation list and the default
     * {@link #BLOCK_TAGS}.
     */
    public MarkupTokenizer()
    {
        this(CharArraySet.EMPTY_SET, BLOCK_TAGS);
    }

    /**
     * Build a tokenizer with configured brevidots and the default {@link #BLOCK_TAGS}.
     * Brevidot entries include their final dot, for example {@code "Dr."}, {@code "etc."},
     * or {@code "Var."}.
     *
     * @param brevidots forms whose final dot always remains inside the token; null means none
     */
    public MarkupTokenizer(final CharArraySet brevidots)
    {
        this(brevidots, BLOCK_TAGS);
    }

    /**
     * Build a tokenizer with configured brevidots and block tags.
     *
     * <p>Position increment and position length are registered so that they exist in the
     * stream, but never set: {@code clearAttributes()} resets both to their default of 1,
     * which is the only value this tokenizer emits.</p>
     *
     * @param brevidots forms whose final dot always remains inside the token, spelled with
     *        that dot ({@code "etc."}, {@code "Stud."}); matched with the set's own case
     *        policy, so {@code Var.} and {@code var.} differ under a case-sensitive set;
     *        null means none
     * @param blockTags comma-separated element local-names ending any sentence pending a dot
     *        decision, matched case-insensitive on opening and closing tags; null or blank
     *        means no block boundary, the legacy behavior
     */
    public MarkupTokenizer(final CharArraySet brevidots, final String blockTags)
    {
        super();
        this.brevidots = (brevidots == null) ? CharArraySet.EMPTY_SET : brevidots;
        this.blockTags = compileBlockTags(blockTags);
        addAttribute(PositionIncrementAttribute.class);
        addAttribute(PositionLengthAttribute.class);
    }

    /**
     * Compile a comma-separated list of element local-names into a case-insensitive set.
     * Whitespace around names is ignored and a namespace prefix up to {@code ':'} is
     * stripped, so {@code "tei:p"} and {@code " p "} both compile to {@code p}.
     *
     * @param names comma-separated local-names; null or blank yields an empty set
     * @return case-insensitive set of local-names
     */
    private static CharArraySet compileBlockTags(final String names)
    {
        final CharArraySet set = new CharArraySet(16, true);
        if (names == null) return set;
        for (String name : names.split(",")) {
            name = name.trim();
            final int colon = name.lastIndexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            if (!name.isEmpty()) set.add(name);
        }
        return set;
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
     * Detach pending dots from right to left, then splice them into the lookahead queue. The
     * rightmost candidate is always detached. Each detached candidate then becomes lexical
     * evidence for the candidate on its left; cascading continues only while that bare token
     * begins with uppercase, titlecase, or a digit.
     *
     * <p>When the resolver is a sentence-punctuation token immediately adjacent to the
     * rightmost detached dot, the dot is merged into that token instead of being inserted.</p>
     *
     * @param punctuation queued sentence-punctuation resolver; null for any other resolver
     */
    private void detachDots(final AttributeSource punctuation)
    {
        final int last = candidateCount - 1;
        int first = last;
        removeTrailingDot(lookahead.get(candidateIndices[first]), candidateDotStarts[first]);
        while (first > 0 && startsSentence(lookahead.get(candidateIndices[first]))) {
            first--;
            removeTrailingDot(lookahead.get(candidateIndices[first]), candidateDotStarts[first]);
        }

        boolean mergeLast = false;
        if (punctuation != null
                && punctuation.getAttribute(OffsetAttribute.class).startOffset() == candidateDotEnds[last]) {
            mergeDotIntoPunctuation(punctuation, candidateDotStarts[last]);
            mergeLast = true;
        }
        insertDetachedDots(first, mergeLast);
    }

    /**
     * Emit punctuation already consumed from the source, for example the dot stripped from the
     * end of a number or a decoded {@code &amp;quot;}. Sentence punctuation merges with an
     * immediately following {@code .?!…} run.
     *
     * @return {@code true}
     * @throws IOException if the input cannot be read
     */
    private boolean emitPendingChar() throws IOException
    {
        final char pc = (char) pendingChar;
        pendingChar = -1;
        termAtt.append(pc);
        if (isClausePunct(pc)) {
            posAtt.setPos(PUNCTclause.code);
            offsetAtt.setOffset(correctOffset(pendingStart), correctOffset(pendingEnd));
            return true;
        }
        return readSentencePunctRun(pendingStart);
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
        if (lookahead != null && !lookahead.isEmpty()) {
            clearAttributes();
            lookahead.removeFirst(this);
            return true;
        }
        if (!readToken()) {
            return false;
        }
        if (!isUnknownDotted()) {
            return true;
        }
        resolveDottedSequence();
        clearAttributes();
        lookahead.removeFirst(this);
        return true;
    }

    /**
     * Rebuild the lookahead queue in place, inserting a sentence-dot token after each detached
     * candidate. One full rotation of the queue preserves the original token order.
     *
     * @param first index in the candidate arrays of the leftmost detached candidate
     * @param mergeLast {@code true} when the rightmost dot was merged into an adjacent
     *        punctuation token and must not be inserted
     */
    private void insertDetachedDots(final int first, final boolean mergeLast)
    {
        final int last = candidateCount - 1;
        final int size = lookahead.size();
        int cand = first;
        for (int i = 0; i < size; i++) {
            lookahead.removeFirst(this);
            lookahead.addLast(this);
            if (cand > last || candidateIndices[cand] != i) {
                continue;
            }
            if (cand < last || !mergeLast) {
                clearAttributes();
                termAtt.append('.');
                posAtt.setPos(PUNCTsent.code);
                offsetAtt.setOffset(candidateDotStarts[cand], candidateDotEnds[cand]);
                lookahead.addLast(this);
            }
            cand++;
        }
    }

    /**
     * Test whether the current token is a tag whose local element name is a configured
     * block tag. The name is read after {@code '<'} and an optional {@code '/'}, skipping
     * whitespace and a namespace prefix up to {@code ':'}, ending at whitespace,
     * {@code '/'}, or {@code '>'}. Opening and closing forms both match. Processing
     * instructions, comments, and doctypes extract names such as {@code "?xml"} that no
     * configured set contains, so they need no special case.
     *
     * @return {@code true} when the tag ends any sentence pending a dot decision
     */
    private boolean isBlockTag()
    {
        final char[] buf = termAtt.buffer();
        final int len = termAtt.length();
        int from = 1;
        if (from < len && buf[from] == '/') from++;
        while (from < len && Char.isSpace(buf[from])) from++;
        int to = from;
        while (to < len && buf[to] != '>' && buf[to] != '/' && !Char.isSpace(buf[to])) {
            if (buf[to] == ':') from = to + 1;
            to++;
        }
        return to > from && blockTags.contains(buf, from, to - from);
    }

    /**
     * Test whether a dotted token is a brevidot whose final dot must remain attached.
     * Recognized forms are configured entries, single-letter initials, dotted short-segment
     * abbreviations, and hyphenated initial chains. An elision prefix such as {@code l'} is
     * skipped; the term buffer is already normalized, so only the ASCII apostrophe occurs.
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
            if (buf[i - 1] == '\'') {
                from = i;
                break;
            }
        }

        if (looksLikeDottedAbbrev(buf, from, len)) return true;
        if (looksLikeHyphenatedInitials(buf, from, len)) return true;
        return brevidots.contains(buf, from, len - from);
    }

    /**
     * Test for clause punctuation, kept as standalone single-char tokens.
     *
     * @param c character to test
     * @return {@code true} for clause punctuation
     */
    private static boolean isClausePunct(final char c)
    {
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
    private static boolean isSentencePunct(final char c)
    {
        return c == '.' || c == '…' || c == '?' || c == '!';
    }

    /**
     * Test whether the current token is an unresolved word with an immediately attached
     * final dot.
     *
     * @return {@code true} when contextual lookahead is required
     */
    private boolean isUnknownDotted()
    {
        final int length = termAtt.length();
        return length > 1
            && termAtt.charAt(length - 1) == '.'
            && Char.isLetter(termAtt.charAt(length - 2))
            && !isBrevidot(termAtt.buffer(), length);
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

    /**
     * Prefix an adjacent sentence-punctuation token with the dot detached from the preceding word.
     *
     * @param punctuation queued sentence-punctuation state
     * @param dotStart corrected start offset of the detached dot
     */
    private static void mergeDotIntoPunctuation(
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
     * Normalize a character accepted in a word-like token. Note that the soft hyphen
     * (U+00AD) is a token character and becomes a plain hyphen.
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
     * Return the char at the read cursor without consuming it, refilling the IO buffer
     * when exhausted.
     *
     * @return current char, or {@code -1} at end of input
     * @throws IOException if the input cannot be read
     */
    private int peek() throws IOException
    {
        if (bufferIndex >= bufferLength) {
            CharacterUtils.fill(buffer, input);
            bufferLength = buffer.getLength();
            bufferIndex = 0;
            if (bufferLength == 0) return -1;
        }
        return buffer.getBuffer()[bufferIndex];
    }

    /**
     * Read a number: digits with at most one {@code '.'} or {@code ','} between digit runs.
     * A separator left dangling at the end of the number is stripped and re-emitted as
     * punctuation by the next call, except at end of input where it stays attached.
     *
     * <p>Entered with the cursor on a digit, either from {@link #readToken()} or from
     * {@link #readWord()} when the term is a single minus sign.</p>
     *
     * @param start source start offset of the token
     * @return {@code true}
     * @throws IOException if the input cannot be read
     */
    private boolean readNumber(final int start) throws IOException
    {
        posAtt.setPos(DIGIT.code);
        int c;
        while ((c = peek()) >= 0) {
            final char ch = (char) c;
            if (!Char.isDigit(ch)) {
                final char last = termAtt.charAt(termAtt.length() - 1);
                if ((ch != '.' && ch != ',') || last == '.' || last == ',') break;
            }
            termAtt.append(ch);
            skip();
        }

        // Historical quirk kept for output stability: before a tag ('<'), as at end of
        // input, a dangling separator stays inside the number ("p. 12.</p>" gives "12.").
        final int length = termAtt.length();
        final char last = termAtt.charAt(length - 1);
        if (c >= 0 && c != '<' && (last == '.' || last == ',')) {
            termAtt.setLength(length - 1);
            pendingChar = last;
            pendingStart = offset - 1;
            pendingEnd = offset;
            offsetAtt.setOffset(correctOffset(start), correctOffset(offset - 1));
            return true;
        }
        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        return true;
    }

    /**
     * Read a run of sentence punctuation ({@code .?!…}) from the cursor, appended to whatever
     * the caller already placed in the term (nothing, or one pending char).
     *
     * @param start source start offset of the token
     * @return {@code true}
     * @throws IOException if the input cannot be read
     */
    private boolean readSentencePunctRun(final int start) throws IOException
    {
        posAtt.setPos(PUNCTsent.code);
        int c;
        while ((c = peek()) >= 0 && isSentencePunct((char) c)) {
            termAtt.append((char) c);
            skip();
        }
        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        return true;
    }

    /**
     * Read an XML-like tag from {@code '<'} through {@code '>'} inclusive. A tag truncated by
     * end of input is emitted as-is, without the XML flag.
     *
     * @return {@code true}
     * @throws IOException if the input cannot be read
     */
    private boolean readTag() throws IOException
    {
        final int start = offset;
        int c;
        while ((c = peek()) >= 0) {
            termAtt.append((char) c);
            skip();
            if (c == '>') {
                posAtt.setPos(XML.code);
                break;
            }
        }
        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        return true;
    }

    /**
     * Read one raw token from the character stream, dispatching on the first significant
     * character. Attached final dots remain in word tokens; contextual resolution is performed
     * by {@link #incrementToken()}.
     *
     * @return {@code true} if a token was read
     * @throws IOException if the input cannot be read
     */
    private boolean readToken() throws IOException
    {
        clearAttributes();
        if (pendingChar >= 0) {
            return emitPendingChar();
        }
        int c;
        while ((c = peek()) >= 0) {
            final char ch = (char) c;
            if (ch == '<') {
                return readTag();
            }
            if (isClausePunct(ch)) {
                final int start = offset;
                termAtt.append(ch);
                skip();
                posAtt.setPos(PUNCTclause.code);
                offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
                return true;
            }
            if (isSentencePunct(ch)) {
                return readSentencePunctRun(offset);
            }
            if (Char.isDigit(ch)) {
                return readNumber(offset);
            }
            if (Char.isToken(ch)) {
                if (readWord()) return true;
                continue; // an entity decoded to a delimiter and the term evaporated
            }
            skip();
        }
        return false;
    }

    /**
     * Read a word-like token: letters, digits, apostrophes, hyphens, decoded XML entities,
     * and internal or trailing dots after letters. Hands over to {@link #readNumber(int)}
     * when the term is a single literal minus sign followed by a digit.
     *
     * @return {@code true} if a token was produced, {@code false} when a decoded entity acted
     *         as a delimiter before any term content accumulated
     * @throws IOException if the input cannot be read
     */
    private boolean readWord() throws IOException
    {
        int start = offset;
        int amp = -1;         // term index of a possible XML entity
        char lastRaw = 0;     // last consumed source or entity-decoded char
        boolean trailingDot = false;
        int c;

        while ((c = peek()) >= 0) {
            final char ch = (char) c;

            // A following letter continues an internal dotted form. A recognized brevidot may
            // also continue with any token char, as the hyphen in "J.-J.".
            if (trailingDot) {
                if (!Char.isLetter(ch) && !isBrevidot(termAtt.buffer(), termAtt.length())) break;
                trailingDot = false;
            }

            // Decode the five predefined XML entities. Unknown entities remain verbatim.
            if (ch == ';' && amp >= 0 && termAtt.length() >= amp + 2) {
                termAtt.append(';');
                skip();
                final int spellingStart = amp;
                amp = -1;
                final int entityStart = offset - (termAtt.length() - spellingStart);
                final char decoded = decodeXmlEntity(termAtt.buffer(), spellingStart, termAtt.length());
                if (decoded == 0) {
                    lastRaw = ';';
                    continue;
                }
                termAtt.setLength(spellingStart);
                lastRaw = decoded;
                if (isClausePunct(decoded) || isSentencePunct(decoded)) {
                    pendingChar = decoded;
                    pendingStart = entityStart;
                    pendingEnd = offset;
                    if (termAtt.length() > 0) {
                        offsetAtt.setOffset(correctOffset(start), correctOffset(entityStart));
                        return true;
                    }
                    return emitPendingChar();
                }
                if (Char.isToken(decoded)) {
                    if (termAtt.length() == 0) start = entityStart;
                    termAtt.append(normalizeTokenChar(decoded));
                    continue;
                }
                // '<', '>' or any other decoded character acts as a plain delimiter.
                if (termAtt.length() > 0) {
                    offsetAtt.setOffset(correctOffset(start), correctOffset(entityStart));
                    return true;
                }
                return false;
            }

            // Dot after a letter: may be abbrev/internal dot. Append now; decide on next char.
            if (ch == '.' && termAtt.length() > 0 && Char.isLetter(termAtt.charAt(termAtt.length() - 1))) {
                termAtt.append('.');
                skip();
                lastRaw = '.';
                trailingDot = true;
                continue;
            }

            // "-42": a literal minus sign then a digit is a negative number.
            if (Char.isDigit(ch) && termAtt.length() == 1 && lastRaw == '-') {
                return readNumber(start);
            }

            // Clause punctuation, sentence punctuation, '<', whitespace: end of word.
            if (!Char.isToken(ch)) break;

            if (ch == '&') amp = termAtt.length();
            termAtt.append(normalizeTokenChar(ch));
            skip();
            lastRaw = ch;
            if (termAtt.length() >= TOKEN_MAX_SIZE) break; // cut overly long tokens
        }

        offsetAtt.setOffset(correctOffset(start), correctOffset(offset));
        return true;
    }

    /**
     * Remember an unresolved dotted token in the current lookahead sequence. The read cursor
     * sits just after the final dot of the token.
     *
     * @param queueIndex logical index of the token in {@link #lookahead}
     */
    private void rememberCandidate(final int queueIndex)
    {
        if (candidateCount == candidateIndices.length) {
            final int capacity = candidateCount * 2;
            candidateIndices = Arrays.copyOf(candidateIndices, capacity);
            candidateDotStarts = Arrays.copyOf(candidateDotStarts, capacity);
            candidateDotEnds = Arrays.copyOf(candidateDotEnds, capacity);
        }
        candidateIndices[candidateCount] = queueIndex;
        candidateDotStarts[candidateCount] = correctOffset(offset - 1);
        candidateDotEnds[candidateCount] = correctOffset(offset);
        candidateCount++;
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
     * Reset internal state for a new input.
     *
     * @throws IOException if the input stream cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        bufferIndex = 0;
        bufferLength = 0;
        offset = 0;
        pendingChar = -1;
        candidateCount = 0;
        if (lookahead != null) lookahead.clear();
        buffer.reset();
    }

    /**
     * Buffer an unresolved dotted-token sequence and read raw tokens until its dots can be
     * classified. The current token is the first unresolved dotted token. On return the
     * lookahead queue holds the whole sequence in output order, detached dots included.
     *
     * @throws IOException if the input cannot be read
     */
    private void resolveDottedSequence() throws IOException
    {
        if (lookahead == null) {
            lookahead = new TokenStateQueue(
                LOOKAHEAD_INITIAL_CAPACITY,
                2 * LOOKAHEAD_MAX_CAPACITY,
                OverflowPolicy.GROW,
                this
            );
        }

        candidateCount = 0;
        lookahead.addLast(this);
        rememberCandidate(0);

        while (readToken()) {
            lookahead.addLast(this);
            final int pos = posAtt.getPos();

            if (pos == XML.code) {
                if (isBlockTag()) {
                    detachDots(null); // the sentence cannot cross a block boundary
                    return;
                }
                continue;
            }
            if (pos == PUNCTclause.code) {
                final char c = termAtt.charAt(0);
                if (c == ',' || c == ';' || c == ':') return; // keeps all pending dots
                continue; // quotes, parentheses, dashes: transparent
            }
            if (isUnknownDotted()) {
                rememberCandidate(lookahead.size() - 1);
                continue;
            }
            if (pos == PUNCTsent.code) {
                detachDots(lookahead.peekLast());
                return;
            }
            if (startsSentence(this)) {
                detachDots(null);
            }
            return;
        }

        detachDots(null); // end of input
    }

    /**
     * Consume the char at the read cursor.
     */
    private void skip()
    {
        bufferIndex++;
        offset++;
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
}
