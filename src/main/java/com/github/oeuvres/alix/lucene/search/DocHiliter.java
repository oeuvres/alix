/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
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
package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

/**
 * Build a flatten version of a term vector for a document, to hilite some
 * terms.
 */
public class DocHiliter
{
    /** The list of tokens in order of the document */
    final Token[] toks;
    /** Max count for the most frequent token */
    final int countMax;
    /** Used for toArray() conversions */
    private final static Token[] TOKEN0 = new Token[0];

    /**
     * Flatten a term vector as a list of tokens in document order.
     * 
     * @param tvek
     * @throws NoSuchFieldException
     * @throws IOException          Lucene errors.
     */
    public DocHiliter(Terms tvek, ByteRunAutomaton include, ByteRunAutomaton exclude)
            throws NoSuchFieldException, IOException {
        if (!tvek.hasFreqs() || !tvek.hasPositions() || !tvek.hasOffsets()) {
            throw new NoSuchFieldException(
                    "Missig offsets in search Vector; see FieldType.setStoreTermVectorOffsets(true)");
        }
        int max = 0; // get max token count
        ArrayList<Token> offsets = new ArrayList<Token>();
        // in the underlying implementation of term vectors enum (TVTermsEnum)
        // https://github.com/apache/lucene-solr/blob/master/lucene/core/src/java/org/apache/lucene/codecs/compressing/CompressingTermVectorsReader.java
        // seekExact() is not supported, seekCeil() is linear
        // so letś be linear
        TermsEnum tenum = tvek.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        // the loop is here not very efficient for just a few term
        while (tenum.next() != null) {
            BytesRef ref = tenum.term();
            if (exclude != null && exclude.run(ref.bytes, ref.offset, ref.length))
                continue;
            if (include != null && !include.run(ref.bytes, ref.offset, ref.length))
                continue;
            String form = tenum.term().utf8ToString();
            // set and get a postings to this tenum, should be there, before will not work
            postings = tenum.postings(postings, PostingsEnum.OFFSETS);
            while (postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
                int pos = -1;
                int freq = postings.freq();
                if (freq > max)
                    max = freq;
                for (int i = 0; i < freq; i++) {
                    pos = postings.nextPosition();
                    offsets.add(new Token(pos, postings.startOffset(), postings.endOffset(), form, termId, freq));
                }
            }
            termId++; // new termId
        }
        Collections.sort(offsets); // sort offsets before hilite
        toks = offsets.toArray(TOKEN0);
        this.countMax = max;
    }

    /**
     * Group the token Array to get expressions
     */
    public Token[] group(final int gap, boolean repetitions)
    {
        if (this.toks == null)
            return null; // ?? why ? Shall we send an exception ?
        // if only one word, should capture repetitions
        if (this.toks.length < 2)
            return null; // only one word found, no group possible
        Token[] toks = this.toks; // localize the rail
        ArrayList<Token> offsets = new ArrayList<Token>();
        Token last = toks[0];
        // loop on sorted tokens,
        for (int i = 1, limit = toks.length; i < limit; i++) {
            final Token tok = toks[i];
            // other token for this position, first one should be the longest
            // maybe there is better to do in the for lemma/orth
            if (last.pos == tok.pos)
                continue;
            // distant tokens
            if (tok.pos - last.posLast > gap) {
                // if we are not filtering expressions, we can record last token
                // if (!expression) offsets.add(last);
                // for expression, send only multi word tokens
                if (last.phrase)
                    offsets.add(last);
                // remember last and continue
                last = tok;
                continue;
            }
            // merge a locution
            last.span++;
            last.end = tok.end;
            last.posLast = tok.pos;
            last.form = last.form + '_' + tok.form;
            if (repetitions)
                last.phrase = true; // allow repetitions
            else if (last.termId != tok.termId)
                last.phrase = true; // or not

            // keep pos and start, nothing relevant to do with count
        }
        toks = offsets.toArray(TOKEN0);
        return toks;
    }

    /**
     * A record to sort term vectors occurrences with offsets for hilite
     */
    static class Token implements Comparable<Token>
    {
        /** Token first position */
        final int pos;
        /** Start offset in chars */
        final int start;
        /** End offset in chars */
        int end;
        /** Indexed form (ex: lemma) */
        String form;
        /** A local id (scope: a document) for the term, used for fast equals */
        final int termId;
        /** Freq of term in document (repeated for each occurrences), used for info */
        final int count;
        /** Width in positions, used for concatenantion of search */
        int span;
        /** A second possible freq, for info */
        final int count2;
        /** A flag if different words */
        boolean phrase;
        /** for more than on positions */
        int posLast;

        public Token(final int pos, final int start, final int end) {
            this(pos, start, end, null, -1, 1, 0, 0);
        }

        public Token(final int pos, final int start, final int end, final String form, final int termId) {
            this(pos, start, end, form, termId, 0, 1, 0);
        }

        public Token(final int pos, final int start, final int end, final String form, final int termId,
                final int count) {
            this(pos, start, end, form, termId, count, 1, 0);
        }

        public Token(final int pos, final int start, final int end, final String form, final int termId,
                final int count, final int span, final int count2) {
            this.pos = this.posLast = pos;
            this.start = start;
            this.end = end;
            this.form = form;
            this.termId = termId;
            this.span = span;
            this.count = count;
            this.count2 = count2;
        }

        @Override
        public int compareTo(Token tok2)
        {
            // sort by position, and biggest token first for same position
            final int cp = Integer.compare(this.pos, tok2.pos);
            if (cp != 0)
                return cp;
            return Integer.compare(this.end, tok2.end);
        }
    }

}
