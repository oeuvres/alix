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
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.AttributeSource.State;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

/**
 * HTML/XML tag policy filter for a lexical analysis pipeline.
 *
 * <p>This filter is intended to run early in the analyzer chain (typically
 * immediately after the tokenizer) in order to prevent markup tokens from
 * contaminating downstream linguistic filters such as POS tagging and lemmatization.</p>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li><b>Raw tags are removed</b> from the lexical stream (e.g. {@code <i>}, {@code </span>}).</li>
 *   <li><b>Structural tags</b> are converted to synthetic boundary tokens:
 *     <ul>
 *       <li>paragraph-like boundary -> {@code ¶} with {@code PosAttribute = PUNCTpara}</li>
 *       <li>section-like boundary -> {@code §} with {@code PosAttribute = PUNCTsection}</li>
 *     </ul>
 *   </li>
 *   <li><b>Non-content elements</b> (e.g. {@code script}, {@code style}) suppress both tags and inner text
 *       until the matching closing tag (nesting-aware).</li>
 *   <li><b>Consecutive structural tags are coalesced</b> into a single boundary token
 *       (section boundary has precedence over paragraph boundary).</li>
 * </ul>
 *
 * <h2>Input assumptions</h2>
 * <ul>
 *   <li>Upstream tokenizer emits markup as tokens and marks them with
 *       {@code PosAttribute = XML}.</li>
 *   <li>Non-markup visible text tokens are emitted normally and carry their original
 *       offsets/position data.</li>
 * </ul>
 *
 * <h2>Placement in the analyzer chain</h2>
 * <p>Recommended placement:</p>
 * <pre>
 * Tokenizer -> MLFilter (this) -> clitic split -> sentence lowercasing -> POS tagger -> lemmatizer
 * </pre>
 *
 * <p>The POS tagger and lemmatizer may still defensively skip {@code XML} tokens, but this filter
 * should remove almost all raw markup before they are reached.</p>
 *
 * <h2>Notes</h2>
 * <ul>
 *   <li>This filter is HTML-oriented: tag-name matching is case-insensitive.</li>
 *   <li>Attributes on tags are ignored for linguistic processing.</li>
 *   <li>Comments / declarations / processing instructions are dropped.</li>
 * </ul>
 */
public final class MLFilter extends TokenFilter
{
    /** Synthetic term emitted for paragraph-like boundaries. */
    public static final String PARA_MARK = "¶";

    /** Synthetic term emitted for section-like boundaries. */
    public static final String SECTION_MARK = "§";

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /**
     * Depth of content suppression inside non-content containers (script/style/etc.).
     * When {@code > 0}, visible non-XML tokens are dropped until the matching close tag.
     */
    private int suppressDepth = 0;

    /**
     * Pending structural boundary to emit before the next visible token (or at EOF).
     * Stores a POS code ({@code PUNCTpara.code} or {@code PUNCTsection.code}), or 0 for none.
     */
    private int pendingBoundaryPos = 0;

    /**
     * State captured from the triggering tag token so the synthetic boundary keeps coherent
     * offsets/positions from the source markup token.
     */
    private State pendingBoundaryState = null;

    /**
     * Buffered visible token that was read while a pending boundary still had to be emitted first.
     */
    private State deferredVisibleToken = null;

    // -----------------------------------------------------------------------
    // Tag policy (HTML-oriented defaults)
    // -----------------------------------------------------------------------

    /**
     * Elements whose entire content must be suppressed (tags + inner text).
     * HTML + a few XML/TEI examples.
     */
    private static final CharArraySet SUPPRESS = new CharArraySet(24, true);
    static {
        SUPPRESS.add("script");
        SUPPRESS.add("style");
        SUPPRESS.add("template");
        SUPPRESS.add("noscript");
        SUPPRESS.add("head");

        // Existing project-specific / TEI-ish examples
        SUPPRESS.add("aside");
        SUPPRESS.add("nav");
        SUPPRESS.add("note");
        SUPPRESS.add("teiHeader");
    }

    /**
     * Tags that create paragraph-like boundaries when closed.
     * (For many HTML corpora, close-tags are safer for text flow semantics.)
     */
    private static final CharArraySet PARA_ON_CLOSE = new CharArraySet(32, true);
    static {
        PARA_ON_CLOSE.add("p");
        PARA_ON_CLOSE.add("div");
        PARA_ON_CLOSE.add("li");
        PARA_ON_CLOSE.add("tr");
        PARA_ON_CLOSE.add("td");
        PARA_ON_CLOSE.add("th");
        PARA_ON_CLOSE.add("blockquote");
        PARA_ON_CLOSE.add("cell");
        PARA_ON_CLOSE.add("item");
        PARA_ON_CLOSE.add("label");

        PARA_ON_CLOSE.add("h1");
        PARA_ON_CLOSE.add("h2");
        PARA_ON_CLOSE.add("h3");
        PARA_ON_CLOSE.add("h4");
        PARA_ON_CLOSE.add("h5");
        PARA_ON_CLOSE.add("h6");
    }

    /**
     * Tags that create section-like boundaries when closed.
     */
    private static final CharArraySet SECTION_ON_CLOSE = new CharArraySet(16, true);
    static {
        SECTION_ON_CLOSE.add("article");
        SECTION_ON_CLOSE.add("section");
        SECTION_ON_CLOSE.add("chapter");
    }

    /**
     * Empty or line-break tags that create paragraph-like boundaries when opened / self-closed.
     */
    private static final CharArraySet PARA_ON_OPEN = new CharArraySet(8, true);
    static {
        PARA_ON_OPEN.add("br");
        PARA_ON_OPEN.add("hr");
    }

    /**
     * Creates an HTML/XML tag policy filter.
     *
     * @param input token stream from tokenizer (or a very early filter)
     * @throws NullPointerException if {@code input} is null
     */
    public MLFilter(final TokenStream input)
    {
        super(Objects.requireNonNull(input, "input"));
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

        // 2) Consume input until we find a visible token or a boundary to emit.
        while (input.incrementToken()) {
            final int len = termAtt.length();
            if (len <= 0) {
                // Empty token: ignore silently.
                continue;
            }

            final int pos = posAtt.getPos();
            final boolean xml = (pos == XML.code);

            // Visible text token
            if (!xml) {
                if (suppressDepth > 0) {
                    // Inside a suppressed container: drop visible text.
                    continue;
                }

                // If structural boundary is pending, emit it before this visible token.
                if (pendingBoundaryPos != 0) {
                    deferredVisibleToken = captureState();
                    emitPendingBoundary();
                    return true;
                }

                return true;
            }

            // Markup token: parse and apply tag policy (always dropped unless mapped to boundary).
            final TagKind kind = classifyTag(termAtt.buffer(), len);
            if (kind == TagKind.INVALID || kind == TagKind.DECL_OR_COMMENT) {
                // malformed tag-like token / comment / doctype / processing instruction -> drop
                continue;
            }

            final int nameOff = tagNameOffset(termAtt.buffer(), len, kind);
            final int nameLen = tagNameLength(termAtt.buffer(), len, kind, nameOff);
            if (nameOff < 0 || nameLen <= 0) {
                continue; // malformed
            }

            final boolean selfClosing = isSelfClosingTag(termAtt.buffer(), len);

            // If we are inside a suppressed container, only track nested suppress-tags.
            if (suppressDepth > 0) {
                if (kind == TagKind.OPEN && !selfClosing && SUPPRESS.contains(termAtt.buffer(), nameOff, nameLen)) {
                    suppressDepth++;
                }
                else if (kind == TagKind.CLOSE && SUPPRESS.contains(termAtt.buffer(), nameOff, nameLen)) {
                    suppressDepth--;
                    if (suppressDepth < 0) suppressDepth = 0; // defensive
                }
                continue;
            }

            // Enter suppressed container
            if (kind == TagKind.OPEN && !selfClosing && SUPPRESS.contains(termAtt.buffer(), nameOff, nameLen)) {
                suppressDepth = 1;
                continue;
            }

            // Structural mappings
            if (kind == TagKind.CLOSE) {
                if (SECTION_ON_CLOSE.contains(termAtt.buffer(), nameOff, nameLen)) {
                    requestBoundary(PUNCTsection.code);
                    continue;
                }
                if (PARA_ON_CLOSE.contains(termAtt.buffer(), nameOff, nameLen)) {
                    requestBoundary(PUNCTpara.code);
                    continue;
                }
            }
            else if (kind == TagKind.OPEN) {
                // HTML empty elements like <br>, <hr>, and also <br/> self-closing variants
                if (PARA_ON_OPEN.contains(termAtt.buffer(), nameOff, nameLen)) {
                    requestBoundary(PUNCTpara.code);
                    continue;
                }
            }

            // Default: drop all other tags (inline formatting, unknown tags, etc.)
        }

        // 3) EOF: still emit a pending structural boundary if one remains.
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
        suppressDepth = 0;
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
    }

    @Override
    public void end() throws IOException
    {
        super.end();
        // Defensive cleanup (mirrors reset state)
        suppressDepth = 0;
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
    }

    // -----------------------------------------------------------------------
    // Boundary handling
    // -----------------------------------------------------------------------

    /**
     * Registers a structural boundary to emit later.
     *
     * <p>If multiple consecutive tags request boundaries before any visible token is emitted,
     * boundaries are coalesced; section boundary wins over paragraph boundary.</p>
     *
     * @param posCode one of {@code PUNCTpara.code} or {@code PUNCTsection.code}
     */
    private void requestBoundary(final int posCode)
    {
        if (posCode != PUNCTpara.code && posCode != PUNCTsection.code) {
            return; // ignore unsupported boundary kind
        }

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

    /**
     * Tag token class recognized by this filter.
     */
    private enum TagKind {
        /** Normal opening tag: {@code <p>} or {@code <p class="x">} or {@code <br/>}. */
        OPEN,
        /** Closing tag: {@code </p>}. */
        CLOSE,
        /** Declaration/comment/PI: {@code <!DOCTYPE...>}, {@code <!--...-->}, {@code <?xml...?>}. */
        DECL_OR_COMMENT,
        /** Not a recognizable tag token. */
        INVALID
    }

    /**
     * Classifies a token as opening tag, closing tag, declaration/comment/PI, or invalid.
     *
     * @param buf token buffer (only first {@code len} chars are valid)
     * @param len logical token length
     * @return tag kind
     */
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
     * Returns the start offset of the tag name inside the token buffer.
     *
     * @param buf token buffer
     * @param len logical token length
     * @param kind tag kind
     * @return offset of tag name, or {@code -1} if invalid
     */
    private static int tagNameOffset(final char[] buf, final int len, final TagKind kind)
    {
        if (len < 3) return -1;
        switch (kind) {
            case OPEN:  return 1;
            case CLOSE: return (len >= 4) ? 2 : -1;
            default:    return -1;
        }
    }

    /**
     * Returns the length of the tag name (without {@code <}, {@code </}, attributes, or {@code />}).
     *
     * <p>Parsing is bounded by {@code len}; this is critical because {@link CharTermAttribute#buffer()}
     * returns the underlying buffer capacity, not the logical token length.</p>
     *
     * @param buf token buffer
     * @param len logical token length
     * @param kind tag kind
     * @param off tag name offset
     * @return tag-name length, or {@code -1} if invalid
     */
    private static int tagNameLength(final char[] buf, final int len, final TagKind kind, final int off)
    {
        if (off < 0 || off >= len) return -1;

        for (int i = off; i < len; i++) {
            final char c = buf[i];
            if (c == '>' || c == '/' || isHtmlSpace(c)) {
                return i - off;
            }
        }

        // No terminator found inside logical token length -> malformed.
        return -1;
    }

    /**
     * Tests whether a tag token is self-closing (e.g. {@code <br/>}, {@code <img ... />}).
     *
     * @param buf token buffer
     * @param len logical token length
     * @return true if the last non-space character before {@code '>'} is {@code '/'}
     */
    private static boolean isSelfClosingTag(final char[] buf, final int len)
    {
        if (len < 4 || buf[0] != '<') return false;
        if (buf[len - 1] != '>') return false;

        for (int i = len - 2; i >= 1; i--) {
            final char c = buf[i];
            if (isHtmlSpace(c)) continue;
            return (c == '/');
        }
        return false;
    }

    /**
     * Small HTML/XML whitespace predicate used when parsing tags.
     */
    private static boolean isHtmlSpace(final char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}