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
import java.util.Arrays;

import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.lucene.search.FormIterator.Order;
import com.github.oeuvres.alix.util.IntList;

/**
 * A dedicated dictionary for stats on facets. This class is lighter than the
 * lucene <a href="https://lucene.apache.org/core/9_11_1/facet/index.html">facet package</a>, backed to memory
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
 * <li>search, matching document count</li>
 * <li>search, matching occurrences count</li>
 * </ul>
 * 
 * <p>
 * This facet dic is backed on an efficient
 * {@link BytesRefHash}. This handy data structure
 * provide a sequential int id for each term. This is used as a pointer in
 * different growing arrays. On creation, object is populated with data non
 * dependent of a search. Those internal vectors are stored as arrays with
 * facetId index.
 * </p>
 */
public class FieldFacet extends FieldCharsAbstract
{
    /** docId4facetId[docId] = [facetId1, facetId2…], used to get freqs */
    final private int[][] docId4facetId;
    /** The field type */
    final private DocValuesType type;

    /**
     * Extract values of field for stats.
     * 
     * @param reader a lucene index reader.
     * @param fieldName name of a lucene text field.
     * @throws IOException Lucene errors.
     */
    public FieldFacet(final DirectoryReader reader, final String fieldName) throws IOException {
        super(reader, fieldName);
        // final int[] occsByDoc = new int[reader.maxDoc()];
        type = info.getDocValuesType();
        IndexOptions options = info.getIndexOptions();
        docId4facetId = new int[reader.maxDoc()][];
        // SortedSetField
        if (type == DocValuesType.SORTED_SET) {
            buildSortedSetField(fieldName);
        }
        // SortedField
        else if (type == DocValuesType.SORTED_SET) {
            buildSortedField(fieldName);
        }
        // StringField
        else if (options == IndexOptions.DOCS) {
            buildStringField(fieldName);
        }
        else { //
            throw new IllegalArgumentException(
                    "Field \"" + fieldName + "\", the type " + type + " is not supported as a facet.");
        }
        this.maxForm = dic.size();
    }

    /**
     * Build data from a {@link SortedSetDocValuesField}.
     * @param fieldName field name.
     * @throws IOException Lucene errors.
     */
    public void buildSortedSetField(final String fieldName) throws IOException
    {
        final IntList docsByForm = new IntList();
        final IntList docIdRow = new IntList();
        // loop on each leaves
        for (LeafReaderContext context : reader.leaves()) {
            final LeafReader leaf = context.reader();
            final SortedSetDocValues docIterator = leaf.getSortedSetDocValues(fieldName);
            if (docIterator == null) continue;
            final int valueCount = (int) docIterator.getValueCount();
            TermsEnum terms = docIterator.termsEnum();
            BytesRef bytes;
            int[] formIdByOrd = new int[valueCount];
            while((bytes = terms.next()) != null) {
                final int ord = (int)terms.ord();
                int formId = dic.add(bytes);
                if (formId < 0) {
                    formId = -formId - 1; // value already given
                }
                formIdByOrd[ord] = formId;
            }
            // loop on all docs
            Bits live = leaf.getLiveDocs();
            boolean hasLive = (live != null);
            final int docBase = context.docBase;
            int docLeaf;
            while ((docLeaf = docIterator.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (hasLive && !live.get(docLeaf)) {
                    continue; // deleted doc
                }
                final int docValueCount = docIterator.docValueCount();
                if (docValueCount < 1) continue;
                docsAll++;
                final int docId = docBase + docLeaf;
                docIdRow.clear();
                for (int i = 0; i < docValueCount; i++) {
                    final int ord = (int) docIterator.nextOrd();
                    final int formId = formIdByOrd[ord];
                    docIdRow.push(formId);
                    docsByForm.inc(formId);
                }
                docId4facetId[docId] = docIdRow.uniq();
            }
        }
        this.formId4docs = docsByForm.toArray();
    }

    
    /**
     * Build data from a {@link SortedDocValuesField}.
     * @param fieldName field name.
     * @throws IOException Lucene errors.
     */
    private void buildSortedField(final String fieldName) throws IOException
    {
        final IntList docsByForm = new IntList();
        // loop on each leaves
        for (LeafReaderContext context : reader.leaves()) {
            final LeafReader leaf = context.reader();
            final SortedDocValues docIterator = leaf.getSortedDocValues(fieldName);
            if (docIterator == null) continue;
            final int valueCount = (int) docIterator.getValueCount();
            TermsEnum terms = docIterator.termsEnum();
            BytesRef bytes;
            // forms of this leave
            int[] formIdByOrd = new int[valueCount];
            while((bytes = terms.next()) != null) {
                final int ord = (int)terms.ord();
                int formId = dic.add(bytes);
                if (formId < 0) {
                    formId = -formId - 1; // value already given
                }
                formIdByOrd[ord] = formId;
            }
            // loop on all docs of this leave
            Bits live = leaf.getLiveDocs();
            boolean hasLive = (live != null);
            final int docBase = context.docBase;
            int docLeaf;
            while ((docLeaf = docIterator.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (hasLive && !live.get(docLeaf)) {
                    continue; // deleted doc
                }
                final int ord = (int) docIterator.ordValue();
                final int docId = docBase + docLeaf;
                final int formId = formIdByOrd[ord];
                docsByForm.inc(formId);
                docId4facetId[docId] = new int[]{formId};
                docsAll++;
            }
        }
        this.formId4docs = docsByForm.toArray();
    }

    /**
     * Build data from a {@link StringField}.
     * 
     * @param fieldName field name.
     * @throws IOException Lucene errors.
     */
    private void buildStringField(final String fieldName) throws IOException
    {
        BytesRef bytes;
        IntList formDocs = new IntList();
        IntList[] docForms = new IntList[reader.maxDoc()];

        // loop on the index leaves to get all terms and freqs
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            int docBase = context.docBase;
            Terms terms = leaf.terms(fieldName);
            if (terms == null)
                continue;
            TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
            PostingsEnum docsEnum = null;
            while ((bytes = tenum.next()) != null) {
                if (bytes.length == 0)
                    continue; // should not count empty position
                int formId = dic.add(bytes);
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
                        docsAll++;
                        docForms[docId] = new IntList();
                    }
                    docForms[docId].push(formId);
                    formDocs.inc(formId);
                    // occsAll++;
                }
            }
        }
        this.formId4docs = formDocs.toArray();
        for (int i = 0, len = docForms.length; i < len; i++) {
            if (docForms[i] == null)
                continue; // empty line
            this.docId4facetId[i] = docForms[i].toArray();
        }
        maxForm = dic.size();
    }

    /**
     * Returns a new enumerator on all search for this facet in orthographic order
     * 
     * @return enumerator of facets in alphabetic order.
     * @throws IOException Lucene errors.
     */
    public FormEnum formEnum() throws IOException
    {
        FormEnum formEnum = new FormEnum(this);
        formEnum.sort(Order.ALPHA);
        return formEnum;
    }

    /**
     * Get forms for this field, filtered by a set of docIds.
     * 
     * @param docFilter a set of docId.
     * @return an object to sort and loop forms.
     * @throws IOException lucene errors.
     */
    public FormEnum formEnum(final BitSet docFilter) throws IOException
    {
        FormEnum formEnum = formEnum();
        if (docFilter == null) {
            return formEnum();
        }
        formEnum.formId4hits = new int[maxForm];
        for (int docId = 0, max = this.docId4facetId.length; docId < max; docId++) {
            // document not in the filter, go next
            if (!docFilter.get(docId))
                continue;
            formEnum.hitsAll++; // document hit
            // empty document, probably a book cover, but we don’t want a dependance here on
            // a FieldText
            // if (ftext.occsByDoc[docId] < 1) continue;
            int[] facets = docId4facetId[docId];
            if (facets == null)
                continue;
            for (int facetId : facets) {
                formEnum.formId4hits[facetId]++;
            }
        }
        return formEnum;
    }

    /**
     * Get occurences stats by facet from a text field.
     * 
     * @param ftext required stats from a text field.
     * @param docFilter optional set of internal lucene docId.
     * @return an enumerator of facets with some textual stats.
     * @throws IOException Lucene errors.
     */
    public FormEnum formEnum(final FieldText ftext, final BitSet docFilter) throws IOException
    {
        return formEnum(ftext, docFilter, null, null);
    }

    /**
     * Get occurrences count of a text field by facet.
     * For example, an index has 3 facets : withLotsOfDummy, withSomeDummy, withoutDummy.
     * Searching for the word "dummy" may result in a count of occurrences of the word "dummy"
     * very different on each facet (withLotsOfDummy: a lot, withSomeDummy: some, withoutDummy: 0).
     * 
     * Query maybe restricted by a doc filter (a
     * corpus). If there are no terms in the search, will cry. Returns an iterator
     * on search of this facet, with scores and other stats.
     * 
     * @param ftext required stats from a text field.
     * @param docFilter optional set of internal lucene docId.
     * @param formBytes optional set of terms present in the text field.
     * @param scorer optional distribution to calculate a score for each facet.
     * @return an enumerator of facets with textual stats.
     * @throws IOException Lucene errors.
     */
    public FormEnum formEnum(final FieldText ftext, final BitSet docFilter, final BytesRef[] formsBytes, Distrib scorer)
            throws IOException
    {
        if (ftext == null) {
            throw new IllegalArgumentException("A TextField (with indexed tokens) is required here");
        }
        FormEnum formEnum = formEnum();
        formEnum.occsAll = ftext.occsAll();
        formEnum.formId4occs = new long[maxForm];
        // loop on all docs by docId to set occs by facet
        for (int docId = 0, len = reader.maxDoc(); docId < len; docId++) {
            final long occs = ftext.occsByDoc(docId);
            int[] facets = docId4facetId[docId]; // get the facets for this doc
            if (facets == null) continue;
            for (final int facetId: facets) {
                formEnum.formId4occs[facetId] += occs;
            }
        }
        boolean hasFilter = (docFilter != null);
        // no query to search with, but a doc filter to set hits
        if (formsBytes == null && hasFilter) {
            formEnum.formId4hits = new int[maxForm];
            // loop on all docs by docId to set hits by facet
            for (int docId = 0, len = reader.maxDoc(); docId < len; docId++) {
                if (!docFilter.get(docId)) continue;
                formEnum.hitsAll++;
                int[] facets = docId4facetId[docId]; // get the facets for this doc
                if (facets == null) continue;
                for (final int facetId: facets) {
                    formEnum.formId4occs[facetId] ++;
                }
            }
            return formEnum;
        }
        // no more stats to get here
        else if (formsBytes == null) {
            return formEnum;
        }
        boolean hasScorer = (scorer != null);
        
        


        // Crawl index to get stats by facet term about the text search
        java.util.BitSet docMap = new java.util.BitSet(reader.maxDoc()); // keep memory of already counted docs

        formEnum.formId4hits = new int[maxForm];
        formEnum.formId4freq = new long[maxForm]; // a vector to count matched occurrences by facet


        // hits by form, to record a var for scoring
        final int[] hitsByForm = new int[ftext.maxForm()];
        PostingsEnum docsEnum = null; // reuse
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            final int docBase = context.docBase;
            Terms terms = leaf.terms(ftext.name());
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            for (final BytesRef bytes : formsBytes) {
                if (bytes == null) continue;
                if (!tenum.seekExact(bytes)) continue; // term may be absent from this leaf
                final int formId = ftext.formId(bytes);
                docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
                int docLeaf;
                final Bits live = leaf.getLiveDocs();
                while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    if (hasFilter &&!docFilter.get(docId)) continue; // document not in the filter
                    hitsByForm[formId]++; // increment hits by form
                    final int freq = docsEnum.freq();
                    if (freq == 0) continue; // no occurrence for this term
                    final boolean docSeen = docMap.get(docId); // doc has been seen for another term in the search
                    int[] facets = docId4facetId[docId]; // get the facets of this doc
                    if (facets == null) continue; // could be null if doc matching but not faceted
                    // some docs may have no facets, like books covers
                    if (!docSeen) formEnum.hitsAll++; // 
                    formEnum.freqAll += freq; // all terms matched
                    // loop on facets for this term in this leaf
                    for (final int facetId : facets) {
                        // if doc not already counted for another, increment hits for this facet
                        if (!docSeen) {
                            formEnum.formId4hits[facetId]++;
                            docMap.set(docId);
                        }
                        formEnum.formId4freq[facetId] += freq; // add the matched freqs for this doc to the facet
                    }
                }
            }
        }
        // loop on facets to calculate score
        if (hasScorer) {
            formEnum.scoreByForm = new double[maxForm];
            for (int facetId = 0; facetId < maxForm; facetId++) {
                for (final BytesRef bytes : formsBytes) {
                    final int formId = ftext.formId(bytes);
                    scorer.expectation(ftext.occs(formId), ftext.occsAll);
                    // hits here is the count of docs 
                    scorer.idf(ftext.formId4docs[formId], ftext.docsAll, ftext.occsAll);
                    // ??
                    formEnum.scoreByForm[facetId] += scorer.score(formEnum.formId4freq[facetId], formEnum.formId4occs[facetId]);
                }
            }
        }
        return formEnum;
    }
    
    

    /**
     * Use a list of search as a navigator for a list of doc ids. The list is
     * supposed to be sorted in a relevant order for this facet ex : (author, title)
     * or (author, date) for an author facet. Get the index of the first relevant
     * document for each faceted term.
     * 
     * @param topDocs lucene search results {@link IndexSearcher#search(Query, int)}.
     * @return array[facetId] = docId (the first one in the sort 
     */
    public int[] nos(final TopDocs topDocs)
    {
        int[] nos = new int[maxForm];
        Arrays.fill(nos, Integer.MIN_VALUE);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        // loop on doc in order
        for (int n = 0, docs = scoreDocs.length; n < docs; n++) {
            final int docId = scoreDocs[n].doc;
            int[] facets = docId4facetId[docId]; // get the facets of this doc
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
     * Calculate, by facet, global count of occurrences of a text field
     * 
     * @param ftext required stats on a text field.
     * @return array[facetId] = count of occurrences of documents.
     */
    public long[] stats(FieldText ftext)
    {
        // try to find it in cache ?
        long[] formOccsAll = new long[maxForm];
        // loop on each doc to
        for (int docId = 0, len = docId4facetId.length; docId < len; docId++) {
            int[] forms = docId4facetId[docId];
            if (forms == null)
                continue;
            final int occsByDoc = ftext.occsByDoc(docId);
            for (final int formId : forms) {
                formOccsAll[formId] += occsByDoc;
            }
        }
        return formOccsAll;
    }

    /**
     * Get first available facetId for this docId or -1 if nothing found.
     * Useful for unique value by doc for this field.
     * 
     * @param docId lucene internal document id.
     * @return first facetId for this doc.
     */
    public int valueId(final int docId)
    {
        int[] results = docId4facetId[docId];
        if (results == null || results.length < 1)
            return -1;
        return results[0];
    }

    /**
     * Get facetIds for a docId.
     * 
     * @param docId a lucene internal doc id.
     * @return array of facet id for this doc.
     */
    public int[] valueIds(final int docId)
    {
        return docId4facetId[docId];
    }

    @Override
    public String toString()
    {
        StringBuilder string = new StringBuilder();
        BytesRef ref = new BytesRef();
        for (int i = 0; i < maxForm; i++) {
            dic.get(i, ref);
            string.append(ref.utf8ToString() + "\n");
        }
        return string.toString();
    }

}
