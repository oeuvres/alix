/*
 * Alix, A Lucene Indexer for XML documents.
 * 
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
package com.github.oeuvres.alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.TopArray;
import com.github.oeuvres.alix.web.OptionDistrib;

/**
 * This object is build to collect list of forms with useful stats for semantic
 * interpretations and score calculations. This class is a wrapper around
 * different pre-calculated arrays, and is also used to record search specific
 * counts like freqs and hits.
 */
public class FormEnum implements FormIterator
{
    /** Field dictionary. */
    protected final BytesRefHash dic;
    /** Number of different values found, is also biggest valueId+1 see {@link IndexReader#maxDoc()}. */
    protected final int maxValue;
    /** Global number of docs of a field. */
    protected final int docsAll;
    /** By formId, count of docs, for all index. */
    protected final int[] docs4form;
    /** Global number of occurrences for this field. */
    protected long occsAll;
    /** By formId, count of occurrences */
    protected long[] occs4form;
    
    /** Document found, Σ formHits */
    protected int hitsAll;
    /** By formId, count of docs, matched in a text search */
    protected int[] hits4form;
    /** Occurrences found, Σ freq4form */
    protected long freqAll;
    /** By formId, count of occurrences, matched or selected */
    protected long[] freq4form;
    /** By formId, a relevance score calculated */
    protected double[] score4form;
    
    /** Array of formId in order to iterate on, to set before iteration */
    private int[] sorter;
    /** Cursor, to iterate in the sorter */
    private int cursor = -1;
    /** Limit for this iterator */
    private int limit = -1;
    /** Current formId, set by next */
    private int formId = -1;

    /** Count of forms with freq &gt; 0 */
    private int cardinality = -1;
    /** By formId, count of docs, for a partition */
    protected int[] formDocsPart;
    /** By formId, count of occurrences, on a partition */
    protected long[] formOccsPart;
    
    /** Vector of documents hits */
    protected BitSet hitsVek;
    /** Count of occurrences for the part explored */
    public long occsPart;
    /** Optional, for a co-occurrence search, pivot words */
    public String[] search;

    /**
     * Build a form enumerator with required field.
     * 
     * @param dic dictionary of forms {@link AbstractFieldChars#dic}
     * @param docs  {@link AbstractFieldChars#docsAll}
     * @param docsByForm {@link AbstractFieldChars#docsByform}
     * @param occs {@link AbstractFieldChars#occsAll}
     * @param occsByForm long[formId] = occs.
     */
    public FormEnum(
        final BytesRefHash dic,
        final int docs,
        final int[] docsByForm,
        final long occs,
        final long[] occsByForm
    ) {
        this.dic = dic;
        this.docsAll = docs;
        this.docs4form = docsByForm;
        this.occsAll = occs;
        this.occs4form = occsByForm;
        this.maxValue = dic.size();
    }
    
    /**
     * Count of forms with freq &gt; 0, imited from {@link java.util.BitSet#cardinality()}.
     * 
     * @return forms with 1 ore more occurrence.
     */
    public int cardinality()
    {
        if (freq4form == null && hits4form == null) {
            return cardinality;
            // throw new RuntimeException("This dictionary has all terms of the field " +
            // name +", without formFreq nore formHits, cardinality() is not relevant, =
            // size().");
        }
        if (cardinality >= 0)
            return cardinality;
        cardinality = 0;
        if (freq4form != null) {
            for (long freq : freq4form) {
                if (freq > 0)
                    cardinality++;
            }
        } else if (hits4form != null) {
            for (int hits : hits4form) {
                if (hits > 0)
                    cardinality++;
            }
        }
        return cardinality;
    }

    /**
     * For the current formId in iterator, returns the total count of documents in
     * corpus containing this form.
     * 
     * @return docs for current form.
     */
    public int docs()
    {
        if (docs4form == null)
            return 0;
        return docs4form[formId];
    }

    /**
     * For the provided formId, returns the total count of documents in
     * corpus containing this form.
     * 
     * @param formId a form Id.
     * @return docs for this form.
     */
    public int docs(final int formId)
    {
        if (docs4form == null)
            return 0;
        return docs4form[formId];
    }

    /**
     * Global count of docs concerned by this field.
     * 
     * @return docs.
     */
    public int docsAll()
    {
        return docsAll;
    }

    /**
     * By rank in sort order, returns total count of documents in corpus
     * 
     * @return
     */
    public int docsByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return docs4form[formId];
    }

    /**
     * For current form, document count in a part
     * 
     * @return
     */
    public long docsPart()
    {
        return formDocsPart[formId];
    }

    /**
     * For requested form, amount of documents for a part.
     * 
     * @return 
     */
    public long docsPart(final int formId)
    {
        return formDocsPart[formId];
    }

    /**
     * Get the current term as a String
     * 
     * @return
     */
    public String form()
    {
        final BytesRef bytes = new BytesRef();
        dic.get(formId, bytes);
        return bytes.utf8ToString();
    }

    /**
     * By rank in sort order, returns form
     * 
     * @param rank
     * @return
     */
    public String formByRank(final int rank)
    {
        final BytesRef bytes = new BytesRef();
        if (rank >= limit)
            return null;
        final int formId = sorter[rank];
        dic.get(formId, bytes);
        return bytes.utf8ToString();
    }

    /**
     * Current term, get the formId for the global dic.
     */
    public int formId()
    {
        return formId;
    }

    /**
     * Populate reusable bytes with current term
     * 
     * @param bytes
     */
    public void form(BytesRef bytes)
    {
        dic.get(formId, bytes);
    }

    /**
     * Copy the current term in a reusable char array.
     * 
     * @param term
     * @return
     */
    public CharsAttImpl form(CharsAttImpl term)
    {
        final BytesRef bytes = new BytesRef();
        dic.get(formId, bytes);
        // ensure limit of the char array
        int length = bytes.length;
        char[] chars = term.resizeBuffer(length);
        final int len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, length, chars);
        term.setLength(len);
        return term;
    }

    /**
     * Copy the current term in a reusable char array.
     * 
     * @param term reusable mutable CharSequence.
     */
    public void form(Chain term)
    {
        final BytesRef bytes = new BytesRef();
        dic.get(formId, bytes);
        term.reset().append(bytes);
    }

    /**
     * Get the count of matching occurrences.
     * 
     * @return
     */
    public long freq()
    {
        if (freq4form == null)
            return 0;
        return freq4form[formId];
    }

    /**
     * Get the count of matching occurrences
     * 
     * @param formId
     * @return
     */
    public long freq(final int formId)
    {
        if (freq4form == null)
            return 0;
        return freq4form[formId];
    }

    /**
     * Global count of matching occurrences
     * 
     * @return
     */
    public long freqAll()
    {
        return freqAll;
    }

    /**
     * By rank in sort order, returns count of matching occurrences
     * 
     * @param rank
     * @return
     */
    public long freqByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return freq4form[formId];
    }

    @Override
    public boolean hasNext()
    {
        return (cursor < limit - 1);
    }

    /**
     * Get the count of matched documents for the current term.
     * 
     * @return
     */
    public int hits()
    {
        if (hits4form == null)
            return 0;
        return hits4form[formId];
    }

    /**
     * Get the count of matched documents for the current term.
     * 
     * @return
     */
    public int hits(final int formId)
    {
        if (hits4form == null)
            return 0;
        return hits4form[formId];
    }

    /**
     * Total of document found for this freq list
     */
    public int hitsAll()
    {
        return hitsAll;
    }

    public int hitsByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return hits4form[formId];
    }

    public void last()
    {
        cursor = sorter.length - 1;
        formId = sorter[cursor];
    }

    @Override
    public int limit()
    {
        if (limit < 0) {
            limit = size();
        }
        return limit;
    }

    @Override
    public FormEnum limit(final int limit)
    {
        this.limit = limit;
        return this;
    }

    @Override
    public int next() throws NoSuchElementException
    {
        cursor++;
        formId = sorter[cursor];
        return formId;
    }

    /**
     * Current global number of occurrences for this term
     * 
     * @return
     */
    public long occs()
    {
        if (occs4form == null)
            return 0;
        return occs4form[formId];
    }

    /**
     * Global number of occurrences for this term
     * 
     * @return
     */
    public long occs(final int formId)
    {
        if (occs4form == null)
            return 0;
        return occs4form[formId];
    }

    /**
     * Current global number of occurrences for this term
     * 
     * @return
     */
    public long occsAll()
    {
        return occsAll;
    }

    /**
     * Global number of occurrences by rank of a term
     * 
     * @return
     */
    public long occsByRank(final int rank)
    {
        if (rank >= limit) {
            return -1;
        }
        final int formId = sorter[rank];
        return occs4form[formId];
    }

    /**
     * For current form, occurrence count in a part
     * 
     * @return
     */
    public long occsPart()
    {
        if (formOccsPart == null)
            return 0;
        return formOccsPart[formId];
    }

    /**
     * For requested form, occurrence count in a part
     * 
     * @return
     */
    public long occsPart(final int formId)
    {
        if (formOccsPart == null)
            return 0;
        return formOccsPart[formId];
    }

    @Override
    public void reset()
    {
        if (sorter == null) {
            // natural order, let’s see
            this.sorter(IntStream.range(0, maxValue).toArray());
            // throw new NegativeArraySizeException("No order rule to sort on. Use FormEnum.sort() before");
        }
        cursor = -1;
        formId = -1;
    }

    /**
     * Value used for sorting for current term.
     * 
     * @return
     */
    public double score()
    {
        if (score4form == null)
            return 0;
        return score4form[formId];
    }

    /**
     * Value used for sorting for current term.
     * 
     * @param formId
     * @return
     */
    public double score(final int formId)
    {
        // be nice or be null ?
        if (score4form == null)
            return 0;
        return score4form[formId];
    }

    /**
     * 
     * @param distrib
     */
    public void score(OptionDistrib distrib)
    {
        if (freq4form == null) {
            throw new IllegalArgumentException("No freqs for this dictionary to calculate score on.");
        }
        score4form = new double[maxValue];
        for (int formId = 0; formId < maxValue; formId++) {
            if (freq4form[formId] < 1)
                continue;
            distrib.idf(docs4form[formId], docsAll, occsAll);
            distrib.expectation(occs4form[formId], occsAll);
            score4form[formId] = distrib.score(freq4form[formId], freqAll); // freq = docLen, all found occs supposed as one
                                                                       // doc
            // ?
            // formScore[formId] = distrib.last(formOccs[formId] - formFreq[formId], freq);
        }
    }

    /**
     * 
     * @param rank
     * @return
     */
    public double scoreByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return score4form[formId];
    }

    @Override
    public int size()
    {
        return dic.size();
    }

    @Override
    public void sort(final Order order)
    {
        sort(order, -1, false);
    }

    @Override
    public void sort(final Order order, final int limit)
    {
        sort(order, limit, false);
    }

    /**
     * Prepare the order of enumeration with a vector.
     * 
     * @param order sort order accepted.
     * @param limit limit the number of forms returned.
     * @param reverse reverse order according to order.
     */
    public void sort(final Order order, final int limit, final boolean reverse)
    {
        this.limit = limit;
        if (freq4form != null && maxValue != freq4form.length) {
            throw new IllegalArgumentException("Corrupted FormEnum maxForm=" + maxValue
                    + " formOccsFreq.length=" + freq4form.length);
        }
        if (order == null) {
            reset();
            return;
        }
        switch (order) {
        case OCCS:
            if (occs4form == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by occs (occurrences total), formOccs has not been set by producer."
                );
            }
            break;
        case DOCS:
            if (docs4form == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort docs (documents total), formDocs has not been set by producer."
                );
            }
            break;
        case FREQ:
            if (freq4form == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by freq (occurrences found), seems not results of a search, formFreq has not been set by producer."
                );
            }
            break;
        case HITS:
            if (hits4form == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by hits (documents found), seems not results of a search, formHits has not been set by producer."
                );
            }
            break;
        case SCORE:
            if (score4form == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by score, seems not results of a search with a scorer, formScore has not been set by producer."
                );
            }
            break;
        case ALPHA:
            break;
        default:
            throw new IllegalArgumentException("Sort by " + order + " is not implemented here.");
        }

        // if (maxForm != formOccsFreq.length) throw new
        // IllegalArgumentException("Corrupted FormEnum name="+fieldName+"
        // maxForm="+maxForm+" formOccsFreq.length="+formOccsFreq.length);
        // int flags = TopArray.NO_ZERO; // ?
        int flags = 0;
        if (reverse)
            flags |= TopArray.REVERSE;
        TopArray top = null;
        if (limit < 1)
            top = new TopArray(maxValue, flags);
        else
            top = new TopArray(limit, flags);
        // boolean noZeroScore = false;
        if (score4form != null && order != Order.SCORE) {
            // noZeroScore = true;
        }
        // be careful, do not use formOccsAll as a size, the growing array may be bigger
        // than dictionary
        if (order.equals(Order.ALPHA)) {
            int[] sorter = sortAlpha(dic);
            this.sorter(sorter);
            reset();
            return;
        }
        // cardinality = 0;
        for (int formId = 0, length = maxValue; formId < length; formId++) {
            // 2022-05 ??? do not output global stats if form have been filtered (ex : by
            // cat)
            // check values > 0
            /*
             * if (formHits != null && formHits[formId] > 0) { cardinality++; } else if
             * (formFreq != null && formFreq[formId] > 0) { cardinality++; }
             */
            // do not output null score ?
            switch (order) {
            case OCCS:
                top.push(formId, occs4form[formId]);
                break;
            case DOCS:
                top.push(formId, docs4form[formId]);
                break;
            case FREQ:
                top.push(formId, freq4form[formId]);
                break;
            case HITS:
                top.push(formId, hits4form[formId]);
                break;
            case SCORE: // in case of negative scores ?
                if (freq4form[formId] == 0) {
                    top.push(formId, Double.NEGATIVE_INFINITY);
                    score4form[formId] = Double.NEGATIVE_INFINITY;
                    break;
                }
                top.push(formId, score4form[formId]);
                break;
            default:
                top.push(formId, occs4form[formId]);
                break;
            }
            // to test, do not work yet
            // else top.push(sortAlpha(this.formDic));
        }
        int[] sorter = top.toArray();
        this.sorter(sorter);
        reset();
    }

    /**
     * Returns an array of formId in alphabetic order for the tearm of the dictionary.
     *
     * @param dic a dictionary of bytes.
     * @return vector of valueId in syntaxic alphabetic order.
     */
    static public int[] sortAlpha(BytesRefHash dic)
    {
        Collator collator = Collator.getInstance(Locale.FRANCE);
        collator.setStrength(Collator.TERTIARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        int size = dic.size();
        BytesRef bytes = new BytesRef();
        class Entry
        {
            final CollationKey key;
            final int formId;

            Entry(final int formId, final CollationKey key) {
                this.key = key;
                this.formId = formId;
            }
        }
        Entry[] sorter = new Entry[size];
        for (int formId = 0; formId < size; formId++) {
            dic.get(formId, bytes);
            sorter[formId] = new Entry(formId, collator.getCollationKey(bytes.utf8ToString()));
        }
        Arrays.sort(sorter, new Comparator<Entry>() {
            @Override
            public int compare(Entry arg0, Entry arg1)
            {
                return arg0.key.compareTo(arg1.key);
            }
        });
        int[] terms = new int[size];
        for (int i = 0, max = size; i < max; i++) {
            terms[i] = sorter[i].formId;
        }
        return terms;
    }

    @Override
    public int[] sorter()
    {
        if (sorter == null)
            return null;
        return sorter.clone();
    }

    /**
     * Set the sorted vector of ids
     */
    private void sorter(final int[] sorter)
    {
        this.sorter = sorter;
        this.limit = sorter.length;
        reset();
    }

    @Override
    public String toString()
    {
        final BytesRef bytes = new BytesRef();
        int limit = Math.min(dic.size(), 100);
        StringBuilder sb = new StringBuilder();
        sb.append("size=" + dic.size() + "\n");
        if (sorter == null) {
            sb.append("No sorter, limit=" + limit + "\n");
            sorter = new int[limit];
            for (int i = 0; i < limit; i++)
                sorter[i] = i;
        }
        boolean hasScore = (score4form != null);
        boolean hasHits = (hits4form != null);
        boolean hasDocs = (docs4form != null);
        boolean hasOccs = (occs4form != null);
        boolean hasFreq = (freq4form != null);
        for (int pos = 0; pos < limit; pos++) {
            int formId = sorter[pos];
            dic.get(formId, bytes);
            sb.append((pos + 1) + ". [" + formId + "] " + bytes.utf8ToString());
            if (hasScore)
                sb.append(" score=" + score4form[formId]);

            if (hasOccs && hasFreq) {
                sb.append(" freq=" + freq4form[formId] + "/" + occs4form[formId]);
            } else if (hasOccs) {
                sb.append(" freq=" + occs4form[formId]);
            }
            if (hasHits && hasDocs)
                sb.append(" hits=" + hits4form[formId] + "/" + docs4form[formId]);
            else if (hasDocs)
                sb.append(" docs=" + docs4form[formId]);
            else if (hasHits)
                sb.append(" hits=" + hits4form[formId]);
            sb.append("\n");
        }
        return sb.toString();
    }

}
