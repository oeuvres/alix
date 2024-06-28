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
import java.util.Arrays;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.Alix;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.OptionDistrib;

/**
 * A dedicated dictionary for stats on facets. This class is lighter than the
 * lucene facet package, {@link org.apache.lucene.demo.facet}, backed to memory
 * only, and allow more complex scoring.
 *
 * <p>
 * Example scenario: search "science God" among lots of books. Which author
 * should be the more relevant result? The one with the more occurrences could
 * be a good candidate, but if he signed lots of big books in the corpus, he has
 * a high probability to use the searched words. Different formulas are known
 * for such scoring.
 * </p>
 * 
 * <p>
 * Different variables could be used in such formulas. Some are only relative to
 * all index, some are cacheable for each facet value, other are search
 * dependent.
 * </p>
 * 
 * <ul>
 * <li>index, total document count</li>
 * <li>index, total occurrences count</li>
 * <li>facet, total document count</li>
 * <li>facet, total occurrences count</li>
 * <li>search, matching document count</li>
 * <li>search, matching occurrences count</li>
 * </ul>
 * 
 * <p>
 * This facet dic is backed on an efficient
 * {@link org.apache.lucene.util.BytesRefHash}. This handy data structure
 * provide a sequential int id for each term. This is used as a pointer in
 * different growing arrays. On creation, object is populated with data non
 * dependent of a search. Those internal vectors are stored as arrays with
 * facetId index.
 * </p>
 *
 */
public class FieldFacet
{
    /** A table docId => facetId+, used to get freqs */
    private int[][] docForms;
    /** Global number of docs relevant for this facet */
    protected int docs;
    /** Store and populate the search */
    protected final BytesRefHash formDic;
    /** By facet, Count of docs */
    protected int[] formDocs;
    /** Global number of values for this facet */
    protected int maxForm;
    /** Name of the field for facets, source key for this dictionary */
    public final String name;
    /** Cache the state of a reader from which all freqs are counted */
    private IndexReader reader;
    /** Count of facet affected */
    protected long occs;
    /** The field type */
    public final DocValuesType type;

    /**
     * Build data to have frequencies on a facet field. Access by an Alix instance,
     * to allow cache on an IndexReader state.
     * 
     * @param alix
     * @param name
     * @throws IOException Lucene errors.
     */
    public FieldFacet(final Alix alix, final String name) throws IOException {
        this.reader = alix.reader();
        // final int[] docOccs = new int[reader.maxDoc()];
        FieldInfo info = alix.info(name);
        if (info == null) {
            throw new IllegalArgumentException("Field \"" + name + "\" is not known in this index.");
        }

        type = info.getDocValuesType();
        IndexOptions options = info.getIndexOptions();
        if (type != DocValuesType.SORTED_SET && type != DocValuesType.SORTED && options != IndexOptions.DOCS) {
            throw new IllegalArgumentException(
                    "Field \"" + name + "\", the type " + type + " is not supported as a facet.");
        }

        // common to all formats
        formDic = new BytesRefHash();
        this.name = name;
        docForms = new int[reader.maxDoc()][];

        // StringField
        if (options == IndexOptions.DOCS) {
            buildStringField(name);
        }
        // SortedField
        else {
            buildSortedField(name);
        }

    }

    /*
     * TODO, get BitSet of doc by value ? public void hits(String tag, BitSet hits)
     * { if (hits == null) }
     */

    private void buildSortedField(final String name) throws IOException
    {
        formDocs = new int[32];
        int docsAll = 0;
        int occsAll = 0;
        // this.docLength = docLength;
        // max int for an array collecttor
        int ordMax = -1;
        final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
        for (LeafReaderContext context : reader.leaves()) { // loop on the reader leaves
            int docBase = context.docBase;
            LeafReader leaf = context.reader();
            // get a doc iterator for the facet field
            DocIdSetIterator docs4terms = null;
            if (type == DocValuesType.SORTED) {
                docs4terms = leaf.getSortedDocValues(name);
                if (docs4terms == null)
                    continue;
                ordMax = (int) ((SortedDocValues) docs4terms).getValueCount();
            } else if (type == DocValuesType.SORTED_SET) {
                docs4terms = leaf.getSortedSetDocValues(name);
                if (docs4terms == null)
                    continue;
                ordMax = (int) ((SortedSetDocValues) docs4terms).getValueCount();
            }
            // record doc counts for each term by a temp ord index
            int[] leafDocs = new int[ordMax];
            // loop on docs
            int docLeaf;
            Bits live = leaf.getLiveDocs();
            boolean hasLive = (live != null);
            while ((docLeaf = docs4terms.nextDoc()) != NO_MORE_DOCS) {
                if (hasLive && !live.get(docLeaf)) {
                    continue; // deleted doc
                }
                final int docId = docBase + docLeaf;
                int ord;
                if (type == DocValuesType.SORTED) {
                    ord = ((SortedDocValues) docs4terms).ordValue();
                    occsAll++;
                    leafDocs[ord]++;
                } else if (type == DocValuesType.SORTED_SET) {
                    SortedSetDocValues it = (SortedSetDocValues) docs4terms;
                    for (int i = 0, count = it.docValueCount(); i < count; i++) {
                        // possible bug ? maybe long
                        ord = (int) it.nextOrd();
                        occsAll++;
                        leafDocs[ord]++;
                    }
                }
                docsAll++; // one more doc for this facet
            }
            BytesRef bytes = null;
            // build a local map for this leaf to record the ord -> facetId
            int[] ordFacetId = new int[ordMax];
            // copy the data fron this leaf to the global dic, and get facetId for it
            for (int ord = 0; ord < ordMax; ord++) {
                if (type == DocValuesType.SORTED)
                    bytes = ((SortedDocValues) docs4terms).lookupOrd(ord);
                else if (type == DocValuesType.SORTED_SET)
                    bytes = ((SortedSetDocValues) docs4terms).lookupOrd(ord);
                int facetId = formDic.add(bytes);
                if (facetId < 0)
                    facetId = -facetId - 1; // value already given
                // if more than one cover by facet, last will replace previous
                formDocs = ArrayUtil.grow(formDocs, facetId + 1);
                formDocs[facetId] += leafDocs[ord];
                ordFacetId[ord] = facetId;
            }
            // global dic has set a unified int id for search
            // build a map docId -> facetId+, used to get freqs from docs found
            // restart the loop on docs
            if (type == DocValuesType.SORTED) {
                docs4terms = leaf.getSortedDocValues(name);
            } else if (type == DocValuesType.SORTED_SET) {
                docs4terms = leaf.getSortedSetDocValues(name);
            }
            IntList row = new IntList(); // a growable int array
            while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (live != null && !live.get(docLeaf))
                    continue; // deleted doc
                int ord;
                if (type == DocValuesType.SORTED) {
                    ord = ((SortedDocValues) docs4terms).ordValue();
                    docForms[docBase + docLeaf] = new int[] { ordFacetId[ord] };
                } else if (type == DocValuesType.SORTED_SET) {
                    row.clear();
                    SortedSetDocValues it = (SortedSetDocValues) docs4terms;
                    for (int i = 0, count = it.docValueCount(); i < count; i++) {
                        // possible bug ? maybe long
                        ord = (int) it.nextOrd();
                        row.push(ordFacetId[ord]);
                    }
                    docForms[docBase + docLeaf] = row.toArray();
                }
            }
        }
        this.docs = docsAll;
        this.occs = occsAll;
        maxForm = formDic.size();
    }

    /**
     * Build facet on a SttringField
     * 
     * @throws IOException Lucene errors.
     */
    private void buildStringField(final String name) throws IOException
    {
        BytesRef bytes;
        IntList formDocs = new IntList();
        IntList[] docForms = new IntList[reader.maxDoc()];

        // loop on the index leaves to get all terms and freqs
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            int docBase = context.docBase;
            Terms terms = leaf.terms(name);
            if (terms == null)
                continue;
            TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
            PostingsEnum docsEnum = null;
            while ((bytes = tenum.next()) != null) {
                if (bytes.length == 0)
                    continue; // should not count empty position
                int formId = formDic.add(bytes);
                if (formId < 0)
                    formId = -formId - 1; // value already given
                // termLength[formId] += tenum.totalTermFreq(); // not faster if not yet cached
                docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
                int docLeaf;
                Bits live = leaf.getLiveDocs();
                boolean hasLive = (live != null);
                while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (hasLive && !live.get(docLeaf))
                        continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    if (docForms[docId] == null) {
                        docs++;
                        docForms[docId] = new IntList();
                    }
                    docForms[docId].push(formId);
                    formDocs.inc(formId);
                    occs++;
                }
            }
        }
        this.formDocs = formDocs.toArray();
        for (int i = 0, len = docForms.length; i < len; i++) {
            if (docForms[i] == null)
                continue; // empty line
            this.docForms[i] = docForms[i].toArray();
        }
        maxForm = formDic.size();
    }

    /**
     * Total count of documents for the field
     * 
     * @return
     */
    public int docs()
    {
        return docs;
    }

    /**
     * Get form from a local formId
     * 
     * @return
     */
    public String form(final int facetId)
    {
        BytesRef bytes = new BytesRef();
        if (facetId < 0 || facetId >= maxForm) {
            return null;
        }
        formDic.get(facetId, bytes);
        return bytes.utf8ToString();
    }

    /**
     * Returns formId &gt;= 0 if exists, or &lt; 0 if not.
     * 
     * @param bytes
     * @return
     */
    public int formId(final BytesRef bytes)
    {
        return formDic.find(bytes);
    }

    /**
     * Returns formId &gt;= 0 if exists, or &lt; 0 if not.
     * 
     * @param term
     * @return
     */
    public int formId(final String term)
    {
        BytesRef bytes = new BytesRef(term);
        return formDic.find(bytes);
    }

    /**
     * Get first available facetId for this docId or -1 if nothing found
     */
    public int formId(final int docId)
    {
        int[] results = docForms[docId];
        if (results == null || results.length < 1)
            return -1;
        return results[0];
    }

    /**
     * Get facetIds for a docId
     */
    public int[] formIds(final int docId)
    {
        return docForms[docId];
    }

    /**
     * Returns a new enumerator on all search for this facet in orthographic order
     * 
     * @return
     * @throws IOException Lucene errors.
     */
    public FormEnum forms() throws IOException
    {
        FormEnum forms = new FormEnum(name);
        forms.docs = docs;
        forms.formDic = formDic;
        forms.formDocs = formDocs;
        forms.formOccs = null; // not relevant, a facet is not repeated by doc
        forms.formTag = null;
        forms.maxForm = maxForm;
        forms.occs = occs; // maybe > docsAll for multiple terms
        return forms;
    }

    /**
     * Number of documents by term according to a filter.
     * 
     * @param filter
     * @return
     * @throws IOException Lucene errors.
     */
    public FormEnum forms(final BitSet filter) throws IOException
    {
        FormEnum forms = forms();
        if (filter == null) {
            return forms();
        }
        forms.formHits = new int[maxForm];
        for (int docId = 0, max = this.docForms.length; docId < max; docId++) {
            // document not in the filter, go next
            if (!filter.get(docId))
                continue;
            forms.hits++; // document hit
            // empty document, probably a book cover, but we don’t want a dependance here on
            // a FieldText
            // if (ftext.docOccs[docId] < 1) continue;
            int[] facets = docForms[docId];
            if (facets == null)
                continue;
            for (int facetId : facets) {
                forms.formHits[facetId]++;
            }
        }
        return forms;
    }

    /**
     * Get stats from a text field by facet
     * 
     * @param ftext
     * @param filter
     * @return
     * @throws IOException Lucene errors.
     */
    public FormEnum forms(final FieldText ftext, final BitSet filter) throws IOException
    {
        if (ftext == null) {
            throw new IllegalArgumentException("A TextField (with indexed tokens) is required here");
        }
        boolean hasFilter = (filter != null);
        FormEnum forms = forms();
        forms.formOccs = new long[maxForm];
        if (hasFilter) {
            forms.formFreq = new long[maxForm];
            forms.formHits = new int[maxForm];
        }
        // loop on all docs by docId,
        for (int docId = 0, len = reader.maxDoc(); docId < len; docId++) {
            // get occs count by doc
            long occs = ftext.docOccs[docId];
            if (hasFilter && filter.get(docId)) {
                forms.hits++;
                forms.occs += occs;
            }
            final int[] formIds = docForms[docId];
            if (formIds == null)
                continue;
            for (final int formId : formIds) {
                forms.formOccs[formId] += occs;
                if (hasFilter && filter.get(docId)) {
                    forms.formFreq[formId] += occs;
                    forms.formHits[formId]++;
                }
            }
        }
        return forms;
    }

    /**
     * Get occurrences count of textField by facet. Results of a text search
     * according to a facet field search. Query maybe restricted by a doc filter (a
     * corpus). If there are no search in the search, will cry. Returns an iterator
     * on search of this facet, with scores and other stats.
     * 
     * @return
     * @throws IOException Lucene errors.
     */
    public FormEnum forms(final FieldText ftext, final BitSet filter, final String[] search, OptionDistrib distrib)
            throws IOException
    {
        FormEnum forms = forms(ftext, filter);
        ArrayList<Term> terms = new ArrayList<Term>();
        if (search != null && search.length != 0) {
            for (String f : search) {
                if (f == null)
                    continue;
                if (f.isEmpty())
                    continue;
                terms.add(new Term(ftext.name, f));
            }
        }
        // no terms found
        if (terms.size() < 1) {
            return forms;
        }
        boolean hasScorer = (distrib != null);
        boolean hasFilter = (filter != null);

        // Crawl index to get stats by facet term about the text search
        BitSet docMap = new FixedBitSet(reader.maxDoc()); // keep memory of already counted docs

        forms.formHits = new int[maxForm];
        forms.formFreq = new long[maxForm]; // a vector to count matched occurrences by facet
        if (hasScorer) {
            forms.formScore = new double[maxForm];
        }
        // loop on each term of the search to update the score vector
        @SuppressWarnings("unused")
        int facetMatch = 0; // number of matched facets by this search
        @SuppressWarnings("unused")
        long occsMatch = 0; // total occurrences matched

        // loop on search, this order of loops may be not efficient for a big list of
        // search
        // loop by term is better for some stats
        for (Term term : terms) {
            // long[] formPartOccs = new long[size]; // a vector to count matched
            // occurrences for this term, by facet
            final int formId = ftext.formId(term.bytes());
            // shall we do something here if word not known ?
            if (formId < 1) {
                continue;
            }
            if (hasScorer) {
                distrib.expectation(ftext.formOccs[formId], ftext.occs);
                distrib.idf(ftext.formDocs[formId], ftext.docs, ftext.occs);
            }
            // loop on the reader leaves (opening may have disk cost)
            for (LeafReaderContext context : reader.leaves()) {
                LeafReader leaf = context.reader();
                // get the ocurrence count for the term
                PostingsEnum postings = leaf.postings(term);
                if (postings == null) {
                    continue;
                }
                final int docBase = context.docBase;
                int docLeaf;
                // loop on the docs for this term
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    int docId = docBase + docLeaf;
                    if (hasFilter && !filter.get(docId))
                        continue; // document not in the metadata filter
                    final int freq = postings.freq();
                    if (freq == 0) {
                        continue; // no occurrence for this term (why found with no freqs ?)
                    }
                    final boolean docSeen = docMap.get(docId); // doc has been seen for another term in the search
                    int[] facets = docForms[docId]; // get the facets of this doc
                    if (facets == null)
                        continue; // could be null if doc matching but not faceted
                    occsMatch += freq;
                    for (int i = 0, length = facets.length; i < length; i++) {
                        int facetId = facets[i];
                        // first match for this facet, increment the counter of matched facets
                        if (forms.formFreq[facetId] == 0) {
                            facetMatch++;
                        }
                        // if doc not already counted for another, increment hits for this facet
                        if (!docSeen) {
                            forms.formHits[facetId]++;
                            forms.hits++;
                        }
                        forms.freq += freq;
                        forms.formFreq[facetId] += freq; // add the matched freqs for this doc to the facet
                        // what for ?
                        // formPartOccs[facetId] += freq;
                        // term frequency
                        if (hasScorer) {
                            forms.formScore[facetId] += distrib.score(freq, ftext.docOccs[docId]);
                        }
                    }
                    if (!docSeen) {
                        docMap.set(docId); // do not recount this doc as hit for another term
                    }
                }
            }

        }
        return forms;
    }

    /**
     * Use a list of search as a navigator for a list of doc ids. The list is
     * supposed to be sorted in a relevant order for this facet ex : (author, title)
     * or (author, date) for an author facet. Get the index of the first relevant
     * document for each faceted term.
     */
    public int[] nos(final TopDocs topDocs)
    {
        int[] nos = new int[maxForm];
        Arrays.fill(nos, Integer.MIN_VALUE);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        // loop on doc in order
        for (int n = 0, docs = scoreDocs.length; n < docs; n++) {
            final int docId = scoreDocs[n].doc;
            int[] facets = docForms[docId]; // get the facets of this doc
            if (facets == null)
                continue; // could be null if doc not faceted
            for (int i = 0, length = facets.length; i < length; i++) {
                final int facetId = facets[i];
                // already set, let it, keep the first one
                if (nos[facetId] > -0)
                    continue;
                nos[facetId] = n;
            }
        }
        return nos;
    }

    /**
     * Total count of occurrences
     * 
     * @return
     */
    public long occs()
    {
        return occs;
    }

    /**
     * Number of search in the list.
     * 
     * @return
     */
    public int size()
    {
        return formDic.size();
    }

    /**
     * Calculate, by facet, global count of occurrences of a text field
     */
    public long[] stats(FieldText ftext)
    {
        // try to find it in cache ?
        long[] formOccsAll = new long[maxForm];
        // loop on each doc to
        for (int docId = 0, len = docForms.length; docId < len; docId++) {
            int[] forms = docForms[docId];
            if (forms == null)
                continue;
            int occs = ftext.docOccs[docId];
            for (int formId : forms) {
                formOccsAll[formId] += occs;
            }
        }
        return formOccsAll;
    }

    @Override
    public String toString()
    {
        StringBuilder string = new StringBuilder();
        BytesRef ref = new BytesRef();
        for (int i = 0; i < maxForm; i++) {
            formDic.get(i, ref);
            string.append(ref.utf8ToString() + "\n");
        }
        return string.toString();
    }

}
