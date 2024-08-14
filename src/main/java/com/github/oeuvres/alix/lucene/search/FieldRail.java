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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.logging.Logger;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
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

import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Edge;
import com.github.oeuvres.alix.util.EdgeMap;
import com.github.oeuvres.alix.util.EdgeRoller;
import com.github.oeuvres.alix.util.EdgeMatrix;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntPairMutable;
import com.github.oeuvres.alix.util.RowcolQueue;

/**
 * Persistent storage of full sequence of all document search for a field. Used
 * for co-occurrences stats. Data structure of the file
 * <p>
 * int:maxDoc, maxDoc*[int:docLength], maxDoc*[docLength*[int:formId], int:-1]
 */
// ChronicleMap has been tested, but it is not more than x2 compared to lucene BinaryField, so stay in Lucene
public class FieldRail
{
    static Logger LOGGER = Logger.getLogger(FieldRail.class.getName());
    /** Keep the freqs for the field */
    private final FieldText fieldText;
    /** Name of the reference text field */
    public final String fieldName;
    /** Lucene index reader, cache the state. */
    protected final DirectoryReader reader;
    /** Infos about the lucene field. */
    protected final FieldInfo info;
    /** Dictionary of search for this field */
    protected final BytesRefHash dic;
    /** The path of underlaying file store */
    private final Path path;
    /** Cache a fileChannel for read */
    protected FileChannel channel;
    /** A buffer on file */
    protected MappedByteBuffer channelMap;
    /** Max for docId */
    protected int maxDoc;
    /** Max for formId */
    private final int maxForm;
    /** Size of file header */
    static final int headerInt = 3;
    /** Index of positions for each doc im channel */
    protected int[] indexByDoc;
    /** Index of sizes for each doc */
    protected int[] lenByDoc;

    /**
     * Load rail of words as int, build it as file if necessary.
     * 
     * @param fieldText alix stats on an indexed and tokenized lucene field.
     * @throws IOException lucene errors.
     */
    public FieldRail(FieldText fieldText) throws IOException {
        reader = fieldText.reader;
        info = fieldText.info;
        this.fieldName = fieldText.fieldName;
        if (info == null) {
            throw new IllegalArgumentException("Field \"" + fieldName + "\" is not known in this index");
        }
        IndexOptions options = info.getIndexOptions();
        if (options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS && options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
            throw new IllegalArgumentException(
                    "Field \"" + fieldName + "\" of type " + options + " has no POSITIONS (see IndexOptions)");
        }
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
     * @param pivotBytes an ordered set of lucene terms.
     * @param left width of context before a pivot.
     * @param right width of context after a pivot.
     * @param docFilter optional, set of lucene internal docId to restrict collect.
     * @return forms with freq.
     * @throws IOException lucene errors.
     */
    public FormEnum coocs(
        final BytesRef[] pivotBytes, 
        final int left,
        final int right,
        final BitSet docFilter
    ) throws IOException
    {
        if (left < 0 || right < 0 || (left + right) < 1) {
            throw new IllegalArgumentException("left=" + left + " right=" + right
                    + " not enough context to extract co-occurrences.");
        }


        // 1. collect all positions of pivots in the lucene index for accepted docs
        RowcolQueue docposList = positions(pivotBytes, docFilter);

        // 2. loop on all pivot position by doc
        // for each doc, a bit set is used to record the relevant positions
        // this will avoid counting interferences when search occurrences are close
        FormEnum formEnum = new FormEnum(fieldText);
        formEnum.freqByForm = new long[maxForm];
        formEnum.freqAll = 0;
        formEnum.hitsByForm = new int[maxForm];
        
        BitSet formByDoc = new FixedBitSet(maxForm);
        IntBuffer intRail = channelMap.rewind().asIntBuffer().asReadOnlyBuffer(); // the rail
        BitSet form4context = new FixedBitSet(maxForm);
        int docLast = -1;
        while (docposList.hasNext()) {
            docposList.next();
            final int docId = docposList.row();
            final int pos = docposList.col();
            // end of a doc
            if (docLast != docId) {
                formByDoc.clear();
            }
            docLast = docId;
            // loop in this context
            final int docIndex = indexByDoc[docId];
            final int docLen = lenByDoc[docId];
            // load the document rail and loop on the context to count co-occurrents
            final int from = Math.max(0, pos - left);
            final int to = Math.min(docLen, pos + 1 + right);
            // loop on this context to collect the known words from which to get a matrix
            form4context.clear();
            for (int formIndex = from; formIndex < to; formIndex++) {
                final int formId = intRail.get(docIndex + formIndex);
                if (formId < 1) {
                    continue;
                }
                // already seen in this context (ex: le, un…)
                if (form4context.get(formId)) {
                    continue;
                }
                form4context.set(formId);
                formEnum.freqByForm[formId]++;
                if (!formByDoc.get(formId)) {
                    formEnum.hitsByForm[formId]++;
                    formByDoc.set(formId);
                }
                formEnum.freqAll++;
            }
        }
        return formEnum;
    }

    /**
     * Loop on a set of pivots, explore their context,
     * record edges between a set of cooccurents in these contexts.
     * The set coocIds should have been obtained by {@link #coocs(BytesRef[], int, int, BitSet)}.
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
        EdgeMatrix matrix = new EdgeMatrix(coocIds, false);
        final int[] nodeLookup = matrix.nodeLookup();
        final IntList nodeIndexes = new IntList();
        while (docposList.hasNext()) {
            docposList.next();
            final int docId = docposList.row();
            final int docIndex = indexByDoc[docId];
            final int docLen = lenByDoc[docId];
            final int pos = docposList.col();
            final int from = Math.max(0, pos - left);
            final int to = Math.min(docLen, pos + 1 + right);
            // loop on this context to collect the known words from which to get a matrix
            nodeIndexes.clear();
            for (int formIndex = from; formIndex < to; formIndex++) {
                final int formId = intRail.get(docIndex + formIndex);
                if (formId < 1) {
                    continue;
                }
                final int nodeIndex = Arrays.binarySearch(nodeLookup, formId);
                if (nodeIndex < 0) continue;
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
            throw new IllegalArgumentException("Search term(s) missing, A set of Ids is required");
        }
        EdgeRoller span = new EdgeRoller(formIds, distance);
        // filter documents
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();

        if (docFilter != null) {
            for (int docId = docFilter.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS; docId = docFilter
                    .nextSetBit(docId + 1)) {
                if (lenByDoc[docId] == 0) {
                    continue; // deleted or with no value for this field
                }
                span.clear();
                bufInt.position(indexByDoc[docId]);
                for (int position = 0, max = lenByDoc[docId]; position < max; position++) {
                    int formId = bufInt.get();
                    span.push(position, formId);
                }
            }
        } 
        else {
            for (int docId = 0; docId < maxDoc; docId++) {
                if (lenByDoc[docId] == 0) {
                    continue; // deleted or with no value for this field
                }
                span.clear();
                bufInt.position(indexByDoc[docId]);
                for (int position = 0, max = lenByDoc[docId]; position < max; position++) {
                    int formId = bufInt.get();
                    span.push(position, formId);
                }
            }
        }
        return span.edges();
    }

    /**
     * Loop on the rail to find expression (2 plain words with possible stop words
     * between but not holes or punctuation).
     * 
     * @param docFilter optional, a set of lucene internal doc id for a partition.
     * @param formFilter optional, type of words to exclude from expressions like verbs, etc…
     * @return expressions as an {@link Iterable} of edges
     */
    public EdgeMap expressions(final BitSet docFilter, final TagFilter formFilter)
    {

        final boolean hasExclude;
        BitSet exclude = null;
        if (formFilter != null) {
            hasExclude = true;
            exclude = fieldText.formFilter(formFilter);
        } else {
            hasExclude = false;
        }
        final boolean hasPartition = (docFilter != null);

        EdgeMap expressions = new EdgeMap(true);
        BytesRefHash formDic = fieldText.dic;
        // no cost in time and memory to take one int view, seems faster to loop
        IntBuffer bufInt = channelMap.rewind().asIntBuffer().asReadOnlyBuffer();
        // a vector to record formId events
        IntList slider = new IntList();
        Chain chain = new Chain();
        BytesRef bytes = new BytesRef();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (lenByDoc[docId] == 0)
                continue; // deleted or with no value for this field
            if (hasPartition && !docFilter.get(docId))
                continue; // document not in the filter
            bufInt.position(indexByDoc[docId]); // position cursor in the rail
            IntPairMutable key = new IntPairMutable();
            for (int i = 0, max = lenByDoc[docId]; i < max; i++) {
                final int formId = bufInt.get();
                // pun or hole, reset expression
                if (formId == 0 || fieldText.isPunctuation(formId)) {
                    slider.clear();
                    continue;
                }
                if (hasExclude) {
                    final boolean excluded = exclude.get(formId);
                    // do not start an expression on an excluded word
                    if (excluded) {
                        if (!slider.isEmpty())
                            slider.push(formId);
                        continue;
                    }
                    // reset on verb ? to check
                    // if (Tag.VERB.sameParent(tag)) { slider.reset(); continue; }
                }
                // should be a plain word here
                if (slider.isEmpty()) { // start of an expression
                    slider.push(formId);
                    continue;
                }
                // here we have something to test or to store
                slider.push(formId); // don’t forget the current formId
                key.set(slider.first(), formId);
                Edge edge = expressions.get(key);
                if (edge == null) { // new expression
                    chain.reset();
                    for (int jj = 0, len = slider.size(); jj < len; jj++) {
                        if (jj > 0 && chain.last() != '\'')
                            chain.append(' ');
                        formDic.get(slider.get(jj), bytes);
                        chain.append(bytes);
                    }
                    edge = new Edge(key.x(), key.y(), chain.toString());
                    expressions.put(edge);
                }
                edge.inc();
                // reset candidate compound, and start by current form
                slider.clear().push(formId);
            }
        }
        return expressions;
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
        int[] posInt = this.indexByDoc;
        int[] limInt = this.lenByDoc;

        /*
         * // if channelMap is copied here as int[], same cost as IntBuffer // gain x2
         * if int[] is preloaded at class level, but cost mem int[] rail = new
         * int[(int)(channel.size() / 4)]; channelMap.asIntBuffer().get(rail);
         */
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

        int maxDoc = data.readInt();
        this.maxDoc = maxDoc;
        int[] posInt = new int[maxDoc];
        int[] limInt = new int[maxDoc];
        int indInt = headerInt + maxDoc;
        for (int i = 0; i < maxDoc; i++) {
            posInt[i] = indInt;
            int docLen = data.readInt();
            limInt[i] = docLen;
            indInt += docLen + 1;
        }
        this.indexByDoc = posInt;
        this.lenByDoc = limInt;
        this.channelMap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    }

    
    /**
     * For an ordered set of forms as bytes, obtained from 
     * {@link FieldCharsAbstract#bytesSorted(CharSequence[])}
     * or {@link FieldCharsAbstract#bytesSorted(int[])},
     * get an ordered list of positions by docId.
     * Used by {@link #coocs(BytesRef[], int, int, BitSet)}.
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
        IntBuffer bufInt = channelMap.position(indexByDoc[docId] * 4).asIntBuffer();
        bufInt.limit(lenByDoc[docId]);
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
