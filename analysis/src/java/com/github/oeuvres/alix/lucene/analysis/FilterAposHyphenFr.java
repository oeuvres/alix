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
package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

/**
 * A filter that decomposes words on a list of suffixes and prefixes, mainly to handle 
 * hyphenation and apostrophe ellision in French. The original token is broken and lost,
 * offset are precisely kept, so that word counting and stats are not biased by multiple
 * words on same positions.
 * 
 * https://fr.wikipedia.org/wiki/Emploi_du_trait_d%27union_pour_les_pr%C3%A9fixes_en_fran%C3%A7ais
 * 
 * Known side effect : qu’en-dira-t-on, donne-m’en, emmène-m’y.
 */
public class FilterAposHyphenFr extends TokenFilter
{
    private static final int MAX_STEPS = 16;

    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Char index in source text. */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** A linguistic category as a short number, see {@link Upos} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Stack of stored states */
    private final AttLinkedList deque = new AttLinkedList();

    /** Ellisions prefix */
    private static final CharArrayMap<char[]> PREFIX = new CharArrayMap<>(30, false);
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
        PREFIX.put("quelqu'", "quelque".toCharArray());
        PREFIX.put("Quelqu'", "quelque".toCharArray());
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

        SUFFIX.put("-ce", "ce".toCharArray()); // Serait-ce ?
        SUFFIX.put("-ci", null); // cette année-ci, ceux-ci.
        SUFFIX.put("-elle", "elle".toCharArray()); // dit-elle.
        SUFFIX.put("-elles", "elles".toCharArray()); // disent-elles.
        SUFFIX.put("-en", "en".toCharArray()); // parlons-en.
        SUFFIX.put("-eux", "eux".toCharArray()); // 
        SUFFIX.put("-il", "il".toCharArray()); // dit-il.
        SUFFIX.put("-ils", "ils".toCharArray()); // disent-ils.
        SUFFIX.put("-je", "je".toCharArray()); // dis-je.
        SUFFIX.put("-la", "la".toCharArray()); // prends-la !
        SUFFIX.put("-là", null); // cette année-là, ceux-là.
        SUFFIX.put("-le", "le".toCharArray()); // rends-le !
        SUFFIX.put("-les", "les".toCharArray()); // rends-les !
        SUFFIX.put("-leur", "leur".toCharArray()); // rends-leur !
        SUFFIX.put("-lui", "lui".toCharArray()); // rends-leur !
        SUFFIX.put("-me", "me".toCharArray()); // laissez-moi !
        SUFFIX.put("-moi", "moi".toCharArray()); // laissez-moi !
        SUFFIX.put("-nous", "nous".toCharArray()); // laisse-nous.
        SUFFIX.put("-on", "on".toCharArray()); // laisse-nous.
        SUFFIX.put("-t", null); // habite-t-il ici ?
        SUFFIX.put("-te", "te".toCharArray()); // 
        SUFFIX.put("-toi", "toi".toCharArray()); // 
        SUFFIX.put("-tu", "tu".toCharArray()); // viendras-tu ?
        SUFFIX.put("-vous", "vous".toCharArray()); // voulez-vous ?
        SUFFIX.put("-y", "y".toCharArray()); // allons-y.
    }

    public FilterAposHyphenFr(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        // Emit buffered tokens first
        if (!deque.isEmpty()) {
            deque.removeFirst(termAtt, offsetAtt);
        }
        else {
            if (!input.incrementToken()) return false;
        }

        // do not try to split in XML tags
        if (flagsAtt.getFlags() == XML.code) {
            return true;
        }

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
                    deque.addLast(
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
                        deque.addFirst(
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

        throw new IllegalStateException("FilterAposHyphenFr: exceeded MAX_STEPS, deque=" + deque);
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
        deque.clear(); // add clear() to AttLinkedList (recommended)
    }
}
