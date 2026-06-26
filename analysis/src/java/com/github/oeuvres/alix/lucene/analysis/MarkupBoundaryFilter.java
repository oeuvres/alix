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

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Lucene {@link TokenFilter} that converts selected closing XML/HTML tags into synthetic structural
 * boundary tokens and drops every other markup token.
 *
 * <h2>Input contract</h2>
 * <ul>
 *   <li>The upstream tokenizer emits each tag as a token whose {@link CharTermAttribute} holds the
 *       literal tag, delimiters included ({@code <} … {@code >}).</li>
 *   <li>Tag tokens are recognised by {@link PosAttribute#getPos()} {@code == XML.code}.</li>
 *   <li>Every other token (text, punctuation, …) is treated as visible and passes through unchanged
 *       with the offsets/positions the tokenizer assigned.</li>
 * </ul>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>All markup tokens are dropped, except closing tags mapped to a boundary.</li>
 *   <li>A configured closing tag emits a synthetic boundary token:
 *     <ul>
 *       <li>paragraph: term {@value #PARA_MARK}, {@code PosAttribute = PUNCTpara.code};</li>
 *       <li>section: term {@value #SECTION_MARK}, {@code PosAttribute = PUNCTsection.code}.</li>
 *     </ul>
 *   </li>
 *   <li>Only closing tags trigger boundaries; open and self-closing tags never do.</li>
 *   <li>Matching is on the local-name only — prefixes are ignored on both sides
 *       (e.g. {@code </tei:p>} matches the configured name {@code p}) — and is case-insensitive.</li>
 *   <li>Consecutive boundary requests with no intervening visible token are coalesced into one;
 *       a section boundary wins over a paragraph boundary.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Each constructor list is {@code |}-separated local-names:</p>
 * <ul>
 *   <li>{@code paraElements}: closing tags mapped to a paragraph boundary;</li>
 *   <li>{@code sectionElements}: closing tags mapped to a section boundary.</li>
 * </ul>
 *
 * <pre>{@code
 * // Map </p>, </li>, </td>, </h1>… to ¶, and </article>, </section> to §
 * TokenStream ts = new MarkupBoundaryFilter(tokenizer, "p|li|td|h1|h2|h3", "article|section");
 * }</pre>
 *
 * <h2>Offsets and positions</h2>
 * <p>A boundary token reuses the captured state of the triggering closing tag and overwrites only its
 * {@link CharTermAttribute} and {@link PosAttribute}. It therefore inherits the offsets and the
 * position increment that the tokenizer assigned to that markup token. In particular, a boundary
 * occupies a token position — and thus blocks cross-boundary phrase/span matches — only if the
 * tokenizer gives tag tokens a non-zero position increment.</p>
 */
public final class MarkupBoundaryFilter extends TokenFilter
{
    /** Default {@code |}-separated local-names whose closing tag triggers a paragraph boundary. */
    public static final String DEFAULT_PARA_ELEMENTS =
            "ab|address|blockquote|cell|dd|div|dt|h1|h2|h3|h4|h5|h6|head|item|l|label|li|p|pre|row|td|th|tr";

    /** Default {@code |}-separated local-names whose closing tag triggers a section boundary. */
    public static final String DEFAULT_SECTION_ELEMENTS =
            "article|back|body|chapter|div0|div1|div2|div3|div4|div5|div6|div7|front|group|main|section|text";

    /** Synthetic term emitted for paragraph-like boundaries. */
    public static final String PARA_MARK = "¶";

    /** Synthetic term emitted for section-like boundaries. */
    public static final String SECTION_MARK = "§";

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    private final CharArraySet paraOnClose;
    private final CharArraySet sectionOnClose;

    /**
     * Pending structural boundary to emit before the next visible token (or at EOF).
     * Holds a POS code ({@code PUNCTpara.code} or {@code PUNCTsection.code}), or 0 for none.
     */
    private int pendingBoundaryPos = 0;

    /**
     * State captured from the triggering close-tag token, so the synthetic boundary keeps coherent
     * offsets/positions from the source markup token.
     */
    private State pendingBoundaryState = null;

    /**
     * Visible token read while a boundary still had to be emitted first; returned on the next call.
     */
    private State deferredVisibleToken = null;

    /** Default-policy constructor: uses {@link #DEFAULT_PARA_ELEMENTS} and {@link #DEFAULT_SECTION_ELEMENTS}. */
    public MarkupBoundaryFilter(final TokenStream input)
    {
        this(input, DEFAULT_PARA_ELEMENTS, DEFAULT_SECTION_ELEMENTS);
    }

    /**
     * @param input token stream (typically tokenizer output)
     * @param paraElements {@code |}-separated local-names mapped from close-tags to a paragraph boundary (e.g. {@code "p|li|td|h1"})
     * @param sectionElements {@code |}-separated local-names mapped from close-tags to a section boundary (e.g. {@code "article|section"})
     */
    public MarkupBoundaryFilter(final TokenStream input, final String paraElements, final String sectionElements)
    {
        super(Objects.requireNonNull(input, "input"));
        this.paraOnClose = compileTagSet(paraElements);
        this.sectionOnClose = compileTagSet(sectionElements);
    }

    /**
     * Compile a {@code |}-separated list of tag local-names into a case-insensitive {@link CharArraySet}.
     * A {@code null} or empty input yields an empty set. Any prefix in a name is stripped (only the part
     * after the last {@code ':'} is stored). Accepted separators: {@code |} plus optional surrounding
     * whitespace.
     */
    public static CharArraySet compileTagSet(final String names)
    {
        final CharArraySet set = new CharArraySet(16, true);
        if (names == null) return set;

        int i = 0;
        final int n = names.length();
        while (i < n) {
            while (i < n) {
                final char c = names.charAt(i);
                if (c == '|' || isSpace(c)) { i++; continue; }
                break;
            }
            if (i >= n) break;

            final int start = i;
            while (i < n && names.charAt(i) != '|') i++;
            int end = i;
            while (end > start && isSpace(names.charAt(end - 1))) end--;

            if (end > start) {
                final int p = names.lastIndexOf(':', end - 1);
                final int ls = (p >= start) ? (p + 1) : start;
                if (end > ls) set.add(names.substring(ls, end));
            }
        }
        return set;
    }

    /** Clears any pending boundary and buffered token at end of stream. */
    @Override
    public void end() throws IOException
    {
        super.end();
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
    }

    /**
     * Returns the next token. Markup tokens are dropped, except configured closing tags, which are
     * converted to a single coalesced {@value #PARA_MARK}/{@value #SECTION_MARK} boundary emitted just
     * before the next visible token (or at end of stream). When a boundary must precede a visible token,
     * that visible token is buffered and returned on the following call.
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        if (deferredVisibleToken != null) {
            restoreState(deferredVisibleToken);
            deferredVisibleToken = null;
            return true;
        }

        while (input.incrementToken()) {
            if (posAtt.getPos() != XML.code) {
                if (pendingBoundaryPos != 0) {
                    deferredVisibleToken = captureState();
                    emitPendingBoundary();
                }
                return true;
            }

            final int boundaryPos = closeTagBoundaryPos();
            if (boundaryPos != 0) {
                requestBoundary(boundaryPos);
            }
            // markup token consumed (boundary requested, or dropped); keep scanning
        }

        if (pendingBoundaryPos != 0) {
            emitPendingBoundary();
            return true;
        }
        return false;
    }

    /** Clears any pending boundary and buffered token so the filter can be reused. */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
    }

    /**
     * Classifies the current term as a markup token and, if it is a configured closing tag, returns the
     * matching boundary POS code. Section wins over paragraph when both sets would match.
     *
     * @return {@code PUNCTsection.code}, {@code PUNCTpara.code}, or 0 when the tag maps to no boundary.
     */
    private int closeTagBoundaryPos()
    {
        final char[] buf = termAtt.buffer();
        final int len = termAtt.length();

        if (classifyTag(buf, len) != TagKind.CLOSE) return 0;

        final long span = readLocalTagNameSpan(buf, len, /* from */ 2); // after "</"
        final int start = (int) (span >>> 32);
        final int end = (int) span;
        if (end <= start) return 0;

        final int nameLen = end - start;
        if (sectionOnClose.contains(buf, start, nameLen)) return PUNCTsection.code;
        if (paraOnClose.contains(buf, start, nameLen)) return PUNCTpara.code;
        return 0;
    }

    /**
     * Registers a structural boundary to emit later. Consecutive requests are coalesced;
     * a section boundary overrides a pending paragraph boundary.
     */
    private void requestBoundary(final int posCode)
    {
        if (posCode != PUNCTpara.code && posCode != PUNCTsection.code) return;

        if (pendingBoundaryPos == 0) {
            pendingBoundaryPos = posCode;
            pendingBoundaryState = captureState();
            return;
        }

        if (pendingBoundaryPos == PUNCTpara.code && posCode == PUNCTsection.code) {
            pendingBoundaryPos = posCode;
            pendingBoundaryState = captureState();
        }
    }

    /**
     * Emits the pending structural boundary by restoring the triggering tag's captured state and
     * overwriting its term/POS with the synthetic boundary marker. Resets the pending state.
     */
    private void emitPendingBoundary()
    {
        restoreState(pendingBoundaryState);
        pendingBoundaryState = null;

        if (pendingBoundaryPos == PUNCTsection.code) {
            posAtt.setPos(PUNCTsection.code);
            termAtt.setEmpty().append(SECTION_MARK);
        }
        else {
            posAtt.setPos(PUNCTpara.code);
            termAtt.setEmpty().append(PARA_MARK);
        }

        pendingBoundaryPos = 0;
    }

    private enum TagKind { OPEN, CLOSE, DECL_OR_COMMENT, INVALID }

    private static TagKind classifyTag(final char[] buf, final int len)
    {
        if (len < 3) return TagKind.INVALID;
        if (buf[0] != '<') return TagKind.INVALID;

        final char c1 = buf[1];
        if (c1 == '/') return TagKind.CLOSE;
        if (c1 == '!' || c1 == '?') return TagKind.DECL_OR_COMMENT;
        return TagKind.OPEN;
    }

    /**
     * Reads the local-name span from a tag token.
     *
     * @param tag tag buffer
     * @param n tag length
     * @param from index right after {@code <} (1) or {@code </} (2)
     * @return packed long {@code (start << 32) | end} (end exclusive), or {@code 0L} on failure.
     */
    private static long readLocalTagNameSpan(final char[] tag, final int n, final int from)
    {
        int i = from;
        while (i < n && isSpace(tag[i])) i++;
        if (i >= n) return 0L;

        final int nameStart = i;
        while (i < n) {
            final char ch = tag[i];
            if (ch == '>' || ch == '/' || isSpace(ch)) break;
            i++;
        }
        final int nameEnd = i;
        if (nameEnd <= nameStart) return 0L;

        int localStart = nameStart;
        for (int k = nameStart; k < nameEnd; k++) {
            if (tag[k] == ':') localStart = k + 1;
        }
        return (((long) localStart) << 32) | (nameEnd & 0xFFFFFFFFL);
    }

    private static boolean isSpace(final char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
