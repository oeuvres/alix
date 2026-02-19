/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic tools for French,
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
package com.github.oeuvres.alix.lucene.analysis.fr;


import java.io.IOException;

import org.apache.lucene.analysis.CharArraySet;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.lucene.analysis.TokenStateQueue;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

/**
 * A filter that decomposes words on a list of suffixes and prefixes, mainly to handle
 * hyphenation and apostrophe elision in French. The original token is broken and lost,
 * offsets are precisely kept, so that word counting and stats are not biased by multiple
 * words on same positions.
 *
 * https://fr.wikipedia.org/wiki/Emploi_du_trait_d%27union_pour_les_pr%C3%A9fixes_en_fran%C3%A7ais
 *
 * Known side effect: qu’en-dira-t-on, donne-m’en, emmène-m’y.
 */
public class FrenchCliticSplitFilter extends TokenFilter
{
    private static final int MAX_STEPS = 16;

    /** Small ring is enough in practice; can grow if needed. */
    private static final int QUEUE_CAPACITY = 16;

    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Char index in source text. */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** A linguistic category as a short number, see {@link Upos} */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Ring-buffer of stored token states (lazy init). */
    private TokenStateQueue queue;

    /** Snapshot of the current token before any split/normalization, used for rollback. */
    private AttributeSource original;

    /** Reusable buffer for case-insensitive keep-as-is lookups. */
    private char[] normBuf = new char[32];

    /** Scratch attribute source used to build buffered tokens without mutating current token. */
    private AttributeSource scratch;
    private CharTermAttribute scratchTerm;
    private OffsetAttribute scratchOffset;
    private PositionIncrementAttribute scratchPosInc;

    /** Tokens that must never be split (lookup is performed case-insensitively in {@link #keepAsIs()}). */
    private static final CharArraySet KEEP_AS_IS = new CharArraySet(32, true);
    static {
        // Lexicalized form: splitting "quelqu'un" into "quelque" + "un" is usually undesirable.
        KEEP_AS_IS.add("c'est");
        KEEP_AS_IS.add("d'abord");
        KEEP_AS_IS.add("d'accord");
        KEEP_AS_IS.add("d'ailleurs");
        KEEP_AS_IS.add("d'autant");
        KEEP_AS_IS.add("d'autre");
        KEEP_AS_IS.add("d'autres");
        KEEP_AS_IS.add("d'emblée");
        KEEP_AS_IS.add("d'après");
        KEEP_AS_IS.add("d'avec");
        KEEP_AS_IS.add("d'entre");
        KEEP_AS_IS.add("d'ici");
        KEEP_AS_IS.add("l'un");
        KEEP_AS_IS.add("l'une");
        KEEP_AS_IS.add("l'autre");
        KEEP_AS_IS.add("n'est");
        KEEP_AS_IS.add("n'est-ce");
        KEEP_AS_IS.add("n'importe");
        KEEP_AS_IS.add("n'empêche");
        KEEP_AS_IS.add("qu'est-ce");
    }

    /** Ellisions prefix */
    private static final CharArrayMap<char[]> PREFIX = new CharArrayMap<>(30, true);
    static {
        PREFIX.put("d'", "de".toCharArray());
        PREFIX.put("D'", "de".toCharArray());
        PREFIX.put("j'", "je".toCharArray());
        PREFIX.put("J'", "je".toCharArray());
        PREFIX.put("jusqu'", "jusque".toCharArray());
        PREFIX.put("Jusqu'", "jusque".toCharArray());
        PREFIX.put("l'", "l'".toCharArray()); // je l’aime. le ou la
        PREFIX.put("L'", "l'".toCharArray());
        PREFIX.put("lorsqu'", "lorsque".toCharArray());
        PREFIX.put("Lorsqu'", "lorsque".toCharArray());
        PREFIX.put("m'", "me".toCharArray());
        PREFIX.put("M'", "me".toCharArray());
        PREFIX.put("n'", "ne".toCharArray()); // N’y va pas.
        PREFIX.put("N'", "ne".toCharArray());
        PREFIX.put("puisqu'", "puisque".toCharArray());
        PREFIX.put("Puisqu'", "puisque".toCharArray());
        PREFIX.put("qu'", "que".toCharArray());
        PREFIX.put("Qu'", "que".toCharArray());
        PREFIX.put("quoiqu'", "quoique".toCharArray());
        PREFIX.put("Quoiqu'", "quoique".toCharArray());
        PREFIX.put("s'", "se".toCharArray());
        PREFIX.put("S'", "se".toCharArray());
        PREFIX.put("t'", "te".toCharArray());
        PREFIX.put("T'", "te".toCharArray());
    }

    /** Hyphen suffixes */
    private static final CharArrayMap<char[]> SUFFIX = new CharArrayMap<>(30, false);
    static {
        SUFFIX.put("-ce", "ce".toCharArray()); // Serait-ce ?
        SUFFIX.put("-ci", null);               // cette année-ci, ceux-ci.
        SUFFIX.put("-elle", "elle".toCharArray());   // dit-elle.
        SUFFIX.put("-elles", "elles".toCharArray()); // disent-elles.
        SUFFIX.put("-en", "en".toCharArray());       // parlons-en.
        SUFFIX.put("-eux", "eux".toCharArray());
        SUFFIX.put("-il", "il".toCharArray());       // dit-il.
        SUFFIX.put("-ils", "ils".toCharArray());     // disent-ils.
        SUFFIX.put("-je", "je".toCharArray());       // dis-je.
        SUFFIX.put("-la", "la".toCharArray());       // prends-la !
        SUFFIX.put("-là", null);                     // cette année-là, ceux-là.
        SUFFIX.put("-le", "le".toCharArray());       // rends-le !
        SUFFIX.put("-les", "les".toCharArray());     // rends-les !
        SUFFIX.put("-leur", "leur".toCharArray());   // rends-leur !
        SUFFIX.put("-lui", "lui".toCharArray());     // rends-lui !
        SUFFIX.put("-me", "me".toCharArray());
        SUFFIX.put("-moi", "moi".toCharArray());     // laissez-moi !
        SUFFIX.put("-nous", "nous".toCharArray());   // laisse-nous.
        SUFFIX.put("-on", "on".toCharArray());       // dit-on.
        SUFFIX.put("-t", null);                      // habite-t-il ici ?
        SUFFIX.put("-te", "te".toCharArray());
        SUFFIX.put("-toi", "toi".toCharArray());
        SUFFIX.put("-tu", "tu".toCharArray());       // viendras-tu ?
        SUFFIX.put("-vous", "vous".toCharArray());   // voulez-vous ?
        SUFFIX.put("-y", "y".toCharArray());         // allons-y.
    }

    public FrenchCliticSplitFilter(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        ensureQueue();

        // Emit buffered tokens first
        if (!queue.isEmpty()) {
            clearAttributes();
            queue.removeFirst(this);
        }
        else {
            if (!input.incrementToken()) return false;
        }

        // do not try to split in XML tags
        if (posAtt.getPos() == XML.code) {
            return true;
        }

        // Some lexicalized forms should never be split.
        if (keepAsIs()) {
            return true;
        }

        // Capture the token state before any in-place normalization/splitting.
        this.copyTo(original);

        try {
            for (int step = 0; step < MAX_STEPS; step++) {
                final int len = termAtt.length();
                if (len <= 1) return true;

            final char[] buf = termAtt.buffer();

            final int hyphLast = lastHyphenIndexAndNormalize(buf, len);
            final int aposFirst = firstAposIndexAndNormalize(buf, len);

            if (aposFirst < 0 && hyphLast < 0) return true;

            // apos is last char, let it run (maths A', D', etc.)
            if (aposFirst == len - 1) return true;

            // hyphen is first or last char, let it run
            if (hyphLast == 0 || hyphLast == len - 1) return true;

            // Prefix split on apostrophe
            if (aposFirst > 0) {
                final int startOffset = offsetAtt.startOffset();
                final int prefixLen = aposFirst + 1;

                final char[] value = PREFIX.get(buf, 0, prefixLen);
                if (value != null) {
                    // keep term after prefix for next call
                    bufferLastFromCurrent(
                        buf,
                        prefixLen,
                        len - prefixLen,
                        startOffset + prefixLen,
                        offsetAtt.endOffset()
                    );

                    // send the prefix
                    termAtt.copyBuffer(value, 0, value.length);
                    offsetAtt.setOffset(startOffset, startOffset + prefixLen);
                    return true;
                }
            }

            // Suffix split on hyphen
            if (hyphLast > 0) {
                final int suffixLen = len - hyphLast;

                if (SUFFIX.containsKey(buf, hyphLast, suffixLen)) {
                    final char[] value = SUFFIX.get(buf, hyphLast, suffixLen);

                    if (value != null) {
                        bufferFirstFromCurrent(
                            value,
                            0,
                            value.length,
                            offsetAtt.startOffset() + hyphLast,
                            offsetAtt.endOffset()
                        );
                    }

                    // set term without suffix, loop again (may strip multiple suffixes)
                    offsetAtt.setOffset(offsetAtt.startOffset(), offsetAtt.startOffset() + hyphLast);
                    termAtt.setLength(hyphLast);
                    continue;
                }
            }

                return true; // term is OK like that
            }

            throw new IllegalStateException("FilterAposHyphenFr: exceeded MAX_STEPS, queue=" + queue);
        }
        catch (IllegalStateException e) {
            // If queue capacity is exceeded, rollback: no split, emit the original token as-is.
            final String msg = e.getMessage();
            if (msg != null && msg.startsWith("TokenStateQueue is full")) {
                queue.clear();
                original.copyTo(this);
                return true;
            }
            throw e;
        }
    }

    /**
     * Buffer a token built from the current token state (all attributes copied),
     * then overriding term and offsets.
     *
     * Important: position increment is normalized to 1 so that gaps from the original token
     * are not duplicated on generated tokens.
     */
    private void bufferLastFromCurrent(final char[] buf, final int off, final int len, final int startOffset, final int endOffset) {
        this.copyTo(scratch);
        scratchTerm.copyBuffer(buf, off, len);
        scratchOffset.setOffset(startOffset, endOffset);
        scratchPosInc.setPositionIncrement(1);
        queue.addLast(scratch);
    }

    private void bufferFirstFromCurrent(final char[] buf, final int off, final int len, final int startOffset, final int endOffset) {
        this.copyTo(scratch);
        scratchTerm.copyBuffer(buf, off, len);
        scratchOffset.setOffset(startOffset, endOffset);
        scratchPosInc.setPositionIncrement(1);
        queue.addFirst(scratch);
    }

    private void ensureQueue() {
        if (queue != null) return;

        queue = new TokenStateQueue(
            QUEUE_CAPACITY,
            QUEUE_CAPACITY,
            TokenStateQueue.OverflowPolicy.THROW,
            this
        );

        scratch = this.cloneAttributes();
        scratch.clearAttributes();
        scratchTerm = scratch.getAttribute(CharTermAttribute.class);
        scratchOffset = scratch.getAttribute(OffsetAttribute.class);
        scratchPosInc = scratch.getAttribute(PositionIncrementAttribute.class);

        original = this.cloneAttributes();
        original.clearAttributes();
    }

    /**
     * Case-insensitive lookup for tokens that must not be split.
     *
     * <p>Performs a lightweight normalization for apostrophe and hyphen variants
     * without mutating the current token buffer.</p>
     */
    private boolean keepAsIs() {
        final int len = termAtt.length();
        if (len <= 0) return false;
        ensureNormBuf(len);
        final char[] buf = termAtt.buffer();
        for (int i = 0; i < len; i++) {
            char c = buf[i];
            if (c == '’' || c == '\u02BC') c = '\'';
            else if (c == '\u2010' || c == '\u2011' || c == '\u00AD') c = '-';
            normBuf[i] = Character.toLowerCase(c);
        }
        return KEEP_AS_IS.contains(normBuf, 0, len);
    }

    private void ensureNormBuf(final int len) {
        if (normBuf.length >= len) return;
        int newLen = normBuf.length;
        while (newLen < len) newLen <<= 1;
        normBuf = new char[newLen];
    }

    private static int firstAposIndexAndNormalize(final char[] buf, final int len) {
        for (int i = 0; i < len; i++) {
            char c = buf[i];
            if (c == '’' || c == '\u02BC') { // U+2019 or U+02BC
                buf[i] = '\'';
                c = '\'';
            }
            if (c == '\'') return i;
        }
        return -1;
    }

    private static int lastHyphenIndexAndNormalize(final char[] buf, final int len) {
        for (int i = len - 1; i >= 0; i--) {
            char c = buf[i];
            if (c == '\u2010' || c == '\u2011' || c == '\u00AD') { // hyphen variants
                buf[i] = '-';
                c = '-';
            }
            if (c == '-') return i;
        }
        return -1;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        if (queue != null) queue.clear();
    }
}
