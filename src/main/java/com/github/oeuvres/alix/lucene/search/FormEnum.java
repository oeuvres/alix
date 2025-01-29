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

import java.io.IOException;
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

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.MI;
import com.github.oeuvres.alix.util.TopArray;

/**
 * This object is outputed by an Alix field {@link FieldCharsAbstract}, to provide
 * list of terms with stats, for example for queries. according to filters or queries; calculated by th 
 * 
 */
public class FormEnum implements FormIterator
{
    /** Count of forms with freq &gt; 0 */
    private int cardinality = -1;
    /** Field dictionary {@link FieldCharsAbstract#dic}. */
    private final BytesRefHash dic;
    /** docId4match.get(docId) == true: doc matched. */
    protected BitSet docId4match;
    /** Σ docsByForm; global count of docs relevant for this field {@link FieldCharsAbstract#docsAll}. */
    private final int docsAll;
    /** Alix field with chars and dic of terms. */
    private final FieldCharsAbstract field;
    /** Current formId, set by next */
    private int formId = -1;
    /** formId4docs[formId] = docs; count of docs by form {@link FieldCharsAbstract#formId4docs}. */
    protected final int[] formId4docs;
    /** formId4freq[formId] = freq; count of occurrences for this form in documents found (hits). */
    protected long[] formId4freq;
    /** formId4hits[formId] = hits; count of docs matched or selected by form. */
    protected int[] formId4hits;
    /** formId4occs[formId] = occs; count of occurrences by form {@link FieldText#formId4occs}. */
    protected long[] formId4occs;
    /** Occurrences found, Σ freq4form */
    protected long freqAll;
    /** Count of unique documents found. */
    protected int hitsAll;
    /** Limit for this iterator */
    private int limit = -1;
    /** Number of different values found, is also biggest valueId+1 see {@link IndexReader#maxDoc()}. */
    private final int maxForm;
    /** Σ occsByForm */
    protected long occsAll;
    
    /** Cursor, to iterate in the sorter */
    private int rank = -1;
    /** scoreByform[formId]=score; a relevance score calculated by form. */
    protected double[] scoreByForm;
    
    /** sorter[rank]Array of formId in order to iterate on, to set before iteration */
    private int[] sorter;
    /**
     * Build a form enumerator from an alix {@link FieldText}, sharing properties 
     * useful to calcultate scores for some queries : 
     * <ul>
     *   <li>Dictionary of forms {@link FieldCharsAbstract#dic}</li>
     *   <li>docsByform[formId] = docs; count of docs by form {@link FieldCharsAbstract#formId4docs}.</li>
     *   <li>Σ docsByForm; global count of docs relevant for this field {@link FieldCharsAbstract#docsAll}.</li>
     *   <li>occsByform[formId] = occs; count of occurrences by form {@link FieldText#formId4occs}.</li>
     *   <li>Σ occsByForm; global count of occs for this field {@link FieldText#occsAll}.</li>
     * </ul>
     * 
     * @param field alix stats on an indexed and tokenized lucene field.
     */
    public FormEnum(final FieldText field)
    {
        this.field = field;
        dic = field.dic;
        maxForm = dic.size();
        formId4docs = field.formId4docs;
        docsAll = field.docsAll;
        formId4occs = field.formId4occs;
        occsAll = field.occsAll;
    }
    
    /**
     * Build a form enumerator from an alix {@link FieldFacet}, sharing properties 
     * useful to calcultate scores for some queries : 
     * <ul>
     *   <li>Dictionary of forms {@link FieldCharsAbstract#dic}</li>
     *   <li>docsByform[formId] = docs; count of docs by form {@link FieldCharsAbstract#formId4docs}.</li>
     *   <li>Σ docsByForm; global count of docs relevant for this field {@link FieldCharsAbstract#docsAll}.</li>
     * </ul>
     * 
     * @param field alix stats on an indexed and not tokenized lucene field.
     */
    public FormEnum(final FieldFacet field) {
        this.field = field;
        dic = field.dic;
        maxForm = dic.size();
        docsAll = field.docsAll;
        formId4docs = field.formId4docs;
    }
    
    /**
     * Count of forms with freq &gt; 0, imited from {@link java.util.BitSet#cardinality()}.
     * 
     * @return forms with 1 ore more occurrence.
     */
    public int cardinality()
    {
        if (formId4freq == null && formId4hits == null) {
            return cardinality;
            // throw new RuntimeException("This dictionary has all terms of the field " +
            // name +", without formFreq nore formHits, cardinality() is not relevant, =
            // size().");
        }
        if (cardinality >= 0)
            return cardinality;
        cardinality = 0;
        if (formId4freq != null) {
            for (long freq : formId4freq) {
                if (freq > 0)
                    cardinality++;
            }
        } else if (formId4hits != null) {
            for (int hits : formId4hits) {
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
        if (formId4docs == null)
            return 0;
        return formId4docs[formId];
    }

    /**
     * For the requested formId, 
     * get document count containing this form.
     * 
     * @param formId a form Id.
     * @return docs for this form.
     */
    public int docs(final int formId)
    {
        if (formId4docs == null)
            return 0;
        return formId4docs[formId];
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
     * For the form requested by rank in sort order,
     * get document count containing this form.
     * 
     * @param rank in order of last sort.
     * @return docs for the form at this rank.
     */
    public int docsByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return formId4docs[formId];
    }
    
    /**
     * Get the field from wich this term enumerator is build on.
     * @return stats about a lucene field.
     */
    public FieldCharsAbstract field()
    {
        return field;
    }
    

    /**
     * Filter forms. For excluded form, unset freq, hits and score if exists.
     * 
     * @param formFilter form filter.
     * @return this.
     */
    public FormEnum filter(final TagFilter formFilter)
    {
        if (!(field instanceof FieldText)) {
            throw new UnsupportedOperationException("Field " + field.fieldName + " is not instanceof FieldText, filter form is not possible " + field);
        }
        FieldText fieldText = (FieldText)field;
        boolean noStop = (formFilter != null && formFilter.get(Tag.NOSTOP));
        boolean locs = (formFilter != null && formFilter.get(Tag.LOC));
        boolean hasTags = (formFilter != null && (formFilter.cardinality(null, TagFilter.NOSTOP_LOC) > 0));

        boolean hasFreq = (formId4freq != null);
        boolean hasHits = (formId4hits != null);
        boolean hasScore = (scoreByForm != null);

        if (!hasFreq) {
            formId4freq = new long[maxForm];
            freqAll = 0;
        }
        for (int formId = 0; formId < maxForm; formId++) {
            boolean unset = false;
            if (noStop) { // special tag
                if(fieldText.isStop(formId)) unset = true;
            }
            else if (locs) {  // special tag
                if(!fieldText.isLocution(formId)) unset = true;
            }
            // use tags even if no stop is set, allow to filter unknown or null
            if (hasTags) {
                if(!formFilter.get(fieldText.formId4flag[formId])) unset = true;
            }
            // if no freq, give occs for the form
            if (!hasFreq) {
                if (!unset) {
                    formId4freq[formId] = formId4occs[formId];
                    freqAll += formId4occs[formId];
                }
                continue;
            }
            // form to unset
            if (unset) {
                if (hasFreq) formId4freq[formId] = 0;
                if (hasHits) formId4hits[formId] = 0;
                if (hasScore) scoreByForm[formId] = 0;
            }
        }
        return this;
    }
    
    /**
     * For current form in sort order, get its chars as a String.
     * 
     * @return current chars.
     */
    public String form()
    {
        final BytesRef bytes = new BytesRef();
        dic.get(formId, bytes);
        return bytes.utf8ToString();
    }

    /**
     * For the form at this rank in sort order, returns the form as a String.
     * 
     * @param rank in order of last sort.
     * @return form at rank.
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
     * For current form in sort order, get its formId.
     *
     * @return current formId.
     */
    public int formId()
    {
        return formId;
    }

    /**
     * For current form in sort order, get its chars
     * with reusable bytes (no return, value populate the bytes).
     * 
     * @param bytes reusable bytes to populate with current form.
     */
    public void form(BytesRef bytes)
    {
        dic.get(formId, bytes);
    }


    /**
     * For current form in sort order, copy the chars
     * in a reusable mutable CharSequence.
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
     * If a search has set freq by form,
     * for the current form in sort order,
     * get the count of matching occurrences.
     * 
     * @return current freq.
     */
    public long freq()
    {
        if (formId4freq == null)
            return 0;
        return formId4freq[formId];
    }

    /**
     * If a search has set freq by form,
     * for the form id,
     * get the count of matching occurrences .
     * 
     * @param formId a form id.
     * @return freq for this form.
     */
    public long freq(final int formId)
    {
        if (formId4freq == null)
            return 0;
        return formId4freq[formId];
    }

    /**
     * If a search has set freq by form,
     * get global count of matching occurrences
     * 
     * @return Σ freq by form.
     */
    public long freqAll()
    {
        return freqAll;
    }

    /**
     * For the form requested by rank in sort order,
     * get count of matching occurrences.
     * 
     * @param rank in order of last sort.
     * @return freq for the form at this rank.
     */
    public long freqByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return formId4freq[formId];
    }

    @Override
    public boolean hasNext()
    {
        return (rank < limit - 1);
    }

    /**
     * If a search has set hits by form,
     * for the current form in sort order,
     * get the count of matching documents.
     * 
     * @return hits for current form.
     */
    public int hits()
    {
        if (formId4hits == null)
            return 0;
        return formId4hits[formId];
    }

    /**
     * If a search has set hits by form,
     * for the form requested by id,
     * get the count of matching documents.
     * 
     * @param formId a form id.
     * @return hits for this form.
     */
    public int hits(final int formId)
    {
        if (formId4hits == null)
            return 0;
        return formId4hits[formId];
    }

    /**
     * If a search has set hits by form,
     * total count of unique documents found.
     * 
     * @return count of unique documents hit.
     */
    public int hitsAll()
    {
        return hitsAll;
    }

    /**
     * If a search has set hits by form,
     * for the form by rank in sort order,
     * get the count of matching documents.
     * 
     * @param rank of a form.
     * @return hits for this form.
     */
    public int hitsByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return formId4hits[formId];
    }

    /**
     * Set enumerator to last element.
     */
    public void last()
    {
        rank = sorter.length - 1;
        formId = sorter[rank];
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
        rank++;
        formId = sorter[rank];
        return formId;
    }

    /**
     * For the current form in sort order,
     * get the global count of occurrences.
     * 
     * @return global count of occurrences for current form.
     */
    public long occs()
    {
        if (formId4occs == null)
            return 0;
        return formId4occs[formId];
    }

    /**
     * For the form requested by id,
     * get the global count of occurrences.
     * 
     * @param formId a form id.
     * @return occs for this form.
     */
    public long occs(final int formId)
    {
        if (formId4occs == null)
            return 0;
        return formId4occs[formId];
    }

    /**
     * Global count of occurrences for this field.
     * 
     * @return Σ occsByForm.
     */
    public long occsAll()
    {
        return occsAll;
    }

    /**
     * For the form requested by rank in sort order,
     * get the global count of occurrences.
     * 
     * @param rank in order of last sort.
     * @return occs for the form at this rank.
     */
    public long occsByRank(final int rank)
    {
        if (rank >= limit) {
            return -1;
        }
        final int formId = sorter[rank];
        return formId4occs[formId];
    }

    @Override
    public void reset()
    {
        if (sorter == null) {
            // natural order, let’s see
            this.sorter(IntStream.range(0, maxForm).toArray());
            // throw new NegativeArraySizeException("No order rule to sort on. Use FormEnum.sort() before");
        }
        this.limit = sorter.length;
        rank = -1;
        formId = -1;
    }

    /**
     * For the current form in sort order,
     * get the last calculated score.
     * 
     * @return score for current form.
     */
    public double score()
    {
        if (scoreByForm == null)
            return 0;
        return scoreByForm[formId];
    }

    /**
     * For the form requested by id,
     * get the last calculated score.
     * 
     * @param formId a form id.
     * @return score for this form id.
     */
    public double score(final int formId)
    {
        // be nice or be null ?
        if (scoreByForm == null)
            return 0;
        return scoreByForm[formId];
    }
    
    /**
     * Scores a {@link FormEnum} with freqs extracted from co-occurrences extraction
     * in a {@link FieldRail#coocs(int[], int, int, BitSet)}. Scoring uses a “mutual information”
     * {@link MI} formula (probability like, not tf-idf like). Parameters
     * are
     * 
     * <ul>
     * <li>Oab: freq(a), count of a form (a) observed in a co-occurrency context of a pivot (b)</li>
     * <li>Oa: occs(a), count of a form in full corpus</li>
     * <li>Ob: occs(b), sum of occs of the pivots</li>
     * <li>N: occsAll(), global count of occs from which is extracted the context (full corpus
     * or filtered section)</li>
     * </ul>
     * 
     * @param mi mutual information algorithm to calculate score.
     * @param pivotIds form ids of pivots words.
     * @return this
     * @throws IOException Lucene errors.
     */
    public FormEnum score(final MI mi, final int[] pivotIds) throws IOException
    {
        if (formId4freq == null || formId4freq.length < maxForm) {
            throw new IllegalArgumentException("Scoring this FormEnum required a freqList, set FormEnum.freqs");
        }
        final long N = occsAll; // global
        // Count of pivot occurrences for MI scorer
        long Ob = 0;
        for (int formId : pivotIds) {
            Ob += occs(formId);
        }
        scoreByForm = new double[maxForm];
        for (int formId = 0; formId < maxForm; formId++) {
            long Oab = formId4freq[formId];
            if (Oab == 0) {
                continue;
            }
            // a form in a cooccurrence, may be more frequent than the pivots (repetition in
            // a large context)
            // this will confuse common algorithms
            if (Oab > Ob) {
                Oab = Ob;
            }
            final long Oa = occs(formId);
            scoreByForm[formId] = mi.score(Oab, Oa, Ob, N);
        }
        return this;
    }


    /**
     * Score all forms with a distribution formula, 
     * 
     * @param scorer a score algo implementation.
     */
    public void score(Distrib scorer)
    {
        if (formId4freq == null) {
            throw new IllegalArgumentException("No freqs for this dictionary to calculate score on.");
        }
        scoreByForm = new double[maxForm];
        for (int formId = 0; formId < maxForm; formId++) {
            if (formId4freq[formId] < 1)
                continue;
            scorer.idf(formId4docs[formId], docsAll, occsAll);
            scorer.expectation(formId4occs[formId], occsAll);
            scoreByForm[formId] = scorer.score(formId4freq[formId], freqAll); // freq = docLen, all found occs supposed as one
                                                                       // doc
            // ?
            // formScore[formId] = distrib.last(formOccs[formId] - formFreq[formId], freq);
        }
    }

    /**
     * For the form requested by rank in sort order,
     * get count of matching occurrences.
     * 
     * @param rank in order of last sort.
     * @return score for the form at this rank.
     */
    public double scoreByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return scoreByForm[formId];
    }

    @Override
    public int size()
    {
        return dic.size();
    }

    @Override
    public FormEnum sort(final Order order)
    {
        return sort(order, -1, false);
    }

    @Override
    public FormEnum sort(final Order order, final int limit)
    {
        return sort(order, limit, false);
    }

    @Override
    public FormEnum sort(final Order order, final int limit, final boolean reverse)
    {
        if (formId4freq != null && maxForm != formId4freq.length) {
            throw new IllegalArgumentException(
                "Corrupted FormEnum maxForm=" + maxForm
                + " formOccsFreq.length=" + formId4freq.length
            );
        }
        if (order == null) {
            reset();
            return this;
        }
        switch (order) {
        case OCCS:
            if (formId4occs == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by occs (occurrences total), occsByForm is not set."
                );
            }
            break;
        case DOCS:
            if (formId4docs == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by docs (documents total), docsByForm is not set."
                );
            }
            break;
        case FREQ:
            if (formId4freq == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by freq (occurrences found), seems not results of a search, freqByForm is not set."
                );
            }
            break;
        case HITS:
            if (formId4hits == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by hits (documents found), seems not results of a search, hitsByForm is not set."
                );
            }
            break;
        case SCORE:
            if (scoreByForm == null) {
                throw new IllegalArgumentException(
                    "Impossible to sort by score, seems not results of a search with a scorer, scoreByform is not set."
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
            top = new TopArray(maxForm, flags);
        else
            top = new TopArray(limit, flags);
        // boolean noZeroScore = false;
        if (scoreByForm != null && order != Order.SCORE) {
            // noZeroScore = true;
        }
        // be careful, do not use formOccsAll as a size, the growing array may be bigger
        // than dictionary
        if (order.equals(Order.ALPHA)) {
            int[] sorter = sortAlpha(dic);
            this.sorter(sorter);
            reset();
            return this;
        }
        // cardinality = 0;
        for (int formId = 0, length = maxForm; formId < length; formId++) {
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
                top.push(formId, formId4occs[formId]);
                break;
            case DOCS:
                top.push(formId, formId4docs[formId]);
                break;
            case FREQ:
                top.push(formId, formId4freq[formId]);
                break;
            case HITS:
                top.push(formId, formId4hits[formId]);
                break;
            case SCORE: // in case of negative scores ?
                if (formId4freq[formId] == 0) {
                    top.push(formId, Double.NEGATIVE_INFINITY);
                    scoreByForm[formId] = Double.NEGATIVE_INFINITY;
                    break;
                }
                top.push(formId, scoreByForm[formId]);
                break;
            default:
                top.push(formId, formId4occs[formId]);
                break;
            }
            // to test, do not work yet
            // else top.push(sortAlpha(this.formDic));
        }
        int[] sorter = top.toArray();
        this.sorter(sorter);
        reset();
        return this;
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
        StringBuilder sb = new StringBuilder();
        sb.append("size=" + dic.size() + "\n");
        if (sorter == null) {
            sb.append("No sorter, limit=" + limit + "\n");
            sorter = new int[limit];
            for (int i = 0; i < limit; i++)
                sorter[i] = i;
        }
        int limit = Math.min(Math.min(dic.size(), 100), sorter.length);
        boolean hasScore = (scoreByForm != null);
        boolean hasHits = (formId4hits != null);
        boolean hasDocs = (formId4docs != null);
        boolean hasOccs = (formId4occs != null);
        boolean hasFreq = (formId4freq != null);
        for (int pos = 0; pos < limit; pos++) {
            int formId = sorter[pos];
            dic.get(formId, bytes);
            sb.append((pos + 1) + ". [" + formId + "] " + bytes.utf8ToString());
            if (hasScore) {
                sb.append(" score=" + scoreByForm[formId]);
            }

            if (hasOccs && hasFreq) {
                sb.append(" freq=" + formId4freq[formId] + "/" + formId4occs[formId]);
            }
            else if (hasOccs) {
                sb.append(" freq=" + formId4occs[formId]);
            }
            if (hasHits && hasDocs)
                sb.append(" hits=" + formId4hits[formId] + "/" + formId4docs[formId]);
            else if (hasDocs)
                sb.append(" docs=" + formId4docs[formId]);
            else if (hasHits)
                sb.append(" hits=" + formId4hits[formId]);
            sb.append("\n");
        }
        return sb.toString();
    }

}
