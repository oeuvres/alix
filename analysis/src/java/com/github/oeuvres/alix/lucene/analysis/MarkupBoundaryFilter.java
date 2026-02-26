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
 * Converts selected closing XML/HTML tags into synthetic structural boundary tokens
 * and drops all other markup tokens.
 *
 * <h2>Input contract</h2>
 * <ul>
 *   <li>The upstream tokenizer emits tags as tokens whose {@link CharTermAttribute} contains the literal tag
 *       (including {@code <} and {@code >}).</li>
 *   <li>Tag tokens are identified by {@link PosAttribute#getPos()} == {@code XML.code}.</li>
 *   <li>Non-tag tokens (visible text, punctuation, etc.) carry their usual offsets/positions.</li>
 * </ul>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li><b>All markup tokens are dropped</b>, except those mapped to boundaries.</li>
 *   <li>On configured <b>closing tags</b> (e.g. {@code </p>}), emit a synthetic boundary token:
 *     <ul>
 *       <li>paragraph boundary: term {@value #PARA_MARK}, {@code PosAttribute = PUNCTpara.code}</li>
 *       <li>section boundary: term {@value #SECTION_MARK}, {@code PosAttribute = PUNCTsection.code}</li>
 *     </ul>
 *   </li>
 *   <li><b>Only closing tags</b> are considered for boundaries (no mapping on open/self-closing tags).</li>
 *   <li><b>Local-name matching</b>: prefixes are ignored (e.g. {@code </tei:p>} matches {@code p}).</li>
 *   <li><b>Coalescing</b>: consecutive boundary requests before any visible token are merged into one;
 *       section wins over paragraph.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>
 * The constructor accepts two {@code |}-separated lists of element names:
 * </p>
 * <ul>
 *   <li>{@code paraElements}: names whose closing tag triggers a paragraph boundary</li>
 *   <li>{@code sectionElements}: names whose closing tag triggers a section boundary</li>
 * </ul>
 *
 * <pre>{@code
 * // Map </p>, </li>, </td>, </h1>.. to ¶, and </article>, </section> to §
 * TokenStream ts = new MarkupFilter(tokenizer, "p|li|td|h1|h2|h3", "article|section");
 * }</pre>
 *
 * <h2>Offsets and positions</h2>
 * <p>
 * Boundary tokens reuse the attribute state of the triggering close-tag token, and overwrite only:
 * {@link CharTermAttribute} and {@link PosAttribute}. This preserves offsets/position-increment coherence
 * according to what the tokenizer provided for the markup token.
 * </p>
 */
public final class MarkupBoundaryFilter extends TokenFilter
{
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
     * Stores a POS code ({@code PUNCTpara.code} or {@code PUNCTsection.code}), or 0 for none.
     */
    private int pendingBoundaryPos = 0;

    /**
     * State captured from the triggering close-tag token so the synthetic boundary keeps coherent
     * offsets/positions from the source markup token.
     */
    private State pendingBoundaryState = null;

    /**
     * Buffered visible token that was read while a pending boundary still had to be emitted first.
     */
    private State deferredVisibleToken = null;
    
 // Defaults as readable strings (local-names, case-sensitive, alphabetic order)
    public static final String DEFAULT_PARA_ELEMENTS =
            "ab|address|blockquote|cell|dd|div|dt|h1|h2|h3|h4|h5|h6|head|item|l|label|li|p|pre|row|td|th|tr";

    public static final String DEFAULT_SECTION_ELEMENTS =
            "article|back|body|chapter|div0|div1|div2|div3|div4|div5|div6|div7|front|group|main|section|text";

    /** Default policy constructor: uses {@link #DEFAULT_PARA_ELEMENTS} and {@link #DEFAULT_SECTION_ELEMENTS}. */
    public MarkupBoundaryFilter(final TokenStream input) {
        this(input, DEFAULT_PARA_ELEMENTS, DEFAULT_SECTION_ELEMENTS);
    }

    /**
     * @param input token stream (typically tokenizer output)
     * @param paraElements {@code |}-separated local-names mapped from close-tags to paragraph boundary (e.g. {@code "p|li|td|h1"})
     * @param sectionElements {@code |}-separated local-names mapped from close-tags to section boundary (e.g. {@code "article|section"})
     */
    public MarkupBoundaryFilter(final TokenStream input, final String paraElements, final String sectionElements)
    {
        super(Objects.requireNonNull(input, "input"));
        this.paraOnClose = compileTagSet(paraElements);
        this.sectionOnClose = compileTagSet(sectionElements);
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        // 0) Drain deferred visible token first (if we emitted a boundary before it).
        if (deferredVisibleToken != null) {
            restoreState(deferredVisibleToken);
            deferredVisibleToken = null;
            return true;
        }

        // 1) If a structural boundary is pending, emit it now.
        if (pendingBoundaryPos != 0) {
            emitPendingBoundary();
            return true;
        }

        while (input.incrementToken()) {

            final int pos = posAtt.getPos();
            final boolean isXml = (pos == XML.code);

            // Visible token: emit unless we must emit a pending boundary first.
            if (!isXml) {
                if (pendingBoundaryPos != 0) {
                    deferredVisibleToken = captureState();
                    emitPendingBoundary();
                    return true;
                }
                return true;
            }

            // Tag token: classify and (maybe) map to boundary; otherwise drop.
            final char[] buf = termAtt.buffer();
            final int len = termAtt.length();

            final TagKind kind = classifyTag(buf, len);
            if (kind != TagKind.CLOSE) {
                // Drop OPEN, DECL/COMMENT/PI, INVALID
                continue;
            }

            final long span = readLocalTagNameSpan(buf, len, /*from*/2); // after "</"
            final int start = (int)(span >>> 32);
            final int end = (int)span;
            if (end <= start) continue;

            final int nameLen = end - start;

            // Section boundary wins over paragraph if both configured.
            if (sectionOnClose.contains(buf, start, nameLen)) {
                requestBoundary(PUNCTsection.code);
                continue;
            }
            if (paraOnClose.contains(buf, start, nameLen)) {
                requestBoundary(PUNCTpara.code);
                continue;
            }

            // Default: drop tag token
        }

        // EOF: still emit a pending boundary if one remains.
        if (pendingBoundaryPos != 0) {
            emitPendingBoundary();
            return true;
        }

        return false;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
    }

    @Override
    public void end() throws IOException
    {
        super.end();
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
    }

    // -----------------------------------------------------------------------
    // Public helper (requested): compile tag-name lists
    // -----------------------------------------------------------------------

    /**
     * Compile a {@code |}-separated list of tag local-names into a case-insensitive {@link CharArraySet}.
     * Empty/null input yields an empty set.
     *
     * <p>Accepted separators: {@code |} plus optional surrounding whitespace.</p>
     */
    public static CharArraySet compileTagSet(final String names)
    {
        final CharArraySet set = new CharArraySet(16, true);
        if (names == null) return set;

        int i = 0;
        final int n = names.length();
        while (i < n) {
            // skip spaces and separators
            while (i < n) {
                final char c = names.charAt(i);
                if (c == '|' || isWs(c)) { i++; continue; }
                break;
            }
            if (i >= n) break;

            final int start = i;
            while (i < n) {
                final char c = names.charAt(i);
                if (c == '|') break;
                i++;
            }
            int end = i;
            // trim right
            while (end > start && isWs(names.charAt(end - 1))) end--;

            if (end > start) {
                // store local-name only (strip any prefix the user might include)
                final int p = names.lastIndexOf(':', end - 1);
                final int ls = (p >= start) ? (p + 1) : start;
                if (end > ls) set.add(names.substring(ls, end));
            }
        }
        return set;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // -----------------------------------------------------------------------
    // Boundary handling (unchanged semantics)
    // -----------------------------------------------------------------------

    /**
     * Registers a structural boundary to emit later.
     * Coalesces consecutive boundaries; section wins over paragraph.
     */
    private void requestBoundary(final int posCode)
    {
        if (posCode != PUNCTpara.code && posCode != PUNCTsection.code) return;

        if (pendingBoundaryPos == 0) {
            pendingBoundaryPos = posCode;
            pendingBoundaryState = captureState();
            return;
        }

        // Coalesce: keep strongest boundary (section > paragraph).
        if (pendingBoundaryPos == PUNCTpara.code && posCode == PUNCTsection.code) {
            pendingBoundaryPos = posCode;
            pendingBoundaryState = captureState();
        }
    }

    /**
     * Emits the currently pending structural boundary by restoring the state of the triggering
     * tag token and overwriting its term/POS with a synthetic boundary marker.
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

    // -----------------------------------------------------------------------
    // Tag parsing helpers (allocation-free)
    // -----------------------------------------------------------------------

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
     * Reads local-name span from a tag token.
     * @param from index right after '&lt;' (1) or '&lt;/' (2)
     * @return packed long: (start&lt;&lt;32) | end, end exclusive; or (0,0) on failure.
     */
    private static long readLocalTagNameSpan(final char[] tag, final int n, final int from)
    {
        int i = from;
        while (i < n && isHtmlSpace(tag[i])) i++;
        if (i >= n) return 0L;

        final int nameStart = i;
        while (i < n) {
            final char ch = tag[i];
            if (ch == '>' || ch == '/' || isHtmlSpace(ch)) break;
            i++;
        }
        final int nameEnd = i;
        if (nameEnd <= nameStart) return 0L;

        // local-name after last ':'
        int localStart = nameStart;
        for (int k = nameStart; k < nameEnd; k++) {
            if (tag[k] == ':') localStart = k + 1;
        }
        return (((long)localStart) << 32) | (nameEnd & 0xFFFFFFFFL);
    }

    private static boolean isHtmlSpace(final char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}