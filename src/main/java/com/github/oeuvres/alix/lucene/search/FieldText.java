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
import java.util.Collections;

import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.lucene.analysis.FrDics;
import com.github.oeuvres.alix.lucene.analysis.FrDics.LexEntry;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.IntList;

/**
 * An object recording stats for an indexed and tokenized lucene field {@link TextField}. 
 * Lexical stats needs heavy access to some counts that are not native in classical retrieving engine
 * like lucene. For example, it could be expensive to get the list of most frequent or relevant terms
 * from a set of documents found by a query. This object maintain such statistics in memory, for index
 * considered like a “corpus” (a set texts considered as a whole).
 */
public class FieldText extends FieldCharsAbstract
{
    /** locByForm.get(formId) == true: form is a locution. */
    private BitSet locByForm;
    /** Σ occsByForm; global count of occs for this field, without empty positions. */
    protected final long occsAll;
    /** occsByDoc[docId] = occs; count of occurrences by document, without empty positions. */
    protected final int[] occsByDoc;
    /** occsByform[formId] = occs; count of occurrences by form. */
    protected final long[] occsByForm;
    /** punByForm.get(formId) == true: form is punctuation. */
    private BitSet punByForm;
    /** stopByForm.get(formId) == true: form is a stop word. */
    private BitSet stopByForm;
    /** tagByForm[formI] = {@link Tag#flag()}; lexical type of form. */
    protected int[] tagByForm;
    /**
     * Build the dictionaries and stats. Each form indexed for the field will be
     * identified by an int (formId). This id will be in freq order: the
     * form with formId=1 is the most frequent for the index (formId=0 is the empty
     * string).
     * 
     * @param reader a lucene index reader.
     * @param fieldName name of a lucene text field.
     * @throws IOException Lucene errors.
     */
    public FieldText(final DirectoryReader reader, final String fieldName) throws IOException {
        super(reader, fieldName);
        IndexOptions options = info.getIndexOptions();
        if (options == IndexOptions.NONE || options == IndexOptions.DOCS) {
            throw new IllegalArgumentException(
                    "Field \"" + fieldName + "\" of type " + options + " has no FREQS (see IndexOptions)");
        }
        long occsAll = 0;
        /*
         * Array in docId order, with the total number of tokens by doc. Term vector
         * cost 1 s. / 1000 books and is not precise. Norms for similarity is not enough
         * precise (1 byte) see SimilarityBase.computeNorm()
         * https://github.com/apache/lucene-solr/blob/master/lucene/core/src/java/org/
         * apache/lucene/search/similarities/SimilarityBase.java#L185
         */
        occsByDoc = new int[reader.maxDoc()];
        // used between leaves to avoid errors in docs count
        final FixedBitSet docSet = new FixedBitSet(reader.maxDoc());
        // extract all terms on first pass to give a formId in frequence ordert
        class FormRecord implements Comparable<FormRecord>
        {
            final int tmpId;
            int docs;
            long occs;
        
            FormRecord(final int tmpId) {
                this.tmpId = tmpId;
            }
        
            @Override
            public int compareTo(FormRecord o)
            {
                int cp = Long.compare(o.occs, occs);
                if (cp != 0) return cp;
                // not the nicest alpha sort order
                return Integer.compare(tmpId, o.tmpId);
            }
        }

        ArrayList<FormRecord> stack = new ArrayList<FormRecord>();
        BytesRef bytes;

        // Do not use IndexReader.totalTermFreq(Term term)
        // «Note that, like other term measures, this measure does not take deleted
        // documents into account.»
        // So it is more efficient to use a term iterator on all terms

        // a tmp dic used as an id provider, is needed if there are more than on leave
        // to the index
        // is also used to remmeber the UTF8 bytes to reorder
        BytesRefHash tmpDic = new BytesRefHash();
        // loop on the index leaves to get all terms and freqs
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            int docBase = context.docBase;
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
            PostingsEnum docsEnum = null;
            while ((bytes = tenum.next()) != null) {
                if (bytes.length == 0) continue; // should not count empty position
                FormRecord rec;
                int tmpId = tmpDic.add(bytes);
                // form already encountered, probabbly another leave
                if (tmpId < 0) {
                    tmpId = -tmpId - 1;
                    rec = stack.get(tmpId); // should be OK, but has not be tested
                }
                else {
                    rec = new FormRecord(tmpId);
                    stack.add(tmpId, rec);
                }
                // termLength[formId] += tenum.totalTermFreq(); // not faster if not yet cached
                docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
                int docLeaf;
                Bits live = leaf.getLiveDocs();
                while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    int freq = docsEnum.freq();
                    if (freq == 0) continue; // strange, is’n it ? Will probably not arrive
                    rec.docs++;
                    rec.occs += freq;
                    occsAll += freq;
                    occsByDoc[docId] += freq;
                    docSet.set(docId);
                }
            }
        }
        maxForm = stack.size() + 1; // should be the stack of non empty term + empty term
        // here we should have all we need to affect a freq formId
        // sort forms, and reloop on them to get optimized things
        java.util.BitSet stopRecord = new java.util.BitSet(); // record StopWords in a growable BitSet
        java.util.BitSet punRecord = new java.util.BitSet();
        locByForm = new SparseFixedBitSet(maxForm); // record locutions, size of BitSet will be full
        
        dic.add(new BytesRef("")); // add empty string as formId=0 for empty positions
        occsByForm = new long[maxForm];
        docsByform = new int[maxForm];
        tagByForm = new int[maxForm];
        Collections.sort(stack); // should sort by frequences
        CharsAttImpl chars = new CharsAttImpl(); // to test against indexation dicos
        bytes = new BytesRef();

        for (FormRecord rec : stack) {
            tmpDic.get(rec.tmpId, bytes); // get the term
            final int formId = dic.add(bytes); // copy it in the other dic and get its definitive id
            // if (bytes.length == 0) formId = 0; // if empty pos is counted
            occsByForm[formId] = rec.occs;
            docsByform[formId] = rec.docs;
            if (FrDics.isStop(bytes)) stopRecord.set(formId);
            chars.copy(bytes); // convert utf-8 bytes to utf-16 chars
            final int indexOfSpace = chars.indexOf(' ');
            if (indexOfSpace > 0) locByForm.set(formId);
            LexEntry entry = FrDics.word(chars);
            if (entry != null) {
                tagByForm[formId] = entry.tag;
                continue;
            }
            entry = FrDics.name(chars);
            if (entry != null) {
                if (entry.tag == Tag.NAMEpers.flag || entry.tag == Tag.NAMEpersf.flag || entry.tag == Tag.NAMEpersm.flag
                        || entry.tag == Tag.NAMEfict.flag || entry.tag == Tag.NAMEauthor.flag) {
                    tagByForm[formId] = Tag.NAMEpers.flag;
                    continue;
                }
                else {
                    tagByForm[formId] = entry.tag;
                    continue;
                }
            }
            if (Char.isPunctuation(chars.charAt(0))) {
                tagByForm[formId] = Tag.PUN.flag;
                punRecord.set(formId);
                continue;
            }
            if (indexOfSpace > 0) {
                chars.setLength(indexOfSpace);
                // monsieur Madeleine
                entry = FrDics.word(chars);
                if (entry != null) {
                    if (entry.tag == Tag.SUBpers.flag) {
                        tagByForm[formId] = Tag.NAMEpers.flag;
                        continue;
                    }
                    if (entry.tag == Tag.SUBplace.flag) {
                        tagByForm[formId] = Tag.NAMEplace.flag;
                        continue;
                    }
                }
                // Jean Valjean
                entry = FrDics.name(chars);
                if (entry != null) {
                    if (entry.tag == Tag.NAMEpers.flag || entry.tag == Tag.NAMEpersf.flag
                            || entry.tag == Tag.NAMEpersm.flag) {
                        tagByForm[formId] = Tag.NAMEpers.flag;
                        continue;
                    }
                }
            }

            // if (chars.length() < 1) continue; // ?
            if (Char.isUpperCase(chars.charAt(0))) tagByForm[formId] = Tag.NAME.flag;
        }
        // convert a java.lang growable BitSets in fixed lucene ones
        stopByForm = new FixedBitSet(stopRecord.length());
        for (int formId = stopRecord.nextSetBit(0); formId != -1; formId = stopRecord.nextSetBit(formId + 1)) {
            stopByForm.set(formId);
        }
        punByForm = new FixedBitSet(punRecord.length());
        for (int formId = punRecord.nextSetBit(0); formId != -1; formId = punRecord.nextSetBit(formId + 1)) {
            punByForm.set(formId);
        }

        docsAll = docSet.cardinality();
        this.occsAll = occsAll;
    }

    /**
     * Get a dictionary of terms, without statistics.
     * 
     * @param reader lucene index reader.
     * @param fieldName a text field name.
     * @return a bytes dictionary.
     * @throws IOException Lucene errors.
     */
    static public BytesRefHash dic(DirectoryReader reader, String fieldName) throws IOException
    {
        BytesRefHash hashDic = new BytesRefHash();
        BytesRef ref;
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            // int docBase = context.docBase;
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            while ((ref = tenum.next()) != null) {
                int formId = hashDic.add(ref);
                if (formId < 0) formId = -formId - 1; // value already given
            }
        }
        return hashDic;
    }

    /**
     * Total count of occurrences (except empty positions) for a docId.
     * 
     * @param docId lucene internal doc id.
     * @return occurrences count for this doc.
     */
    public int docOccs(final int docId)
    {
        return occsByDoc[docId];
    }

    /**
     * Build a BitSet of formId for efficient filtering of forms by tags.
     * 
     * @param formFilter filter 
     * @return a formId filter.
     */
    public BitSet formFilter(TagFilter formFilter)
    {
        BitSet rule = new SparseFixedBitSet(maxForm);
        final boolean noStop = formFilter.get(Tag.NOSTOP);
        for (int formId = 1; formId < maxForm; formId++) {
            if (noStop) {
                if (isStop(formId)) continue;
            }
            else {
                if (!formFilter.get(tagByForm[formId])) continue;
            }
            rule.set(formId);
        }
        return rule;
    }

    /**
     * Get a non filtered term enum for this field with global stats.
     * 
     * @return an object to sort and loop forms.
     */
    public FormEnum formEnum()
    {
        FormEnum forms = new FormEnum(this);
        return forms;
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
        if (docFilter == null) {
            throw new IllegalArgumentException("BitSet doc filter is null, what kind of results are expected?");
        }
        if (docFilter.cardinality() < 1) { // all is filtered, after sort, iterator should not loop
            return formEnum();
        }
        return formEnum(docFilter, null, null);
    }

    /**
     * Count of occurrences by term for a subset of the index, defined as a BitSet.
     * Returns an iterator sorted according to a scorer. If scorer is null, default
     * is count of occurrences.
     * 
     * @param docFilter a set of docId.
     * @param formFilter a set of formId.
     * @param distribution a scoring algorithm.
     * @return an object to sort and loop forms.
     * @throws IOException lucene errors.
     */
    public FormEnum formEnum(final BitSet docFilter, final TagFilter formFilter, Distrib distribution) throws IOException
    {
        FormEnum formEnum = formEnum(); // get global stats 
    
        boolean noStop = (formFilter != null && formFilter.get(Tag.NOSTOP));
        boolean locs = (formFilter != null && formFilter.get(Tag.LOC));
        boolean hasTags = (formFilter != null && (formFilter.cardinality(null, TagFilter.NOSTOP_LOC) > 0));
        
        
        boolean hasDistrib = (distribution != null);
        boolean hasFilter = (docFilter != null && docFilter.cardinality() > 0);
    
        if (hasDistrib) formEnum.scoreByForm = new double[maxForm];
        formEnum.freqByForm = new long[maxForm];
        formEnum.hitsByForm = new int[maxForm];
        
        int occsPart = 0;
        if (docFilter != null) {
            for (int docId = docFilter.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS; docId = docFilter
                    .nextSetBit(docId + 1)) {
                occsPart += occsByDoc[docId];
            }
        }
        // if (hasSpecif) specif.all(occsAll, docsAll);
        BitSet hitsByDoc = new FixedBitSet(reader.maxDoc());
    
        BytesRef bytes;
        // loop an all index to calculate a score for each term before build a more
        // expensive object
        for (LeafReaderContext context : reader.leaves()) {
            int docBase = context.docBase;
            LeafReader leaf = context.reader();
            Bits live = leaf.getLiveDocs();
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            PostingsEnum postings = null;
            while ((bytes = tenum.next()) != null) {
                if (bytes.length == 0) continue; // do not count empty positions
                int formId = dic.find(bytes);
                // filter some tags
                if (noStop) { // special tag
                    if(isStop(formId)) continue;
                }
                else if (locs) {  // special tag
                    if(!locByForm.get(formId)) continue;
                }
                else if (hasTags) {
                    if(!formFilter.get(tagByForm[formId])) continue;
                }
                // if formId is negative, let the error go, problem in reader
                // for each form, set scorer with global stats by form, before count by doc
                if (hasDistrib) {
                    distribution.idf(docsByform[formId], docsAll, occsAll);
                    distribution.expectation(occsByForm[formId], occsAll);
                }
                postings = tenum.postings(postings, PostingsEnum.FREQS);
                int docLeaf;
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    int docId = docBase + docLeaf;
                    if (hasFilter && !docFilter.get(docId)) continue; // document not in the filter
                    final int freq = postings.freq();
                    if (freq < 1) throw new ArithmeticException("??? field=" + fieldName + " docId=" + docId + " term="
                            + bytes.utf8ToString() + " freq=" + freq);
                    // doc not yet encounter, we can count
                    if (!hitsByDoc.get(docId)) {
                        formEnum.hitsAll++;
                        hitsByDoc.set(docId);
                    }
                    formEnum.hitsByForm[formId]++;
                    if (hasDistrib) {
                        final double score = distribution.score(freq, occsByDoc[docId]);
                        // if (score < 0) forms.formScore[formId] -= score; // all variation is
                        // significant
                        formEnum.scoreByForm[formId] += score;
                    }
                    formEnum.freqByForm[formId] += freq;
                    formEnum.freqAll += freq;
                }
            }
        }
        // finalize some scorer like G or Chi2
        if (hasDistrib) {
            for (int formId = 0; formId < maxForm; formId++) {
                distribution.expectation(occsByForm[formId], occsAll); // do not forget expectation for the form
                // add inverse score
                final long restFreq = occsByForm[formId] - formEnum.freqByForm[formId];
                final long restLen = occsAll - occsPart;
                double score = distribution.last(restFreq, restLen);
                formEnum.scoreByForm[formId] += score;
            }
        }

        return formEnum;
    }

    /**
     * Get scored words for a partition of the full index. A part is a sequential int
     * between [0, parts[. Parts are given as a vector of ints where classifier[docId]=part.
     * If part &lt; 0, the docId is not counted. Results are returned like an array of {@link FormEnum},
     * where FormEnum[part] is the scored list of term for the partition.
     * 
     * @param parts count of parts.
     * @param classifier vector of ints where classifier[docId]=part.
     * @param formFilter filter words by tag.
     * @param scorer scorer.
     * @return a set of dictionaries, one for each part.
     * @throws IOException lucene errors.
     */
    public FormEnum[] formEnumByPart(final int parts, final int[] classifier, final TagFilter formFilter, Distrib scorer)
            throws IOException
    {
        if (parts < 1) {
            throw new IllegalArgumentException("A partition with " + parts + " parts ?");
        }
        if (classifier == null) {
            throw new IllegalArgumentException(
                    "A partition needs a “classifier”, a vector of ints where classifier[docId]=part; ");
        }
        if (classifier.length != reader.maxDoc()) {
            throw new IllegalArgumentException("Is your classifer for this index ? classifier.length="
                    + classifier.length + " IndexReader.maxDoc()=" + reader.maxDoc());
        }
        boolean hasScorer = (scorer != null);
        
        boolean noStop = (formFilter != null && formFilter.get(Tag.NOSTOP));
        boolean hasTags = (formFilter != null && (formFilter.cardinality(null, TagFilter.NOSTOP_LOC) > 0));

        
        FormEnum[] dics = new FormEnum[parts];
        for (int i = 0; i < parts; i++) {
            FormEnum forms = formEnum();
            dics[i] = forms;
            if (hasScorer) forms.scoreByForm = new double[maxForm];
            forms.freqByForm = new long[maxForm];
            forms.hitsByForm = new int[maxForm];
            forms.hitsByDoc = new FixedBitSet(reader.maxDoc());
        }
        // loop on index
        BytesRef bytes;
        // loop an all index to calculate a score for each term
        for (LeafReaderContext context : reader.leaves()) {
            int docBase = context.docBase;
            LeafReader leaf = context.reader();
            Bits live = leaf.getLiveDocs();
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            PostingsEnum docsEnum = null;
            while ((bytes = tenum.next()) != null) {
                if (bytes.length == 0) continue; // do not count empty positions
                int formId = dic.find(bytes);
                // filter some tags
                if (noStop) { // special tag
                    if(isStop(formId)) continue;
                }
                else if (hasTags) {
                    if(!formFilter.get(tagByForm[formId])) continue;
                }
                // if formId is negative, let the error go, problem in reader
                // for each term, set scorer with global stats
                if (hasScorer) {
                    scorer.idf(docsByform[formId], docsAll, occsAll);
                    scorer.expectation(occsByForm[formId], occsAll);
                }
                docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
                int docLeaf;
                while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    // choose a part
                    final int part = classifier[docId];
                    if (part < 0) continue; // doc not counted
                    if (part >= parts) {
                        throw new IllegalArgumentException(
                                "Non expected part found in your classifier part=" + part + " >= parts=" + parts);
                    }
                    FormEnum forms = dics[part];
                    int freq = docsEnum.freq();
                    if (freq < 1) throw new ArithmeticException("??? field=" + fieldName + " docId=" + docId + " term="
                            + bytes.utf8ToString() + " freq=" + freq);
                    // doc not yet encounter, we can count
                    if (!forms.hitsByDoc.get(docId)) {
                        forms.hitsAll++;
                        forms.hitsByDoc.set(docId);
                    }
                    forms.hitsByForm[formId]++;
                    if (hasScorer) {
                        final double score = scorer.score(freq, occsByDoc[docId]);
                        // if (score < 0) forms.formScore[formId] -= score; // all variation is
                        // significant
                        forms.scoreByForm[formId] += score;
                    }
                    forms.freqByForm[formId] += freq;
                    forms.freqAll += freq;
                }
                /*
                 * chi2, g: bof if (hasDistrib) { // add inverse score final long restFreq =
                 * formOccs[formId] - forms.formFreq[formId]; final long restLen = occs -
                 * forms.occsPart; double score = distrib.last(restFreq, restLen);
                 * forms.formScore[formId] += score; }
                 */
            }
        }
        return dics;
    }

    /**
     * Check if a form is present in a partition of the index. Returns its formId or
     * -1 if not found, like {@link BytesRefHash#find(BytesRef)}.
     * 
     * @param bytes a word form as bytes.
     * @param docFilter set of docIds.
     * @return formId if found, or -1 if not found in partition.
     * @throws IOException Lucene errors.
     */
    public int formId(final BytesRef bytes, final BitSet docFilter) throws IOException
    {
        BytesRef[] forms = new BytesRef[] {bytes};
        int[] ret = formIds(forms, docFilter);
        if (ret == null) return -1;
        if (ret.length < 1) {// ?
            return -1;
        }
        return ret[0];
    }
    
    /**
     * Returns a sorted array of valueId ready for binarySearch, or
     * null if no words found.
     * 
     * @param forms set of forms as {@link CharSequence}
     * @param docFilter set of DocId to include.
     * @return sorted set of formId.
     * @throws IOException lucene errors.
     */
    public int[] formIds(CharSequence[] forms, final BitSet docFilter) throws IOException
    {
        BytesRef[] formsBytes = bytesSorted(forms);
        if (formsBytes == null) return null;
        return formIds(formsBytes, docFilter);
    }

    /**
     * Returns a sorted array of valueId ready for binarySearch, or
     * null if no words found.
     * 
     * @param forms a sorted and verified set of forms as bytes.
     * @param docFilter set of DocId to include.
     * @return sorted set of formId.
     * @throws IOException lucene errors.
     */
    private int[] formIds(BytesRef[] forms, final BitSet docFilter) throws IOException
    {
        final int formsLen = forms.length;
        // loop on leaves of the reader, has some cost
        IntList list = new IntList();
        PostingsEnum docsEnum = null; // reuse
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            final int docBase = context.docBase;
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            Bits live = leaf.getLiveDocs();
            for (int i = 0; i < formsLen; i++) {
                final BytesRef bytes = forms[i];
                if (bytes == null) continue;
                if (!tenum.seekExact(bytes)) {
                    continue;
                }
                docsEnum = tenum.postings(docsEnum, PostingsEnum.NONE);
                int docLeaf;
                while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    if (docFilter != null && !docFilter.get(docId)) continue; // document not in the filter
                    // a doc should be found
                    list.push(dic.find(bytes));
                    // nullify that term
                    forms[i] = null;
                    break;
                }
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        int[] pivots = list.uniq();
        return pivots;
    }

    /**
     * Is this formId a locution ?
     * 
     * @param formId form id for the field.
     * @return true if a locution, false otherwise.
     */
    public boolean isLocution(int formId)
    {
        if (formId >= locByForm.length()) return false; // outside the set bits, should be not a stop word
        return locByForm.get(formId);
    }

    /**
     * Is this formId a punctuation ?
     * 
     * @param formId form id for the field.
     * @return true if punctuation, false otherwise.
     */
    public boolean isPunctuation(int formId)
    {
        if (formId >= punByForm.length()) return false; // outside the set bits, should be not a stop word
        return punByForm.get(formId);
    }

    /**
     * Is this formId a StopWord ?
     * 
     * @param formId form id for the field.
     * @return true if stop word, false otherwise.
     */
    public boolean isStop(int formId)
    {
        if (formId <= 0 || formId >= stopByForm.length()) return false; // outside the set bits, should be not a stop word
        return stopByForm.get(formId);
    }

    /**
     * How many occs for this term in all index ?
     * 
     * @param formId id of a form.
     * @return occurrences for this form.
     */
    public long occs(int formId)
    {
        return occsByForm[formId];
    }

    /**
     * Get global length (occurrences) for a form. Returns -1 if unknown.
     * Should never be 0.
     * 
     * @param form a form to search.
     * @return count of occurrences for this form, or -1 if form absent of index.
     */
    public long occs(final String form)
    {
        final BytesRef bytes = new BytesRef(form);
        final int id = dic.find(bytes);
        if (id < 0) return -1;
        return occsByForm[id];
    }

    /**
     * Get global length (occurrences) for a form. Returns -1 if unknown.
     * Should never be 0.
     * 
     * @param bytes form as native lucene bytes.
     * @return count of occurrences for this form, or -1 if form absent of index.
     */
    public long occs(final BytesRef bytes)
    {
        final int id = dic.find(bytes);
        if (id < 0) return -1;
        return occsByForm[id];
    }

    /**
     * Return count of occurrences for a set of forms with a doc filter.
     * 
     * @param forms set of forms.
     * @param docFilter set of docId.
     * @return count of occurrences by form.
     * @throws IOException Lucene errors.
     */
    public long[] occs(final String[] forms, final BitSet docFilter) throws IOException
    {
        long[] counts = new long[forms.length];
        // loop on leaves of the reader
        for (LeafReaderContext context : reader.leaves()) {
            final int docBase = context.docBase;
            LeafReader leaf = context.reader();
            for (int i = 0, len = forms.length; i < len; i++) {
                // TODO more efficient is possible
                Term term = new Term(fieldName, forms[i]);
                PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS);
                if (postings == null) {
                    continue; // no docs for this term, next leaf
                }
                // loop on docs for this term, till on is in the bitset
                final Bits liveDocs = leaf.getLiveDocs();
                int docLeaf;
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (liveDocs != null && !liveDocs.get(docLeaf)) {
                        continue;
                    }
                    final int docId = docBase + docLeaf;
                    if (!docFilter.get(docId)) {
                        continue;
                    }
                    counts[i] += postings.freq();
                }
            }
        }
        return counts;
    }

    /**
     * Global count of occurrences (except empty positions) for all index.
     * 
     * @return total occurrences for the index.
     */
    public long occsAll()
    {
        return occsAll;
    }

    /**
     * Return tag attached to form according to {@link Tag}.
     * 
     * @param formId id of a form.
     * @return tag for the form.
     */
    public int tag(int formId)
    {
        return tagByForm[formId];
    }

    @Override
    public String toString()
    {
        StringBuilder string = new StringBuilder();
        BytesRef ref = new BytesRef();
        int len = Math.min(maxForm, 200);
        for (int i = 0; i < len; i++) {
            dic.get(i, ref);
            string.append(ref.utf8ToString() + ": " + occsByForm[i] + "\n");
        }
        return string.toString();
    }


    /*
     * // not very efficient way to get occs for a query, kept as a memory of false
     * good idea public DocStats docStats(String[] forms, final BitSet filter)
     * throws IOException { if (forms == null || forms.length == 0) return null;
     * int[] freqs = new int[reader.maxDoc()]; double[] scores = new
     * double[reader.maxDoc()]; final boolean hasFilter = (filter != null &&
     * filter.cardinality() > 0); final boolean hasScorer = (scorer != null); final
     * int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS; // loop an all index to
     * calculate a score for the forms for (LeafReaderContext context :
     * reader.leaves()) { int docBase = context.docBase; LeafReader leaf =
     * context.reader(); Bits live = leaf.getLiveDocs(); final boolean hasLive =
     * (live != null); // loop on forms as lucene term for (String form: forms) {
     * final int formId = formId(form); if (hasScorer) scorer.idf(occsAll, docsAll,
     * formOccsAll[formId], formDocsAll[formId]); Term term = new Term(fname, form);
     * PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS); int docLeaf;
     * while ((docLeaf = postings.nextDoc()) != NO_MORE_DOCS) { if (hasLive &&
     * !live.get(docLeaf)) continue; // deleted doc int docId = docBase + docLeaf;
     * if (hasFilter && !filter.get(docId)) continue; // document not in the filter
     * int freq = postings.freq(); if (freq < 1) throw new
     * ArithmeticException("??? field="+fname+" docId=" +
     * docId+" form="+form+" freq="+freq); freqs[docId] += freq; if (hasScorer)
     * scores[docId] += scorer.tf(freq, docOccs[docId]); else scores[docId] += freq;
     * } } } return new DocStats(freqs, scores); }
     */

}
