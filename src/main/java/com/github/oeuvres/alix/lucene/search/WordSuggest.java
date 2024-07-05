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

import com.github.oeuvres.alix.fr.Tag.TagFilter;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.TopArray;

/**
 * Build an efficient suggestion of words.
 * 
 * Data structure
 * <li>Input, words from a lucene field: BytesRefHash formDic = {"Loïc", "Chloé", "Éric"…}</li>
 * <li>ASCII search: String ascii = "_loic_chloe_eric_…"</li>
 * <li>Word indexs: int[] starts = [0, 5, 11, 16…]</li>
 * 
 * Ask for "LÔ", answer {"<mark>Lo</mark>ïc", "Ch<mark>lo</mark>é"} in original order.
 */
public class WordSuggest
{
    /** Source Alix field wrapper */
    private final FieldText fieldText;
    /** The lucene index to search in */
    private final IndexReader reader;
    /** The field name */
    private final String fieldName;
    /** Forms of a field, by reference */
    private final BytesRefHash formDic;
    /** Concatenation of all words, in ascii, in Term enumeration order, */
    private final String ascii;
    /** Starting indexes of words in the ascii String */
    private final int[] starts;
    /** formId known by formDic in the ascii String order */
    private final int[] formIds;
    /** “word count” () dictionary size */
    private final int wc;
    
    /**
     * Build an index of words optimized for wildcard searching
     * @return
     */
    public WordSuggest (final FieldText fieldText)
    {
        this.fieldText = fieldText;
        this.fieldName = fieldText.name;
        this.reader = fieldText.reader;
        this.formDic = fieldText.formDic;
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
     * Find list of formId in the dictionary of a field.
     * @param q
     * @param count
     * @return
     */
    public int[] list(String q, final TagFilter wordFilter) {
        boolean hasTags = (wordFilter != null && wordFilter.cardinality() > 0);
        boolean noStop = (wordFilter != null && wordFilter.nostop());
        boolean locs = (wordFilter != null && wordFilter.locutions());

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
                if (!fieldText.formLoc.get(formId)) continue;
            }
            else if (hasTags) {
                if (!wordFilter.accept(fieldText.formTag[formId])) continue;
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
     * @param word ex: "Lœs"
     * @param q ex "_OE"
     * @return "L<mark>œ</mark>s"
     */
    static public String mark(final String word, final String q)
    {
        final String qNorm = Char.toASCII(q, true).toLowerCase();
        final int qLen = qNorm.length();
        final String ascii = Char.toASCII(word);
        final int asciiLen = ascii.length();
        final String jok = "£";
        boolean ligature = (asciiLen != word.length());
        final String s = (ligature)?word.replaceAll("([æÆᴁﬀﬁﬂĳĲœᴔŒɶﬆ])", "$1" + jok):word;
        final int markStart = ascii.indexOf(qNorm);
        String marked = 
            s.substring(0, markStart)
            + "<mark>"
            + s.substring(markStart, markStart + qLen)
            + "</mark>"
            + s.substring(markStart + qLen)
        ;
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
     * 
     * @param q
     * @param count
     * @throws IOException
     */
    public Suggestion[] search(
        final String q, 
        final int count, 
        final TagFilter wordFilter, 
        final BitSet docFilter
    ) throws IOException
    {
        final int[] formIds = list(q, wordFilter);
        final int formLen = formIds.length;
        int[] formHits = new int[formLen];
        // formId in TermsEnum are faster than shuffled
        BytesRef bytes = new BytesRef();
        PostingsEnum docsEnum = null;
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
                docsEnum = tenum.postings(docsEnum, PostingsEnum.NONE);
                int docLeaf;
                while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    int docId = docBase + docLeaf;
                    if (docFilter != null && !docFilter.get(docId)) continue; // document not in the filter
                    // freq always > 0
                    formHits[i]++;
                }
            }
        }
        // Select the top best terms
        TopArray top = new TopArray(count);
        for (int i = 0; i < formLen; i++) {
            final int hits = formHits[i];
            if (hits == 0) continue;
            top.push(formIds[i], hits);
        }
        // unroll the top to build the result array
        ArrayList<Suggestion> list = new ArrayList<>();
        for (TopArray.IdScore pair: top) {
            final int formId = pair.id();
            final int hits = (int)pair.score();
            formDic.get(formId, bytes);
            final String word = bytes.utf8ToString();
            final String marked = mark(word, q);
            list.add(new Suggestion(word, hits, marked));
        }
        return list.toArray(new Suggestion[0]);
    }

    /**
     * Content of a suggested word.
     */
    class Suggestion
    {
        private final String word;
        private final int hits;
        private final String marked;
        public Suggestion(final String word, final int hits, final String marked) {
            this.word = word;
            this.hits = hits;
            this.marked = marked;
        }
        public String word() {
            return word;
        }
        public int hits() {
            return hits;
        }
        public String marked() {
            return marked;
        }
        @Override
        public String toString()
        {
            return word + " (" + hits + ") " + marked;
        }
    }

    
    @Override
    public String toString()
    {
        return ascii + "\n" + Arrays.toString(formIds);
    }
}
