/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Removes XML markup, emits structural punctuation for configured closing tags,
 * and annotates the final token of each consecutive punctuation run with the
 * strongest boundary in that run.
 *
 * <p>Paragraph and section tokens retain the source offsets and positions of
 * their triggering closing tags. Consecutive closing-tag requests with no
 * intervening visible token are coalesced, as before. Other punctuation is
 * emitted unchanged, including source line-break markers.</p>
 *
 * <p>Boundary annotation uses only the existing UPOS categories PUNCTsent,
 * PUNCTpara and PUNCTsection. No sentence recognition from spelling or
 * language-specific punctuation rules is performed here. PUNCTstruct does
 * not independently imply a paragraph or sentence boundary.</p>
 *
 * <p>The boundary event occurs AFTER the annotated punctuation token. Only
 * the last punctuation token of the run carries a non-NONE value. The event
 * is emitted even at EOF. Lexical tokens carry NONE.</p>
 *
 * <p>Downstream linguistic filters can continue using POS to recognize
 * punctuation barriers without depending on BoundaryAttribute.</p>
 */
public final class MarkupBoundaryFilter extends TokenFilter
{
    /** Closing tags mapped to paragraph boundaries. */
    public static final String DEFAULT_PARA_ELEMENTS =
        "ab|address|blockquote|cell|dd|div|dt|h1|h2|h3|h4|h5|h6|head|item|l|label|li|p|pre|row|td|th|tr";
    /** Closing tags mapped to section boundaries. */
    public static final String DEFAULT_SECTION_ELEMENTS =
        "article|back|body|chapter|div0|div1|div2|div3|div4|div5|div6|div7|front|group|main|section|text";
    /** Synthetic paragraph punctuation term. */
    public static final String PARA_MARK = "¶";
    /** Synthetic section punctuation term. */
    public static final String SECTION_MARK = "§";

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final BoundaryAttribute boundaryAtt = addAttribute(BoundaryAttribute.class);
    private final CharArraySet paraOnClose;
    private final CharArraySet sectionOnClose;

    private int pendingBoundaryPos;
    private State pendingBoundaryState;
    private State deferredVisibleToken;
    private boolean exhausted;
    private State lookahead;
    private int boundaryStrength = BoundaryAttribute.NONE;

    /**
     * Creates a filter with the default XML closing-tag mappings.
     * @param input upstream token stream
     */
    public MarkupBoundaryFilter(final TokenStream input)
    {
        this(input, DEFAULT_PARA_ELEMENTS, DEFAULT_SECTION_ELEMENTS);
    }

    /**
     * Creates a filter with explicit XML closing-tag mappings.
     * @param input upstream token stream
     * @param paraElements pipe-separated local names for paragraph boundaries
     * @param sectionElements pipe-separated local names for section boundaries
     */
    public MarkupBoundaryFilter(final TokenStream input, final String paraElements,
        final String sectionElements)
    {
        super(Objects.requireNonNull(input, "input"));
        paraOnClose = compileTagSet(paraElements);
        sectionOnClose = compileTagSet(sectionElements);
    }

    /**
     * Compiles pipe-separated tag names into a case-insensitive local-name set.
     * Namespace prefixes and surrounding whitespace are ignored.
     * @param names pipe-separated names, or null for an empty set
     * @return configured tag local names
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
                final int ls = (p >= start) ? p + 1 : start;
                if (end > ls) set.add(names.substring(ls, end));
            }
        }
        return set;
    }

    /**
     * Finishes the stream. All boundary events have already been emitted.
     * @throws IOException if the upstream stream cannot finish
     */
    @Override
    public void end() throws IOException
    {
        super.end();
        clearPending();
        boundaryAtt.setBoundary(BoundaryAttribute.NONE);
    }

    /**
     * Emits the next visible or synthetic punctuation token. A punctuation
     * token is delayed by one token to determine whether it ends its run.
     * @return true if a token is available
     * @throws IOException if reading the upstream stream fails
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        if (lookahead != null) {
            restoreState(lookahead);
            lookahead = null;
        }
        else if (!readMarkupToken()) return false;

        boundaryAtt.setBoundary(BoundaryAttribute.NONE);
        final Upos pos = get(posAtt.getPos());
        if (!isPunctuation(pos)) return true;

        boundaryStrength = Math.max(boundaryStrength, strength(pos));
        final State current = captureState();
        final boolean hasNext = readMarkupToken();
        final boolean continues = hasNext && isPunctuation(get(posAtt.getPos()));
        if (hasNext) lookahead = captureState();
        restoreState(current);
        if (!continues) {
            boundaryAtt.setBoundary(boundaryStrength);
            boundaryStrength = BoundaryAttribute.NONE;
        }
        return true;
    }

    /**
     * Resets all buffered tokens and boundary state for stream reuse.
     * @throws IOException if resetting the upstream stream fails
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        clearPending();
        boundaryAtt.setBoundary(BoundaryAttribute.NONE);
    }

    /** Clears both markup and punctuation lookahead state. */
    private void clearPending()
    {
        pendingBoundaryPos = 0;
        pendingBoundaryState = null;
        deferredVisibleToken = null;
        exhausted = false;
        lookahead = null;
        boundaryStrength = BoundaryAttribute.NONE;
    }

    /**
     * Returns the boundary POS associated with the current closing XML tag.
     * Section takes precedence when both mappings match.
     * @return boundary POS ordinal, or zero for no boundary
     */
    private int closeTagBoundaryPos()
    {
        final char[] buf = termAtt.buffer();
        final int len = termAtt.length();
        if (len < 3 || buf[0] != '<' || buf[1] != '/') return 0;
        final long span = readLocalTagNameSpan(buf, len, 2);
        final int start = (int) (span >>> 32);
        final int end = (int) span;
        if (end <= start) return 0;
        final int nameLen = end - start;
        if (sectionOnClose.contains(buf, start, nameLen)) return PUNCTsection.ordinal();
        if (paraOnClose.contains(buf, start, nameLen)) return PUNCTpara.ordinal();
        return 0;
    }

    /** Emits a coalesced closing-tag boundary using its captured source state. */
    private void emitPendingBoundary()
    {
        restoreState(pendingBoundaryState);
        pendingBoundaryState = null;
        posAtt.setPos(pendingBoundaryPos);
        termAtt.setEmpty().append(pendingBoundaryPos == PUNCTsection.ordinal()
            ? SECTION_MARK : PARA_MARK);
        pendingBoundaryPos = 0;
    }

    /**
     * Tests the UPOS punctuation family, including structural refinements.
     * @param pos upstream grammatical annotation
     * @return true for punctuation
     */
    private static boolean isPunctuation(final Upos pos)
    {
        return pos != null && pos.base() == PUNCT;
    }

    /**
     * Tests XML whitespace.
     * @param c character to inspect
     * @return true for whitespace recognized in tag names
     */
    private static boolean isSpace(final char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     * Finds the local-name span of an XML tag.
     * @param tag tag buffer
     * @param n tag length
     * @param from index after the opening delimiter
     * @return packed start and exclusive end offsets, or zero on failure
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
        return ((long) localStart << 32) | (nameEnd & 0xFFFFFFFFL);
    }

    /**
     * Reads one token using the existing markup removal and closing-tag
     * coalescence policy, independently of punctuation-run annotation.
     * @return true if a visible or synthetic token is available
     * @throws IOException if the upstream stream cannot advance
     */
    private boolean readMarkupToken() throws IOException
    {
        if (deferredVisibleToken != null) {
            restoreState(deferredVisibleToken);
            deferredVisibleToken = null;
            return true;
        }
        while (!exhausted) {
            if (!input.incrementToken()) {
                exhausted = true;
                break;
            }
            if (posAtt.getPos() != XML.ordinal()) {
                if (pendingBoundaryPos != 0) {
                    deferredVisibleToken = captureState();
                    emitPendingBoundary();
                }
                return true;
            }
            final int boundaryPos = closeTagBoundaryPos();
            if (boundaryPos != 0) requestBoundary(boundaryPos);
        }
        if (pendingBoundaryPos != 0) {
            emitPendingBoundary();
            return true;
        }
        return false;
    }

    /**
     * Retains a closing-tag boundary request, preferring section to paragraph.
     * @param posCode recognized boundary POS ordinal
     */
    private void requestBoundary(final int posCode)
    {
        if (pendingBoundaryPos == 0 || (pendingBoundaryPos == PUNCTpara.ordinal()
            && posCode == PUNCTsection.ordinal())) {
            pendingBoundaryPos = posCode;
            pendingBoundaryState = captureState();
        }
    }

    /**
     * Maps existing grammatical punctuation refinements to boundary strength.
     * @param pos punctuation annotation
     * @return structural separator strength
     */
    private static int strength(final Upos pos)
    {
        return switch (pos) {
            case PUNCTsent -> BoundaryAttribute.SENTENCE;
            case PUNCTpara -> BoundaryAttribute.PARAGRAPH;
            case PUNCTsection -> BoundaryAttribute.SECTION;
            default -> BoundaryAttribute.NONE;
        };
    }
}
