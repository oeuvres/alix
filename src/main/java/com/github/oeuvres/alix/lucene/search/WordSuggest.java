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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.IntList;

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
    /** Forms of a field, by reference */
    final BytesRefHash formDic;
    /** Concatenation of all words, in ascii, in Term enumeration order, */
    final String ascii;
    /** Starting indexes of words in the ascii String */
    final int[] starts;
    /** formId known by formDic in the ascii String order */
    final int[] formIds;
    
    final int wc;
    
    /**
     * Build an index of words optimized for wildcard searching
     * @return
     */
    public WordSuggest (final BytesRefHash formDic)
    {
        this.formDic = formDic;
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
     * Find list of formId in the dictionary
     * @param q
     * @param count
     * @return
     */
    public int[] search(String q) {
        IntList formIdList = new IntList();
        q = Char.toASCII(q).toLowerCase();
        int fromIndex = 0;
        do {
            final int index = ascii.indexOf(q, fromIndex);
            if (index < 0) break;
            int found = Arrays.binarySearch(starts, index);
            if (found < 0) found = Math.abs(found) - 2;
            formIdList.push(formIds[found]);
            // end of list
            if (found == wc - 1) break;
            // search starting next word
            fromIndex = starts[found + 1];
        } while(true);
        return formIdList.toArray();
    }
    
    /*
    static public void mark(final String ascii, final String q, final String word) {
        final int asciiLen = ascii.length();
        final String jok = "£";
        boolean ligature = asciiLen != word.length();
        if (ligature) {
            // add an empty char after double ligatures
            word = word.replaceAll("([æÆᴁﬀﬁﬂĳĲœᴔŒɶﬆ])", "$1" + jok);
        }
        final int markStart = index - starts[found] - ((q.charAt(0)== '_')?0:1);
        final int qLen = q.replaceAll("_", "").length();
        String marked = 
            word.substring(0, markStart)
            + "<mark>"
            + word.substring(markStart, markStart + qLen)
            + "</mark>"
            + word.substring(markStart + qLen)
        ;
        if (ligature) {
            marked = marked.replaceAll(jok, "");
        }
        row[1] = marked;
        list.add(row);
    }
    */
    
    private class BytesId implements Comparable<BytesId> {
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
    
    @Override
    public String toString()
    {
        return ascii + "\n" + Arrays.toString(formIds);
    }
}
