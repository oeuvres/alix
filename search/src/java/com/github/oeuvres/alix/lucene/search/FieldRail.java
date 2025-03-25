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

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermVectors;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.common.TagFilter;
import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Edge;
import com.github.oeuvres.alix.util.EdgeMap;
import com.github.oeuvres.alix.util.EdgeMatrix;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntPairMutable;
import com.github.oeuvres.alix.util.IntRoller;
import com.github.oeuvres.alix.util.RowcolQueue;

/**
 * Persistent storage of full sequence of all document search for a field. Used
 * for co-occurrences stats. Data structure of the file
 * <p>
 * int:maxDoc, maxDoc*[int:docLength], maxDoc*[docLength*[int:formId], int:-1]
 */
// ChronicleMap has been tested, but it is not more than x2 compared to lucene BinaryField, so stay in Lucene
public class FieldRail  extends FieldCharsAbstract
{
    static Logger LOGGER = Logger.getLogger(FieldRail.class.getName());
    /** Keep the freqs for the field */
    private final FieldText fieldText;
    /** The path of underlaying file store */
    private final Path path;
    /** Cache a fileChannel for read */
    protected FileChannel channel;
    /** A buffer on file */
    protected MappedByteBuffer channelMap;
    /** Size of file header */
    static final int headerInt = 3;
    /** docId4offset[docId] = offset, start index of positions for each doc in channel */
    protected int[] docId4offset;
    /** docId4le[docId] =  Index of sizes for each doc */
    protected int[] docId4len;

    /**
     * Load rail of words as int, build it as file if necessary.
     * 
     * @param fieldText alix stats on an indexed and tokenized lucene field.
     * @throws IOException lucene errors.
     */
    public FieldRail(FieldText fieldText) throws IOException {
        super(fieldText.reader, fieldText.fieldName);
        this.fieldText = fieldText; // build and cache the dictionary for the field
        this.dic = fieldText.dic;
        this.maxForm = dic.size();
        // path to the store for rails
        this.path = ((FSDirectory)reader.directory()).getDirectory().resolve(fieldName + ".rail");
        load();
    }
    

    /**
     * Build a freqList of coocurrents , attached to a FormEnum object.
     * Returns the count of occurences found. This method may need a lot of optional
     * params, set on the FormEnum object.
     * 
     * @param pivotIds an set of lucene terms.
     * @param left width of context before a pivot.
     * @param right width of context after a pivot.
     * @param docFilter optional, set of lucene internal docId to restrict collect.
     * @return forms with freq.
     * @throws IOException lucene errors.
     */
    public FormEnum coocs(
        final int[] pivotIds, 
        final int left,
        final int right,
        final BitSet docFilter
    ) throws IOException
    {
        if (left < 0 || right < 0 || (left + right) < 1) {
            throw new IllegalArgumentException(
                "left=" + left + " right=" + right
                + " not enough context to extract co-occurrences."
            );
        }
        if (pivotIds == null || pivotIds.length == 0) {
            throw new IllegalArgumentException("Pivot term(s) missing, A set of form Ids is required");
        }
        // 
        int[] pivotLookup = IntList.uniq(pivotIds);
        // 1. collect all positions of pivots in the lucene index for accepted docs
        RowcolQueue docposList = positions(fieldText.bytesSorted(pivotLookup), docFilter);

        // 2. loop on all pivot position by doc
        // for each doc, a bit set is used to record the relevant positions
        // this will avoid counting interferences when search occurrences are close
        FormEnum formEnum = new FormEnum(fieldText);
        formEnum.formId4freq = new long[maxForm];
        formEnum.freqAll = 0;
        formEnum.formId4hits = new int[maxForm];
        
        BitSet formByDoc = new FixedBitSet(maxForm);
        IntBuffer intRail = channelMap.rewind().asIntBuffer().asReadOnlyBuffer(); // the rail
        BitSet form4context = new FixedBitSet(maxForm);
        int docLast = -1;
        
        int toLast = -1; // remember last from to not recount coocs
        while (docposList.hasNext()) {
            docposList.next();
            final int docId = docposList.row();
            final int pivotIndex = docposList.col();
            // end of a doc
            if (docLast != docId) {
                formByDoc.clear();
                toLast = -1;
            }
            docLast = docId;
            // loop in this context
            final int docOffset = docId4offset[docId];
            final int docLen = docId4len[docId];
            // stats for pivot here, because of context overlap
            final int pivotId = intRail.get(docOffset + pivotIndex);
            formEnum.formId4freq[pivotId]++;
            if (!formByDoc.get(pivotId)) {
                formEnum.formId4hits[pivotId]++;
                formByDoc.set(pivotId);
            }
            
            // load the document rail and loop on the context to count co-occurrents
            final int from = Math.max(0, Math.max(toLast + 1, pivotIndex - left));
            final int to = Math.min(docLen, pivotIndex + 1 + right);
            toLast = to;
            // loop on this context to collect the known words from which to get a matrix
            form4context.clear();
            for (int formIndex = from; formIndex < to; formIndex++) {
                final int formId = intRail.get(docOffset + formIndex);
                if (Arrays.binarySearch(pivotLookup, formId) > -1) {
                    continue; // pivot is counted upper, because of context overlap
                }
                if (formId < 1) {
                    continue;
                }
                // already seen in this context (ex: le, un…)
                if (form4context.get(formId)) {
                    continue;
                }
                form4context.set(formId);
                formEnum.formId4freq[formId]++;
                formEnum.freqAll++;
                if (!formByDoc.get(formId)) {
                    formEnum.formId4hits[formId]++;
                    formByDoc.set(formId);
                }
            }
        }
        return formEnum;
    }
    
    /**
     * Build a square matrix of co-occurencies.
     * cooc[3][5] = 2 means, for pivot word with formId=3, there are 2 co-occurences
     * of word with formId=5, in the context of <code>-left</code> and <code>+right</code>
     * words around the pivot.
     * 
     * @param maxForm size of the side of the square matrix to return, allow to prune low frequencies occurrences.
     * @param left width of context before a pivot.
     * @param right width of context after a pivot.
     * @param docFilter optional, set of lucene internal docId to restrict collect.
     * @return a co-occureny matrix.
     * @throws IOException lucene errors.
     */
    public CoocMat coocMat(
        final int left,
        final int right,
        final TagFilter tagFilter,
        final int freqMin,
        final BitSet docFilter
    ) throws IOException {
        if (left < 0 || right < 0 || (left + right) < 1) {
            throw new IllegalArgumentException(
                "left=" + left + " right=" + right
                + " not enough context to extract co-occurrences."
            );
        }
        final boolean hasFilter = (docFilter != null);
        final BitSet formFilter;
        if (tagFilter != null) {
            formFilter = fieldText.formFilter(tagFilter);
        }
        else {
            formFilter = new FixedBitSet(maxForm);
            ((FixedBitSet)formFilter).set(1, maxForm);
        }
        // unset all words below minFreq
        if (freqMin > 0) {
            for (int formId = 0; formId < maxForm; formId++) {
                if (fieldText.occs(formId) < freqMin) formFilter.clear(formId);
            }
        }
        CoocMat coocMat = new CoocMat(formFilter);
        
        IntRoller roll = new IntRoller(1 + left + right);
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (this.docId4len[docId] == 0)
                continue; // deleted or with no value for this field
            if (hasFilter && !docFilter.get(docId))
                continue; // document not in the filter
            bufInt.position(this.docId4offset[docId]);
            
            roll.fill(-1);
            for (int i = 0, max = this.docId4len[docId]; i < max; i++) {
                final int formId = bufInt.get();
                if (!formFilter.get(formId)) continue;
                roll.add(formId);
                final int pivot = roll.get(-right);
                if (pivot < 0) continue;
                for (int rollPos = -(left + right); rollPos <= 0; rollPos++) {
                    if (rollPos == (- right)) continue;
                    final int cooc = roll.get(rollPos);
                    if (cooc < 0) continue;
                    coocMat.inc(pivot, cooc);
                }
            }
        }
        return coocMat;
    }


    /**
     * Loop on a set of pivots, explore their context,
     * record edges between a set of cooccurents in these contexts.
     * The set coocIds should have been obtained by {@link #coocs(int[], int, int, BitSet)}.
     * 
     * @param pivotIds set of formId from a {@link FieldText}, word pivots to search around.
     * @param left     left size of context in tokens.
     * @param right    right size of context in tokens.
     * @param coocIds  set of formId from a {@link FieldText}, words to search in the contexts
     * @param docFilter optional, set of lucene internal docId to restrict collect.
     * @return a matrix of formId pairs with count for each pair.
     * @throws IOException lucene errors.
     */
    public EdgeMatrix edges(final int[] pivotIds, final int left, final int right, int[] coocIds, final BitSet docFilter) throws IOException
    {
        if (left < 0 || right < 0 || (left + right) < 1) {
            throw new IllegalArgumentException("left=" + left + " right=" + right
                    + " not enough context to explore edges.");
        }
        // 1. collect all positions of pivots in the lucene index for accepted docs
        BytesRef[] pivotBytes = fieldText.bytesSorted(pivotIds);
        RowcolQueue docposList = positions(pivotBytes, docFilter);
        IntBuffer intRail = channelMap.rewind().asIntBuffer().asReadOnlyBuffer(); // the rail
        // 2. loop on pivot and load edges between selected nodes
        Map<Integer, Long> nodes = new HashMap<>();
        for (int formId: pivotIds) {
            nodes.put(formId, fieldText.occs(formId));
        }
        for (int formId: coocIds) {
            nodes.put(formId, fieldText.occs(formId));
        }
        EdgeMatrix matrix = new EdgeMatrix(nodes, fieldText.occsAll(), false);
        final IntList nodeIndexes = new IntList();
        while (docposList.hasNext()) {
            docposList.next();
            final int docId = docposList.row();
            final int docOffset = docId4offset[docId];
            final int docLen = docId4len[docId];
            final int pos = docposList.col();
            final int from = Math.max(0, pos - left);
            final int to = Math.min(docLen, pos + 1 + right);
            // loop on this context to collect the known words from which to get a matrix
            nodeIndexes.clear();
            for (int formIndex = from; formIndex < to; formIndex++) {
                final int formId = intRail.get(docOffset + formIndex);
                if (formId < 1) {
                    continue;
                }
                final Integer nodeIndex = matrix.nodeIndex(formId);
                if (nodeIndex == null) continue;
                nodeIndexes.push(nodeIndex);
            }
            // double loop on the collected nodeIndex to fill the matrix of edges
            final int nodeIndexesSize = nodeIndexes.size();
            if (nodeIndexesSize < 2) {
                continue;
            }
            for (int x = 0; x < nodeIndexesSize - 1; x++) {
                for (int y = x + 1; y < nodeIndexesSize; y++) {
                    matrix.incByIndex(nodeIndexes.get(x), nodeIndexes.get(y));
                }
            }
        }
        return matrix;
    }

    /**
     * With a set of int formIds, run accross full or part of rails, to collect
     * co-occs between those selected words.
     * 
     * @param formIds set of words as formId from a {@link FieldText}.
     * @param distance maximal distance in tokens between collected pairs.
     * @param docFilter optional, set of lucene internal docId to restrict collect.
     * @return a formIds x formIds matrix with count for each pair.
     */
    public EdgeMatrix edges(final int[] formIds, final int distance, final BitSet docFilter)
    {
        // loop on docs
        // loop on occs
        // push edges
        if (formIds == null || formIds.length == 0) {
            throw new IllegalArgumentException("Search term(s) missing, A set of form Ids is required");
        }
        // filter documents
        /*
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (lenByDoc[docId] == 0) {
                continue; // deleted or with no value for this field
            }
            if (docFilter != null && !docFilter.get(docId)) {
                continue;
            }
            span.clear();
            bufInt.position(indexByDoc[docId]);
            for (int position = 0, max = lenByDoc[docId]; position < max; position++) {
                int formId = bufInt.get();
                span.push(position, formId);
            }
        }
        return span.edges();
        */
        throw new UnsupportedOperationException("Bugs here, use edges(nodeId, left, right, nodeIds, docFilter) instead.");
    }
    
    public void export(
        String outFile,
        final BitSet docFilter,
        final TagFilter tagFilter
    ) throws IOException {
        final boolean hasFilter = (docFilter != null);
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();
        BytesRef bytes = new BytesRef();
        BitSet formFilter = this.fieldText.formFilter(tagFilter);
        
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            for (int docId = 0; docId < maxDoc; docId++) {
                if (this.docId4len[docId] == 0)
                    continue; // deleted or with no value for this field
                if (hasFilter && !docFilter.get(docId))
                    continue; // document not in the filter
                bufInt.position(this.docId4offset[docId]);
                for (int i = 0, max = this.docId4len[docId]; i < max; i++) {
                    final int formId = bufInt.get();
                    final int flag = fieldText.formId4flag[formId];
                    if (flag == TagFr.PUNsection.no) {
                        out.write('\n');
                        out.write('\n');
                        continue;
                    }
                    else if (flag == TagFr.PUNpara.no) {
                        out.write('\n');
                        out.write('\n');
                        continue;
                    }
                    else if (flag == TagFr.PUNsent.no) {
                        // out.write(10);
                        continue;
                    }
                    else if (TagFr.parent(flag) == TagFr.PUN) {
                        continue;
                    }
                    else if (formFilter != null && !formFilter.get(formId)) {
                        continue;
                    }
                    // chain locutions
                    else if (fieldText.isLocution(formId)) {
                        this.dic.get(formId, bytes);
                        for (int j = bytes.offset; j < (bytes.offset + bytes.length); j++) {
                            byte b = bytes.bytes[j];
                            if (b == 32) b = 95;
                            out.write(b);
                        }
                        out.write(32);
                    }
                    else {
                        this.dic.get(formId, bytes);
                        out.write(bytes.bytes, bytes.offset, bytes.length);
                        out.write(32);
                    }
                }
            }
            out.flush();
        }
    }


    /**
     * Loop on the rail to find expression (2 plain words with possible stop words
     * between but not holes or punctuation).
     * 
     * @param docFilter optional, a set of lucene internal doc id for a partition.
     * @param start first word of an expression, a set of tags (stopwords, verbs…) to exclude
     * @param middle words between start and end, a set of tags (stopwords, verbs…) to exclude
     * @param end last word of an expression, a set of tags (stopwords, verbs…) to exclude
     * @return expressions as an {@link Iterable} of edges
     */
    public EdgeMap expressions(final BitSet docFilter, final TagFilter start, final TagFilter middle, final TagFilter end)
    {

        BitSet formStart = fieldText.formFilter(start);
        BitSet formMiddle = fieldText.formFilter(middle);
        BitSet formEnd = fieldText.formFilter(end);
        
        final boolean hasPartition = (docFilter != null);

        EdgeMap expressions = new EdgeMap(true);
        BytesRefHash formDic = fieldText.dic;
        // no cost in time and memory to take one int view, seems faster to loop
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();
        // a vector to record formId events
        IntList slider = new IntList();
        Chain chain = new Chain();
        BytesRef bytes = new BytesRef();
        IntPairMutable key = new IntPairMutable();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (docId4len[docId] < 1)
                continue; // deleted or with no value for this field
            if (hasPartition && !docFilter.get(docId))
                continue; // document not in the filter
            bufInt.position(docId4offset[docId]); // position cursor in the rail
            for (int i = 0, max = docId4len[docId]; i < max; i++) {
                final int formId = bufInt.get();
                if (formId < 1) {
                    // error if -1, 0 is hole
                    continue;
                }
                // shall we start an expression ?
                if (slider.isEmpty()) {
                    if (formStart.get(formId)) {
                        slider.push(formId);
                    }
                    continue;
                }
                // shall we close an expression ?
                else if (formEnd.get(formId)) {
                    slider.push(formId); // don’t forget the current formId
                    key.set(slider.first(), formId); // create a key by start-end
                    Edge edge = expressions.get(key);
                    if (edge == null) { // new expression
                        chain.reset();
                        for (int jj = 0, len = slider.size(); jj < len; jj++) {
                            if (jj > 0 && chain.last() != '\'')
                                chain.append(' ');
                            formDic.get(slider.get(jj), bytes);
                            chain.append(bytes.bytes, bytes.offset, bytes.length);
                        }
                        edge = new Edge().sourceId(key.x()).targetId(key.y()).edgeLabel(chain.toString());

                        expressions.put(edge);
                    }
                    edge.inc();
                    // reset candidate compound
                    slider.clear();
                    // end may be the start of new compond
                    if (formStart.get(formId)) {
                        slider.push(formId);
                    }
                    continue;
                }
                // shall we append middle word or clear expression ?
                else {
                    if (formMiddle.get(formId)) {
                        slider.push(formId);
                    }
                    else {
                        slider.clear();
                    }
                }
            }
        }
        return expressions;
    }

    /**
     * Returns underlying {@link FieldText} given to constructor {@link #FieldRail(FieldText)}
     * @return the underlying <code>FieldText</code>
     */
    public FieldText fieldText()
    {
        return fieldText;
    }
    
    /**
     * From a set of documents provided as a BitSet, return a freqlist as an int
     * vector, where index is the formId for the field, the value is count of
     * occurrences of the term. Counts are extracted from stored <i>rails</i>.
     * 
     * @param docFilter set of lucene internal docId to restrict collect.
     * @return array[formId] = freq.
     * @throws IOException Lucene errors.
     */
    public long[] freqs(final BitSet docFilter) throws IOException
    {
        long[] freqs = new long[dic.size()];
        final boolean hasFilter = (docFilter != null);
        int maxDoc = this.maxDoc;
        int[] posInt = this.docId4offset;
        int[] limInt = this.docId4len;

        // if channelMap is copied here as int[], same time as IntBuffer,
        // but memory cost
        // no cost in time and memory to take one int view, seems faster to loop
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (limInt[docId] == 0)
                continue; // deleted or with no value for this field
            if (hasFilter && !docFilter.get(docId))
                continue; // document not in the filter
            bufInt.position(posInt[docId]);
            for (int i = 0, max = limInt[docId]; i < max; i++) {
                int formId = bufInt.get();
                freqs[formId]++;
            }
        }
        return freqs;
    }

    /**
     * Count document size by the positions in the term vector.
     * 
     * @param termVector {@link TermVectors#get(int, String)}.
     * @return count of indexed tokens for this document.
     * @throws IOException lucene errors.
     */
    public int length(Terms termVector) throws IOException
    {
        if (termVector == null)
            return 0;
        int len = Integer.MIN_VALUE;
        TermsEnum tenum = termVector.iterator();
        PostingsEnum postings = null;
        while (tenum.next() != null) {
            postings = tenum.postings(postings, PostingsEnum.POSITIONS);
            postings.nextDoc(); // always one doc
            int freq = postings.freq();
            for (int i = 0; i < freq; i++) {
                int pos = postings.nextPosition();
                if (pos > len) {
                    len = pos;
                }
            }
        }
        return len + 1;
    }

    /**
     * Load in memory the rail file.
     * 
     * @throws IOException Lucene errors.
     */
    private void load() throws IOException
    {
        // file do not exists, store()
        if (!path.toFile().exists())
            store();
        channel = FileChannel.open(path, StandardOpenOption.READ);
        DataInputStream data = new DataInputStream(Channels.newInputStream(channel));
        long version = data.readLong();
        // bad version reproduce
        if (version != reader.getVersion()) {
            data.close();
            channel.close();
            store();
            channel = FileChannel.open(path, StandardOpenOption.READ);
            data = new DataInputStream(Channels.newInputStream(channel));
        }
        final int maxDoc = data.readInt();
        /* OK?
        this.maxDoc = maxDoc;
        */
        int[] posInt = new int[maxDoc];
        int[] limInt = new int[maxDoc];
        int indInt = headerInt + maxDoc;
        for (int i = 0; i < maxDoc; i++) {
            posInt[i] = indInt;
            int docLen = data.readInt();
            limInt[i] = docLen;
            indInt += docLen + 1;
        }
        this.docId4offset = posInt;
        this.docId4len = limInt;
        this.channelMap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    }

    
    /**
     * For an ordered set of forms as bytes, obtained from 
     * {@link FieldCharsAbstract#bytesSorted(CharSequence[])}
     * or {@link FieldCharsAbstract#bytesSorted(int[])},
     * get an ordered list of positions by docId.
     * Used by {@link #coocs(int[], int, int, BitSet)}.
     *
     * @param pivotBytes mandatory, ordered set of terms of this field, as bytes.
     * @param docFilter optional, set of lucene internal docId to limit search.
     * @return an iterator of (row, col) pairs.
     * @throws IOException lucene errors.
     */
    protected RowcolQueue positions(final BytesRef[] pivotBytes, final BitSet docFilter) throws IOException
    {
        // filter documents ?
        final boolean hasFilter = (docFilter != null && docFilter.cardinality() > 0);
        RowcolQueue docposList = new RowcolQueue();
        PostingsEnum postings = null; // reuse
        for (LeafReaderContext context : reader.leaves()) {
            final int docBase = context.docBase;
            LeafReader leaf = context.reader();
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            Bits live = leaf.getLiveDocs();
            for (BytesRef bytes : pivotBytes) {
                if (bytes == null) continue;
                if (!tenum.seekExact(bytes)) {
                    continue;
                }
                postings = tenum.postings(postings, PostingsEnum.POSITIONS);
                if (postings == null) {
                    // ???
                    // continue;
                }
                int docLeaf;
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live != null && !live.get(docLeaf)) continue; // deleted doc
                    int docId = docBase + docLeaf;
                    if (hasFilter && !docFilter.get(docId)) continue; // document not in the filter
                    final int freq = postings.freq();
                    for (int i = 0; i < freq; i++) {
                        final int posPivot = postings.nextPosition();
                        docposList.push(docId, posPivot);
                        /* 
                        // tested, is oK
                        final int formFound = intRail.get(indexDoc + posPivot);
                        if (formFound != formId) {
                            Throw new Exception("formId=" + formId + " != " + formFound);
                        }
                        */
                    }
                }
                
            }
        }
        docposList.uniq(); // sort and eliminate duplicates
        return docposList;
    }
    
    /**
     * Flatten search of a document in a position order, according to the dictionary
     * of search. Write it in a binary buffer, ready to to be stored in a
     * BinaryField. {@link org.apache.lucene.document.BinaryDocValuesField} The
     * buffer could be modified if resizing was needed.
     * 
     * @param terms A term vector of a document with positions.
     * @param buf        A reusable binary buffer to index.
     * @throws IOException Lucene errors.
     */
    public void rail(Terms terms, IntList buf) throws IOException
    {
        if (!terms.hasPositions()) {
            throw new IOException("No positions to extract.");
        }
        buf.clear(); 
        TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef bytes = null;
        int maxpos = -1;
        int minpos = Integer.MAX_VALUE;
        while ((bytes = tenum.next()) != null) {
            int formId = dic.find(bytes);
            if (formId < 0)
                System.out.println("unknown term? \"" + bytes.utf8ToString() + "\"");
            postings = tenum.postings(postings, PostingsEnum.POSITIONS);
            postings.nextDoc(); // always one doc
            int freq = postings.freq();
            for (int i = 0; i < freq; i++) {
                int pos = postings.nextPosition();
                if (pos > maxpos)
                    maxpos = pos;
                if (pos < minpos)
                    minpos = pos;
                buf.set(pos, formId);
            }
        }
    }

    /**
     * Reindex all documents of the text field as an int vector storing search at
     * their positions {@link org.apache.lucene.document.BinaryDocValuesField}. Byte
     * ordering is the java default.
     * 
     * @throws IOException Lucene errors.
     */
    private void store() throws IOException
    {
        final FileLock lock;
        int maxDoc = reader.maxDoc();
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
        lock = channel.lock(); // may throw OverlappingFileLockException if someone else has lock

        // get document sizes
        // fieldText.docOccs is not correct because of holes
        int[] docLen = new int[maxDoc];
        TermVectors tveks = reader.termVectors();
        for (int docId = 0; docId < maxDoc; docId++) {
            Terms termVector = tveks.get(docId, fieldName);
            docLen[docId] = length(termVector);
        }

        long capInt = headerInt + maxDoc;
        for (int i = 0; i < maxDoc; i++) {
            capInt += docLen[i] + 1;
        }

        MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, capInt * 4);
        // store the version
        buf.putLong(reader.getVersion());
        // the intBuffer
        IntBuffer bufint = buf.asIntBuffer();
        bufint.put(maxDoc);
        bufint.put(docLen);
        IntList ints = new IntList();

        tveks = reader.termVectors();
        for (int docId = 0; docId < maxDoc; docId++) {
            Terms termVector = tveks.get(docId, fieldName);
            if (termVector == null) {
                bufint.put(-1);
                continue;
            }
            rail(termVector, ints);
            bufint.put(ints.data(), 0, ints.size());
            bufint.put(-1);
        }
        buf.force();
        channel.force(true);
        lock.close();
        channel.close();
    }
    
    /**
     * A document as a sequence of tokens.
     * 
     * @param docId an internal lucene document id.
     * @return sequence of tokens.
     */
    public String toString(int docId)
    {
        int limit = 100;
        StringBuilder sb = new StringBuilder();
        IntBuffer bufInt = channelMap.position(docId4offset[docId] * 4).asIntBuffer();
        bufInt.limit(docId4len[docId]);
        BytesRef ref = new BytesRef();
        while (bufInt.hasRemaining()) {
            int formId = bufInt.get();
            this.dic.get(formId, ref);
            sb.append(ref.utf8ToString());
            sb.append(" ");
            if (limit-- <= 0) {
                sb.append("[…]");
            }
        }
        return sb.toString();
    }

}
