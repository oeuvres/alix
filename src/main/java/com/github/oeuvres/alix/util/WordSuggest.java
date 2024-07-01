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
package com.github.oeuvres.alix.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Build an efficient suggestion of words in a relevancy order.
 * 
 * Data structure
 * <li>Input: String[] words = {"Loïc", "Chloé", "Éric"…}</li>
 * <li>ASCII search: String search = "_loic_chloe_eric_…"</li>
 * <li>Word indexs: int[] starts = [0, 5, 11, 16…]</li>
 * 
 * Ask for "LÔ", answer {"<mark>Lo</mark>ïc", "Ch<mark>lo</mark>é"} in original order.
 */
public class WordSuggest
{
    final String[] words;
    final String search;
    final int[] starts;
    final int wc;
    
    public WordSuggest(final String[] words)
    {
        this.words = words;
        StringBuilder sb = new StringBuilder().append("_");
        final int len = words.length;
        starts = new int[len];
        int start = 0;
        for (int i =0; i < len; i++) {
            final String ascii = Char.toASCII(words[i]).toLowerCase();
            sb.append(ascii).append("_");
            starts[i] = start;
            start += ascii.length() + 1; 
        }
        wc = words.length;
        search = sb.toString();
    }
    
    public String[][] search(String q, int count) {
        q = Char.toASCII(q).toLowerCase();
        List<String[]> list = new ArrayList<>(count);
        int fromIndex = 0;
        do {
            if (--count < 0) break;
            final int index = search.indexOf(q, fromIndex);
            if (index < 0) break;
            int found = Arrays.binarySearch(starts, index);
            if (found < 0) found = Math.abs(found) - 2;
            final String[] row = new String[2];
            String word = words[found];
            row[0] = word;
            final int asciiLen = ((found == wc - 1)?search.length():starts[found + 1]) - starts[found] - 1;
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
            if (found == wc - 1) break;
            // search starting next word
            fromIndex = starts[found + 1];
        } while(true);
        return list.toArray(new String[0][0]);
    }
}
