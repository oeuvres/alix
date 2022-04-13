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
package alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.Chain;
import alix.util.EdgeQueue;
import alix.util.TopArray;
import alix.web.OptionDistrib.Scorer;
import alix.web.OptionMI;

/**
 * This object is build to collect list of forms with useful stats for semantic
 * interpretations and score calculations. This class is a wrapper around
 * different pre-calculated arrays, and is also used to record search specific
 * counts like freqs and hits.
 * 
 * @author glorieux-f
 */
public class FormEnum
{
    /** used to read in the dic */
    BytesRef bytes = new BytesRef();
    /** Cursor, to iterate in the sorter */
    private int cursor = -1;
    /** Global number of docs relevant for this facet */
    public int docs;
    /** A record of edges */
    protected EdgeQueue edges;
    /** Source field */
    public final String name;
    /** Optional, a set of documents to limit occurrences collect */
    public BitSet filter;
    /**  By formId, a docId used as a cover (example: metas for books or  authors) */
    protected int[] formCover;
    /** Field dictionary */
    BytesRefHash formDic;
    /** By formId, count of docs, for all base */
    protected int[] formDocs;
    /** By formId, count of docs, for a partition */
    protected int[] formDocsPart;
    /** By formId, count of docs, matched in a text search */
    protected int[] formHits;
    /** Current formId, set by next */
    private int formId = -1;
    /** By formId, a free int with no semantic, see FieldFace.nos() */
    protected int[] formNos;
    /** By formId, count of occurrences, on all base */
    protected long[] formOccs;
    /** By formId, count of occurrences, on a partition */
    protected long[] formOccsPart;
    /** By formId, count of occurrences, matched or selected */
    protected long[] formFreq;
    /** By formId, a relevance score calculated */
    protected double[] formScore;
    /** An optional tag for each search (relevant for textField) */
    protected int[] formTag;
    /** Occurrences found, Σ formFreq */
    protected long freq;
    /** Document found, Σ formHits */
    protected int hits;
    /** Optional, for a co-occurrence search, count of occurrences to capture on the left */
    public int left;
    /** Limit for this iterator */
    public int limit;
    /** Biggest formId+1 (like lucene IndexReader.maxDoc()) */
    public int maxForm;
    /** Optional, a sort algorithm for coocs */
    public OptionMI mi;
    /** All occs from parent field */
    public long occs;
    /** Count of occurrences for the part explored */
    public long occsPart;
    /** Reverse order of sorting */
    public boolean reverse;
    /** Optional, for a co-occurrence search, count of occurrences to capture on the  right */
    public int right;
    /**
     * Optional, a sort algorithm to select specific words according a norm (ex:
     * compare formOccs / freqs)
     */
    public Scorer scorer;
    /** Optional, for a co-occurrence search, pivot words */
    public String[] search;
    /** An array of formId in the order we want to iterate on, should be set before iteration */
    private int[] sorter;
    /** Optional, a set of tags to filter form to collect */
    public TagFilter tags;
    /** sort order */
    public enum Order
    {
        score,
        freq,
        hits,
        alpha,
        occs,
        docs,
    }

    protected FormEnum(final String fname)
    {
        this.name = fname;
    }
    


    /**
     * Cover docid for current term
     * 
     * @return
     */
    public int cover()
    {
        return formCover[formId];
    }

    /**
     * For the current term in iterator, returns the total count of documents in
     * corpus
     * 
     * @return
     */
    public int docs()
    {
        if (formDocs == null) return 0;
        return formDocs[formId];
    }

    /**
     * By formId, return total count
     * 
     * @return
     */
    public int docs(final int formId)
    {
        if (formDocs == null) return 0;
        return formDocs[formId];
    }
    
    /**
     * Global count of docs for field
     * @return
     */
    public int docsAll()
    {
        return docs;
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
        return formDocs[formId];
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
     * For requested form, document count in a part
     * 
     * @return
     */
    public long docsPart(final int formId)
    {
        return formDocsPart[formId];
    }

    /**
     * Prepare results to collect edges
     */
    public EdgeQueue edges()
    {
        if (this.edges == null) {
            this.edges = new EdgeQueue(false);
        }
        return this.edges;
    }
    
    public void first()
    {
        cursor = 0;
        formId = sorter[cursor];
    }

    /**
     * Get the current term as a String
     * 
     * @return
     */
    public String form()
    {
        formDic.get(formId, bytes);
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
        if (rank >= limit)
            return null;
        final int formId = sorter[rank];
        formDic.get(formId, bytes);
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
     * @param ref
     */
    public void form(BytesRef bytes)
    {
        formDic.get(formId, bytes);
    }

    /**
     * Copy the current term in a reusable char array *
     * 
     * @return
     */
    public CharsAtt form(CharsAtt term)
    {
        formDic.get(formId, bytes);
        // ensure limit of the char array
        int length = bytes.length;
        char[] chars = term.resizeBuffer(length);
        final int len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, length, chars);
        term.setLength(len);
        return term;
    }

    /**
     * Copy the current term in a reusable char array *
     * 
     * @return
     */
    public void form(Chain term)
    {
        formDic.get(formId, bytes);
        term.reset().append(bytes);
    }

    /**
     * Get the count of matching occurrences
     * 
     * @return
     */
    public long freq()
    {
        if (formFreq == null) return 0;
        return formFreq[formId];
    }

    /**
     * Get the count of matching occurrences
     * 
     * @param formId
     * @return
     */
    public long freq(final int formId)
    {
        if (formFreq == null) return 0;
        return formFreq[formId];
    }

    /**
     * Global count of matching occurrences
     * 
     * @return
     */
    public long freqAll()
    {
        return freq;
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
        return formFreq[formId];
    }

    /**
     * There are search left
     * 
     * @return
     */
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
        if (formHits == null) return 0;
        return formHits[formId];
    }

    /**
     * Get the count of matched documents for the current term.
     * 
     * @return
     */
    public int hits(final int formId)
    {
        if (formHits == null) return 0;
        return formHits[formId];
    }

    /**
     * Total of document found for this freq list 
     */
    public int hitsAll()
    {
        return hits;
    }

    public int hitsByRank(final int rank)
    {
        if (rank >= limit)
            return -1;
        final int formId = sorter[rank];
        return formHits[formId];
    }

    public void last()
    {
        cursor = sorter.length - 1;
        formId = sorter[cursor];
    }

    /**
     * Limit enumeration
     * 
     * @return
     */
    public int limit()
    {
        return limit;
    }

    /**
     * Advance the cursor to next element
     */
    public int next()
    {
        cursor++;
        formId = sorter[cursor];
        return formId;
    }

    /**
     * Current specific number
     * @return
     */
    public int no()
    {
        return formNos[formId];
    }

    /**
     * Current global number of occurrences for this term
     * 
     * @return
     */
    public long occs()
    {
        if (formOccs == null) return 0;
        return formOccs[formId];
    }
    
    /**
     * Global number of occurrences for this term
     * 
     * @return
     */
    public long occs(final int formId)
    {
        if (formOccs == null) return 0;
        return formOccs[formId];
    }
    
    /**
     * Current global number of occurrences for this term
     * 
     * @return
     */
    public long occsAll()
    {
        return occs;
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
        return formOccs[formId];
    }

    /**
     * Total of occurrences found for this freq list 
     */
    public long occsFreq()
    {
        return freq;
    }

    /**
     * For current form, occurrence count in a part
     * 
     * @return
     */
    public long occsPart()
    {
        if (formOccsPart == null) return 0;
        return formOccsPart[formId];
    }

    /**
     * For requested form, occurrence count in a part
     * 
     * @return
     */
    public long occsPart(final int formId)
    {
        if (formOccsPart == null) return 0;
        return formOccsPart[formId];
    }

    /**
     * Reset the internal cursor if we want to replay the list.
     */
    public void reset()
    {
        if (sorter == null) {
            throw new NegativeArraySizeException("No order rule to sort on. Use FormEnum.sort() before");
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
        if (formScore == null) return 0;
        return formScore[formId];
    }

    /**
     * Value used for sorting for current term.
     * 
     * @return
     */
    public double score(final int formId)
    {
        // be nice or be null ?
        if (formScore == null) return 0;
        return formScore[formId];
    }

    /**
     * Set the sorted vector of ids
     */
    public void setNos(final int[] formNos)
    {
        this.formNos = formNos;
    }

    public int[] sort(final Order order)
    {
        return sort(order, -1, false);
    }

    public int[] sort(final Order order, final int limit)
    {
        return sort(order, limit, false);
    }
    
    /**
     * Prepare the order of enumeration with a vector.
     * @throws Exception
     */
    public int[] sort(final Order order, final int limit, final boolean reverse)
    {
        if (formFreq != null && maxForm != formFreq.length) {
            throw new IllegalArgumentException("Corrupted FormEnum name=" + name + " maxForm=" + maxForm
                    + " formOccsFreq.length=" + formFreq.length);
        }
        switch (order) {
            case occs:
                if (formOccs == null) {
                    throw new IllegalArgumentException("Impossible to sort by occs (occurrences total), formOccs has not been set by producer.");
                }
                break;
            case docs:
                if (formDocs == null) {
                    throw new IllegalArgumentException("Impossible to sort docs (documents total), formDocs has not been set by producer.");
                }
                break;
            case freq:
                if (formFreq == null) {
                    throw new IllegalArgumentException("Impossible to sort by freq (occurrences found), seems not results of a search, formFreq has not been set by producer.");
                }
                break;
            case hits:
                if (formHits == null) {
                    throw new IllegalArgumentException("Impossible to sort by hits (documents found), seems not results of a search, formHits has not been set by producer.");
                }
                break;
            case score:
                if (formScore == null) {
                    throw new IllegalArgumentException("Impossible to sort by score, seems not results of a search with a scorer, formScore has not been set by producer.");
                }
                break;
        case alpha:
            break;
        default:
            break;
        }

        // if (maxForm != formOccsFreq.length) throw new
        // IllegalArgumentException("Corrupted FormEnum name="+fieldName+"
        // maxForm="+maxForm+" formOccsFreq.length="+formOccsFreq.length);
        // int flags = TopArray.NO_ZERO; // ?
        int flags = 0;
        if (reverse) flags |= TopArray.REVERSE;
        TopArray top = null;
        if (limit < 1) top = new TopArray(maxForm, flags);
        else top = new TopArray(limit, flags);
        boolean noZeroScore = false;
        if (formScore != null && order != Order.score) {
            noZeroScore = true;
        }
        // be careful, do not use formOccsAll as a size, the growing array may be bigger
        // than dictionary
        if (order.equals(Order.alpha)) {
            int[] sorter = sortAlpha(formDic);
            this.sorter(sorter);
            reset();
            return sorter;
        }
        // Why exclude first form = 0 ?
        for (int formId = 0, length = maxForm; formId < length; formId++) {
            // do not output global stats if form have been filtered (ex : by cat)
            /* What that for ?
            if (formOccsFreq != null && formOccsFreq[formId] < 1)
                continue;
            */
            // do not output null score
            if (noZeroScore && formScore[formId] == 0)
                continue;
            switch (order) {
                case occs:
                    top.push(formId, formOccs[formId]);
                    break;
                case docs:
                    top.push(formId, formDocs[formId]);
                    break;
                case freq:
                    top.push(formId, formFreq[formId]);
                    break;
                case hits:
                    top.push(formId, formHits[formId]);
                    break;
                case score:
                    top.push(formId, formScore[formId]);
                    break;
                default:
                    top.push(formId, formOccs[formId]);
                    break;
            }
            // to test, do not work yet
            // else top.push(sortAlpha(this.formDic));
        }
        int[] sorter = top.toArray();
        this.sorter(sorter);
        reset();
        return sorter;
    }

    /**
     * Returns an array of formId in alphabetic order for all search of dictionary.
     *
     * @param hashDic
     * @return
     */
    static public int[] sortAlpha(BytesRefHash hashDic)
    {
        Collator collator = Collator.getInstance(Locale.FRANCE);
        collator.setStrength(Collator.TERTIARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        int size = hashDic.size();
        BytesRef bytes = new BytesRef();
        Entry[] sorter = new Entry[size];
        for (int formId = 0; formId < size; formId++) {
            hashDic.get(formId, bytes);
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

    /**
     * Set the sorted vector of ids
     */
    public void sorter(final int[] sorter)
    {
        this.sorter = sorter;
        this.limit = sorter.length;
        reset();
    }

    /**
     * An int tag for term if it’s coming from a text field.
     * 
     * @return
     */
    public int tag()
    {
        return formTag[formId];
    }

    static private class Entry
    {
        final CollationKey key;
        final int formId;

        Entry(final int formId, final CollationKey key)
        {
            this.key = key;
            this.formId = formId;
        }
    }

    /**
     * For the current term, get a number set by {@link #setNos(int[])}.
     * 
     * @return
     */
    /*
     * Very specific to some fields type public int n() { return nos[formId]; }
     */

    @Override
    public String toString()
    {
        int limit = Math.min(formDic.size(), 100);
        StringBuilder sb = new StringBuilder();
        sb.append("size=" + formDic.size() + "\n");
        if (sorter == null) {
            sb.append("No sorter, limit=" + limit + "\n");
            sorter = new int[limit];
            for (int i = 0; i < limit; i++)
                sorter[i] = i;
        }
        boolean hasScore = (formScore != null);
        boolean hasTag = (formTag != null);
        boolean hasHits = (formHits != null);
        boolean hasDocs = (formDocs != null);
        boolean hasOccs = (formOccs != null);
        boolean hasFreq = (formFreq != null);
        for (int pos = 0; pos < limit; pos++) {
            int formId = sorter[pos];
            formDic.get(formId, bytes);
            sb.append((pos + 1) + ". [" + formId + "] " + bytes.utf8ToString());
            if (hasTag)
                sb.append(" " + Tag.label(formTag[formId]));
            if (hasScore)
                sb.append(" score=" + formScore[formId]);

            if (hasOccs && hasFreq) {
                sb.append(" freq=" + formFreq[formId] + "/" + formOccs[formId]);
            }
            else if (hasOccs) {
                sb.append(" freq=" + formOccs[formId]);
            }
            if (hasHits && hasDocs)
                sb.append(" hits=" + formHits[formId] + "/" + formDocs[formId]);
            else if (hasDocs)
                sb.append(" docs=" + formDocs[formId]);
            else if (hasHits)
                sb.append(" hits=" + formHits[formId]);
            sb.append("\n");
        }
        return sb.toString();
    }

    static public class Bigram
    {
        public final int a;
        public final int b;
        public int count = 0;
        public double score;
        final public String label;
    
        Bigram(final int a, final int b)
        {
            this.a = a;
            this.b = b;
            this.label = null;
        }
    
        Bigram(final int a, final int b, final String label)
        {
            this.a = a;
            this.b = b;
            this.label = label;
        }
    
        public int inc()
        {
            return ++count;
        }
    
    }

}
