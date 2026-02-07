/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco    http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010    Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import static com.github.oeuvres.alix.common.Upos.*;
import com.github.oeuvres.alix.common.TagFilter;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.TopArray;

/**
 * Build an efficient suggestion of words.
 * 
 * Data structure
 * <ul>
 * <li>Input, words from a lucene field: BytesRefHash formDic = {"Loïc", "Chloé", "Éric"…}</li>
 * <li>ASCII search: String ascii = "_loic_chloe_eric_…"</li>
 * <li>Word indexs: int[] starts = [0, 5, 11, 16…]</li>
 * </ul>
 * 
 * Ask for "LÔ", answer {"<mark>Lo</mark>ïc", "Ch<mark>lo</mark>é"} in original order.
 */
public class SuggestForm
{
    /** For {@link List#toArray(Object[])} */
    static final Suggestion[] SUGG0 = new Suggestion[0];

    /** Concatenation of all words, in ascii, in Term enumeration order, */
    private final String ascii;
    /** The field name */
    private final String fieldName;
    /** Source Alix field wrapper */
    private final FieldText fieldText;
    /** Forms of a field, by reference */
    private final BytesRefHash formDic;
    /** formId known by formDic in the ascii String order */
    private final int[] formIds;
    /** The lucene index to search in */
    private final IndexReader reader;
    /** Starting indexes of words in the ascii String */
    private final int[] starts;
    /** “word count”, dictionary size */
    private final int wc;
    
    /**
     * Build an index of words optimized for wildcard searching.
     * 
     * @param fieldText name of a lucene indexed field (text).
     */
    public SuggestForm (final FieldText fieldText)
    {
        this.fieldText = fieldText;
        this.fieldName = fieldText.fieldName;
        this.reader = fieldText.reader;
        this.formDic = fieldText.dic;
        final int size = formDic.size();
        // get the terms in their index order for faster loop
        // Term enumerations are always ordered by BytesRef.compareTo
        BytesId[] sorter = new BytesId[size];
        for (int formId = 0; formId < size; formId++) {
            sorter[formId] = new BytesId(formId);
            formDic.get(formId, sorter[formId].bytes);
        }
        Arrays.sort(sorter);
        starts = new int[size];
        formIds = new int[size];
        StringBuilder sb = new StringBuilder().append("_");
        int start = 0;
        for (int i = 0; i < size; i++) {
            final String w = Char.toASCII(sorter[i].bytes.utf8ToString()).toLowerCase();
            sb.append(w).append("_");
            starts[i] = start;
            formIds[i] = sorter[i].formId;
            start += w.length() + 1; 
        }
        wc = size;
        ascii = sb.toString();
    }

    /**
     * Find list of formId in the dictionary of a field by .
     * 
     * @param q a query string to find words.
     * @param wordFilter a wordfilter by tags.
     * @return a list of formId.
     */
    public int[] list(String q, final TagFilter wordFilter) {
        if (q == null || q.isEmpty()) return new int[0];
        boolean hasTags = (wordFilter != null && wordFilter.hasInfoTag());
        boolean noStop = (wordFilter != null && wordFilter.get(NOSTOP));
        boolean locs = (wordFilter != null && wordFilter.get(LOC));

        IntList formIdList = new IntList();
        q = Char.toASCII(q).toLowerCase();
        int fromIndex = 0;
        do {
            final int index = ascii.indexOf(q, fromIndex);
            if (index < 0) break;
            int found = Arrays.binarySearch(starts, index);
            if (found < 0) found = Math.abs(found) - 2;
            // next search will start with next word
            fromIndex = starts[found + 1];
            
            final int formId = formIds[found];
            // filter forms
            if (noStop) {
                if (fieldText.isStop(formId)) continue;
            }
            else if (locs) {
                if (!fieldText.isLocution(formId)) continue;
            }
            else if (hasTags) {
                if (!wordFilter.get(fieldText.tag(formId))) continue;
            }
            
            // formId selected
            formIdList.push(formId);
            // end of list
            if (found == wc - 1) break;
        } while(true);
        return formIdList.toArray();
    }
    
    /**
     * With a found word and an ASCII search, propose an highlighted version.
     * 
     * @param word ex: "Lœs"
     * @param q ex "_OE"
     * @return "L<mark>œ</mark>s"
     */
    static public String mark(final String word, final String q)
    {
        final String TAG_OPEN = "<b>";
        final String TAG_CLOSE = "</b>";
        final String qNorm = Char.toASCII(q, true).toLowerCase();
        final int qLen = qNorm.length();
        final String ascii = Char.toASCII(word).toLowerCase();
        final int asciiLen = ascii.length();
        final String jok = "£";
        boolean ligature = (asciiLen != word.length());
        final String work = (ligature)?word.replaceAll("([æÆᴁﬀﬁﬂĳĲœᴔŒɶﬆ])", "$1" + jok):word;
        final StringBuilder sb = new StringBuilder();
        int fromIndex = 0;
        do {
            final int index = ascii.indexOf(qNorm, fromIndex);
            if (index < 0) break;

            // found at start
            if (index == 0) {
                sb.append(TAG_OPEN);
            }
            // if letters between 2 tags
            else if (index - fromIndex > 0) {
                // close last tag
                if (fromIndex > 0) { // at least one word found
                    sb.append(TAG_CLOSE);
                }
                // append chars before tag
                sb.append(work.substring(fromIndex, index));
                // open new tag
                sb.append(TAG_OPEN);
            }
            
            // append found content
            sb.append(work.substring(index, index + qLen));
            fromIndex = index + qLen;
        } while(true);
        if (fromIndex > 0) { // at least one word found
            sb.append(TAG_CLOSE);
        }
        sb.append(work.substring(fromIndex));
        String marked = sb.toString();
        if (ligature) {
            marked = marked.replaceAll(jok, "");
        }
        return marked;
    }
    
    /**
     * A data row used to sort terms by bytes
     */
    private class BytesId implements Comparable<BytesId>
    {
        final BytesRef bytes;
        final int formId;
        BytesId(int formId) {
            this.bytes = new BytesRef();
            this.formId = formId;
        }
        
        @Override
        public int compareTo(BytesId other) {
            return bytes.compareTo(other.bytes);
        }
    }
    
    /**
     * Search a wildcard.
     * 
     * @param q wildcard query.
     * @param count amount of terms to collect.
     * @param formFilter optional filter by tag.
     * @param docFilter optional filter of documents.
     * @return a list of words ordered by freq.
     * @throws IOException lucene errors.
     */
    public Suggestion[] search(
        final String q, 
        final int count, 
        final TagFilter formFilter, 
        final BitSet docFilter
    ) throws IOException
    {
        if (q == null || q.isBlank()) return new Suggestion[0];
        final int[] formIds = list(q, formFilter);
        final int formLen = formIds.length;
        int[] formHits = new int[formLen];
        long[] formFreq = new long[formLen];
        // formId in TermsEnum are faster than shuffled
        BytesRef bytes = new BytesRef();
        PostingsEnum postings = null;
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            final int docBase = context.docBase;
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            Bits live = leaf.getLiveDocs();
            for (int i = 0; i < formLen; i++) {
                final int formId = formIds[i];
                formDic.get(formId, bytes);
                if (!tenum.seekExact(bytes)) {
                    continue;
                }
                postings = tenum.postings(postings, PostingsEnum.FREQS);
                int docLeaf;
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    int docId = docBase + docLeaf;
                    if (docFilter != null && !docFilter.get(docId)) continue; // document not in the filter
                    // freq always > 0
                    formHits[i]++;
                    final int freq = postings.freq();
                    formFreq[i] += freq;
                }
            }
        }
        // Select the top best terms by freq
        TopArray top = new TopArray(count);
        for (int formIndex = 0; formIndex < formLen; formIndex++) {
            final long freq = formFreq[formIndex];
            if (freq == 0) continue;
            top.push(formIndex, freq);
        }
        // unroll the top to build the result array
        ArrayList<Suggestion> list = new ArrayList<>();
        for (TopArray.IdScore pair: top) {
            final int formIndex = pair.id();
            final int formId = formIds[formIndex];
            formDic.get(formId, bytes);
            final String word = bytes.utf8ToString();
            final String marked = mark(word, q);
            list.add(new Suggestion(word, marked, formHits[formIndex], formFreq[formIndex]));
        }
        return list.toArray(SUGG0);
    }

    /**
     * Content of a suggested form.
     */
    public static class Suggestion
    {
        /** Count of occurrences. */
        private final long freq;
        /** Word form like in the indexed field. */
        private final String form;
        /** Count of documents concerned by this form. */
        private final int hits;
        /** Form with query hilited. */
        private final String marked;
        
        /**
         * Default constructor with required fields.
         * 
         * @param form form like in the indexed field.
         * @param marked form with query hilited.
         * @param hits count of documents concerned.
         * @param freq count of occurrences concerned.
         */
        public Suggestion(final String form, final String marked, final int hits, final long freq) {
            this.form = form;
            this.marked = marked;
            this.hits = hits;
            this.freq = freq;
        }
        
        /**
         * Returns original indexed form.
         * 
         * @return form.
         */
        public String form() {
            return form;
        }
        
        /**
         * Count of ocurrences  for this form.
         * 
         * @return freq.
         */
        public long freq() {
            return freq;
        }
        
        /**
         * Count of documents with this form.
         * 
         * @return hits.
         */
        public int hits() {
            return hits;
        }
        
        /**
         * Form with query hilited.
         * 
         * @return hilited form.
         */
        public String marked() {
            return marked;
        }
        @Override
        public String toString()
        {
            return form + " (" + freq + " occs, in " + hits + "docs) " + marked;
        }
    }

    
    @Override
    public String toString()
    {
        return ascii + "\n" + Arrays.toString(formIds);
    }
}
