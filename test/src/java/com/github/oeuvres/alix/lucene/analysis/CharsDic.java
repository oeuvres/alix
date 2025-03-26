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
package com.github.oeuvres.alix.lucene.analysis;

import java.util.Arrays;
import java.util.HashMap;

import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

/**
 * A dictionary optimized for lucene analysis using {@link CharsAttImpl} as key.
 */
public class CharsDic
{
    private HashMap<CharsAttImpl, Entry> dic = new HashMap<CharsAttImpl, Entry>();

    /**
     * Increment a token found.
     * 
     * @param key a char sequence.
     * @return counter state.
     */
    public int inc(final CharsAttImpl key)
    {
        return inc(key, 0);
    }

    /**
     * Increment a token, same char sequence may be differentiate by an int tag.
     * 
     * @param key a char sequence.
     * @param tag an int key for homonyms.
     * @return counter state.
     */
    public int inc(final CharsAttImpl key, final int tag)
    {
        Entry entry = dic.get(key);
        if (entry == null) {
            CharsAttImpl keyNew = new CharsAttImpl(key);
            entry = new Entry(keyNew, tag);
            dic.put(keyNew, entry);
        }
        return ++entry.count;
    }

    /**
     * Entry to increment.
     */
    public class Entry implements Comparable<Entry>
    {
        /** Internal counter */
        private int count;
        /** Char sequence */
        private final CharsAttImpl key;
        /** Optional tag */
        private final int tag;

        /**
         * Build an entry.
         * @param key char sequence.
         * @param tag optional precision on key.
         */
        public Entry(final CharsAttImpl key, final int tag) {
            this.key = key;
            this.tag = tag;
        }

        /**
         * @return the key.
         */
        public CharsAttImpl key()
        {
            return key;
        }

        /**
         * @return optional tag.
         */
        public int tag()
        {
            return tag;
        }

        /**
         * @return internal counter.
         */
        public int count()
        {
            return count;
        }

        /**
         * Default comparator for chain informations,
         */
        @Override
        public int compareTo(Entry o)
        {
            return o.count - count;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(key);
            if (tag > 0)
                sb.append(" ").append(TagFr.name(tag)).append(" ");
            sb.append(" (").append(count).append(")");
            return sb.toString();
        }
    }

    /**
     * Sort entries and return them.
     * @return sorted entries.
     */
    public Entry[] sorted()
    {
        Entry[] entries = new Entry[dic.size()];
        dic.values().toArray(entries);
        Arrays.sort(entries);
        return entries;
    }
}
