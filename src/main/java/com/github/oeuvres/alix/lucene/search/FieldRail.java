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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermVectors;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.lucene.Alix;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Edge;
import com.github.oeuvres.alix.util.EdgeMap;
import com.github.oeuvres.alix.util.EdgeRoller;
import com.github.oeuvres.alix.util.EdgeMatrix;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntPairMutable;
import com.github.oeuvres.alix.util.MI;

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
    /** State of the index */
    private final Alix alix;
    /** Name of the reference text field */
    public final String fieldName;
    /** Keep the freqs for the field */
    private final FieldText ftext;
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
    protected int[] posInt;
    /** Index of sizes for each doc */
    protected int[] limInt;

    /**
     * Load rail of words as int, build it as file if necessary.
     * 
     * @param alix a wrapper around a lucene {@link IndexReader}.
     * @param fieldName name of a field.
     * @throws IOException lucene erros.
     */
    public FieldRail(Alix alix, String fieldName) throws IOException {
        FieldInfo info = FieldInfos.getMergedFieldInfos(alix.reader()).fieldInfo(fieldName);
        if (info == null) {
            throw new IllegalArgumentException("Field \"" + fieldName + "\" is not known in this index");
        }
        IndexOptions options = info.getIndexOptions();
        if (options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS && options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
            throw new IllegalArgumentException(
                    "Field \"" + fieldName + "\" of type " + options + " has no POSITIONS (see IndexOptions)");
        }
        this.alix = alix;
        this.ftext = alix.fieldText(fieldName); // build and cache the dictionary for the field
        this.fieldName = ftext.fieldName;
        this.dic = ftext.dic;
        this.maxForm = dic.size();
        this.path = Paths.get(alix.path.toString(), fieldName + ".rail");
        load();
    }
    

    /**
     * Build a cooccurrence freqList in formId order, attached to a FormEnum object.
     * Returns the count of occurences found. This method may need a lot of optional
     * params, set on the FormEnum object.
     * 
     * @param pivotsBytes an ordered set of lucene terms.
     * @param left width of context before a pivot.
     * @param right width of context after a pivot.
     * @param docFilter optional, set of lucene internal docId to restrict collect.
     * @return forms with stats.
     * @throws IOException lucene errors.
     */
    public FormEnum coocs(
        final BytesRef[] pivotsBytes, 
        final int left,
        final int right,
        final BitSet docFilter
    ) throws IOException
    {
        throw new IOException("Not yet implemented.");
        /*
         * @param forms words to score in context of the pivots.
         * @param pivotIds form ids of pivots words.
         * @param left width of context before a pivot.
         * @param right width of context after a pivot.
         * @param mi mutual information algorithm to calculate score.
         * @return count of occurrences found.
         * @throws IOException lucene errors.
        // 1. for accepted docs, collect all positions of pivots in the lucene index
        // 2. then 
        // for each index leave
        // collect "postings" for each term
        // for each doc
        // get position of term found
        if (left < 0 || right < 0 || (left + right) < 1) {
            throw new IllegalArgumentException("left=" + left + " right=" + right
                    + " not enough context to extract co-occurrences.");
        }
        // filter documents
        final boolean hasFilter = (forms.filter != null);

        // filter co-occs by tag
        boolean hasTags = (forms.tags != null);
        // filter co-occs stops
        boolean noStop = (forms.tags != null && forms.tags.nostop());
        // collect “locutions” (words like “parce que”)
        boolean locs = (forms.tags != null && forms.tags.locutions());
        // collect “edges” A B [O] A C => AOx2, ABx2, ACx2, BOx1, COx1, BCx1.
        boolean hasEdges = (forms.edges != null);

        // for future scoring, formOccs is global or relative to filter ? relative seems
        // bad
        // create or reuse arrays in result,
        if (forms.formFreq == null || forms.formFreq.length != maxForm) {
            forms.formFreq = new long[maxForm]; // by term, occurrences counts
        } else {
            Arrays.fill(forms.formFreq, 0);
        }
        // create or reuse hits
        if (forms.formHits == null || forms.formHits.length != maxForm) {
            forms.formHits = new int[maxForm]; // by term, document counts
        } else {
            Arrays.fill(forms.formHits, 0);
        }

        // this vector has been useful, but meaning has been forgotten
        boolean[] formSeen = new boolean[maxForm];
        long found = 0;
        final int END = DocIdSetIterator.NO_MORE_DOCS;
        // collector of scores
        // int dicSize = this.hashDic.size();

        // for each doc, a bit set is used to record the relevant positions
        // this will avoid counting interferences when search occurrences are close
        java.util.BitSet contexts = new java.util.BitSet();
        java.util.BitSet pivots = new java.util.BitSet();
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();
        // loop on leafs
        DirectoryReader reader = alix.reader();
        PostingsEnum postings = null; // reuse
        for (LeafReaderContext context : reader.leaves()) {
            final int docBase = context.docBase;
            LeafReader leaf = context.reader();
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            Bits live = leaf.getLiveDocs();
            // collect all “postings” for the requested search
            // 
            ArrayList<PostingsEnum> termDocs = new ArrayList<PostingsEnum>();
            for (BytesRef bytes : pivotsBytes) {
                if (bytes == null) continue;
                if (!tenum.seekExact(bytes)) {
                    continue;
                }
                postings = tenum.postings(postings, PostingsEnum.FREQS | PostingsEnum.POSITIONS);
                if (postings == null) {
                    continue;
                }
                final int docPost = postings.nextDoc(); // advance cursor to the first doc
                if (docPost == END) {
                    continue;
                }
                termDocs.add(postings);
            }
            // loop on all documents for this leaf
            final int max = leaf.maxDoc();
            final Bits liveDocs = leaf.getLiveDocs();
            final boolean hasLive = (liveDocs != null);
            for (int docLeaf = 0; docLeaf < max; docLeaf++) {
                final int docId = docBase + docLeaf;
                final int docLen = limInt[docId];
                if (hasFilter && !forms.filter.get(docId)) {
                    continue; // document not in the document filter
                }
                if (hasLive && !liveDocs.get(docLeaf)) {
                    continue; // deleted doc
                }
                // reset the positions of the rail
                contexts.clear();
                pivots.clear();
                boolean hit = false;
                // loop on each term iterator to get positions for this doc
                for (PostingsEnum postings : termDocs) {
                    int docPost = postings.docID(); // get current doc for these term postings
                    if (docPost == docLeaf) {
                        // OK
                    } else if (docPost == END) {
                        continue; // end of postings, try next term
                    } else if (docPost > docLeaf) {
                        continue; // postings ahead of current doc, try next term
                    } else if (docPost < docLeaf) {
                        docPost = postings.advance(docLeaf); // try to advance postings to this doc
                        if (docPost > docLeaf)
                            continue; // next doc for this term is ahead current term
                    }
                    if (docPost != docLeaf) {
                        // ? bug ?
                        System.out.println("BUG cooc, docLeaf=" + docLeaf + " docPost=" + docPost);
                    }
                    int freq = postings.freq();
                    if (freq == 0) {
                        // bug ?
                        System.out.println("BUG cooc, term=" + postings.toString() + " docId=" + docId + " freq=0");
                    }

                    hit = true;
                    // term found in this doc, search each occurrence
                    for (; freq > 0; freq--) {
                        final int position = postings.nextPosition();
                        final int fromIndex = Math.max(0, position - left);
                        final int toIndex = Math.min(docLen, position + right + 1); // be careful of end Doc
                        contexts.set(fromIndex, toIndex);
                        pivots.set(position);
                        found++;
                    }
                }
                if (!hit) {
                    continue;
                }
                // count all freqs with pivot
                // partOccs += contexts.cardinality(); // no, do not count holes
                // TODISCUSS substract search from contexts
                // contexts.andNot(search);
                // load the document rail and loop on the contexts to count co-occurrents
                final int posDoc = posInt[docId];
                int pos = contexts.nextSetBit(0);
                int lastpos = 0;
                Arrays.fill(formSeen, false);
                while (pos >= 0) {
                    int formId = bufInt.get(posDoc + pos);
                    boolean isPivot = pivots.get(pos);
                    pos = contexts.nextSetBit(pos + 1);
                    // gap, another context, reset coocs cluster
                    if (hasEdges && pos > lastpos + 1) {
                        forms.edges.declust();
                    }
                    lastpos = pos;
                    // Check words to count
                    if (formId == 0) {
                        continue;
                    }
                    // keep pivots
                    else if (isPivot) {

                    } else if (locs && !ftext.formLoc.get(formId)) {
                        continue;
                    } else if (noStop && ftext.isStop(formId)) {
                        continue;
                    }
                    // filter coocs by tag
                    else if (hasTags && !forms.tags.accept(ftext.formTag[formId])) {
                        continue;
                    }

                    if (hasEdges) {
                        // threshold ?
                        forms.edges.clust(formId);
                    }

                    forms.occsPart++;
                    forms.formFreq[formId]++;
                    // has been useful for a scoring algorithm
                    if (!formSeen[formId]) {
                        forms.formHits[formId]++;
                        formSeen[formId] = true;
                    }
                }
            }
        }
        if (mi != null) {
            score(forms, pivotIds, mi);
        }
        return found;
        */
        
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
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();

        if (docFilter != null) {
            for (int docId = docFilter.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS; docId = docFilter
                    .nextSetBit(docId + 1)) {
                if (limInt[docId] == 0) {
                    continue; // deleted or with no value for this field
                }
                span.clear();
                bufInt.position(posInt[docId]);
                for (int position = 0, max = limInt[docId]; position < max; position++) {
                    int formId = bufInt.get();
                    span.push(position, formId);
                }
            }
        } 
        else {
            for (int docId = 0; docId < maxDoc; docId++) {
                if (limInt[docId] == 0) {
                    continue; // deleted or with no value for this field
                }
                span.clear();
                bufInt.position(posInt[docId]);
                for (int position = 0, max = limInt[docId]; position < max; position++) {
                    int formId = bufInt.get();
                    span.push(position, formId);
                }
            }
        }
        return span.edges();
    }

    /**
     * Calculate best edges for coocurrents around pivot words.
     * First pass, search for the most common coocurrents 
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
        // sort the pivots
        BytesRef[] pivotBytes = FieldCharsAbstract.bytesSorted(this.dic, pivotIds);
        if (pivotBytes == null) {
            return null;
        }
        final int pivotLen = pivotBytes.length;
        if (pivotLen < 1) {
            return null;
        }
        
                
        // normalize the coocId as a sorted set of unique values
        coocIds = IntList.uniq(coocIds);
        EdgeMatrix matrix = new EdgeMatrix(coocIds, false);
        IntList nodeIndexes = new IntList();
        DirectoryReader reader = alix.reader();
        IntBuffer bufInt = channelMap.rewind().asIntBuffer(); // the rail
        final boolean hasDocFilter = (docFilter != null); // filter docs ?
        PostingsEnum postings = null; // reuse
        // loop on leafs, open an index may cost
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            final int docBase = context.docBase;
            TermsEnum tenum = terms.iterator();
            Bits live = leaf.getLiveDocs();
            final boolean hasLive = (live != null);
            for (int pivotI = 0; pivotI < pivotLen; pivotI++) {
                final BytesRef bytes = pivotBytes[pivotI];
                if (bytes == null) continue;
                if (!tenum.seekExact(bytes)) {
                    continue;
                }
                postings = tenum.postings(postings, PostingsEnum.FREQS | PostingsEnum.POSITIONS);
                int docLeaf;
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (hasLive && !live.get(docLeaf)) continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    if (hasDocFilter && !docFilter.get(docId)) continue; // document not in the filter
                    final int docLen = limInt[docId];
                    if (docLen < 2) {
                        continue; // empty doc
                    }
                    int freq = postings.freq();
                    if (freq == 0) {
                        // bug ?
                        System.out.println("BUG cooc, term=" + bytes.utf8ToString() + " docId=" + docId + " freq=0");
                        continue;
                    }
                    // term found in this doc, search each occurrence
                    final int posDoc = posInt[docId];
                    for (int occI = 0; occI < freq; occI++) {
                        int posPivot = -1;
                        try {
                            posPivot = postings.nextPosition();
                        } catch (Exception e) {
                            throw new IOException("" + postings);
                        }
                        if (posPivot < 0) {
                            System.out.println("BUG cooc, term=" + postings.toString() + " docId=" + docId + " pos=0");
                            continue;
                        }
                        nodeIndexes.clear();
                        final int posFrom = Math.max(0, posPivot - left);
                        final int posTo = Math.min(docLen, posPivot + right + 1); // be careful of end Doc
                        // TODO, not thought enough
                        // collect waited formIds for edges in this context
                        for (int pos = posFrom; pos < posTo; pos++) {
                            final int formId = bufInt.get(posDoc + pos);
                            final int nodeIndex = Arrays.binarySearch(coocIds, formId);
                            if (nodeIndex < 0) { // not a waited node
                                continue;
                            }
                            nodeIndexes.push(nodeIndex);
                        }
                        // count edges
                        final int size = nodeIndexes.size();
                        if (size < 2) {
                            continue;
                        }
                        // what is t for ? 
                        for (int x = 0; x < size - 1; x++) {
                            for (int y = x + 1; y < size; y++) {
                                matrix.incByIndex(nodeIndexes.get(x), nodeIndexes.get(y));
                            }
                        }
                    }
                }
            }
        }
        return matrix;
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
            exclude = ftext.formFilter(formFilter);
        } else {
            hasExclude = false;
        }
        final boolean hasPartition = (docFilter != null);

        EdgeMap expressions = new EdgeMap(true);
        int maxDoc = this.maxDoc;
        int[] posInt = this.posInt;
        int[] limInt = this.limInt;
        BytesRefHash formDic = ftext.dic;
        // no cost in time and memory to take one int view, seems faster to loop
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();
        // a vector to record formId events
        IntList slider = new IntList();
        Chain chain = new Chain();
        BytesRef bytes = new BytesRef();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (limInt[docId] == 0)
                continue; // deleted or with no value for this field
            if (hasPartition && !docFilter.get(docId))
                continue; // document not in the filter
            bufInt.position(posInt[docId]); // position cursor in the rail
            IntPairMutable key = new IntPairMutable();
            for (int i = 0, max = limInt[docId]; i < max; i++) {
                final int formId = bufInt.get();
                // pun or hole, reset expression
                if (formId == 0 || ftext.isPunctuation(formId)) {
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
        int[] posInt = this.posInt;
        int[] limInt = this.limInt;

        /*
         * // if channelMap is copied here as int[], same cost as IntBuffer // gain x2
         * if int[] is preloaded at class level, but cost mem int[] rail = new
         * int[(int)(channel.size() / 4)]; channelMap.asIntBuffer().get(rail);
         */
        // no cost in time and memory to take one int view, seems faster to loop
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();
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
        if (version != alix.reader().getVersion()) {
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
        this.posInt = posInt;
        this.limInt = limInt;
        this.channelMap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    }

    
    /**
     * For an ordered set of forms as bytes, obtained from 
     * {@link FieldCharsAbstract#bytesSorted(BytesRefHash, int[])}
     * or {@link AbstractFieldString#bytesSorted(BytesRefHash, int[]),
     * get an ordered list of positions by docId.
     *
     * @param formBytes mandatory, ordered set of terms of this field, as bytes.
     * @param docFilter optional, set of lucene internal docId to limit search.
     * @return
     * @throws IOException 
     */
    private Map<Integer, int[]> positions(final BytesRef[] formsBytes, final BitSet docFilter) throws IOException
    {
        boolean hasFilter = (docFilter != null);
        final IndexReader reader = alix.reader();
        PostingsEnum postings = null;
        final Map<Integer, IntList> work = new HashMap<>();
        // loop on leafs, open an index may cost
        for (LeafReaderContext context : reader.leaves()) {
            final int docBase = context.docBase;
            LeafReader leaf = context.reader();
            Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            TermsEnum tenum = terms.iterator();
            final Bits live = leaf.getLiveDocs();
            // loop on pivots
            for (final BytesRef bytes : formsBytes) {
                if (bytes == null) continue;
                if (!tenum.seekExact(bytes)) {
                    continue;
                }
                postings = tenum.postings(postings, PostingsEnum.FREQS | PostingsEnum.POSITIONS);
                // loop on docs found
                final Bits liveDocs = leaf.getLiveDocs();
                final boolean hasLive = (liveDocs != null);
                int docLeaf; // advance cursor to the first doc
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (hasLive && !live.get(docLeaf)) continue; // deleted doc
                    final int docId = docBase + docLeaf;
                    // is document in the document filter ?
                    if (hasFilter && !docFilter.get(docId))continue;
                    final int freq = postings.freq();
                    if (freq == 0) {
                        // bug ?
                        new IOException("BUG cooc, term=" + bytes.utf8ToString() + " docId=" + docId + " freq=0");
                    }
                    IntList vector = work.get(docId);
                    if (vector == null) vector = new IntList();
                    work.put(docId, vector);
                    // term found in this doc, search each occurrence
                    for (int i = 0; i < freq; i++) {
                        int posPivot = -1;
                        try {
                            posPivot = postings.nextPosition();
                        } catch (Exception e) {
                            throw new IOException("" + postings);
                        }
                        if (posPivot < 0) {
                            throw new IOException("BUG cooc, term=" + postings.toString() + " docId=" + docId + " pos=0");
                        }
                        vector.push(posPivot);
                    }
                }
            }
        }

        return null;
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
        BytesRefHash hashDic = this.dic;
        TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef bytes = null;
        int maxpos = -1;
        int minpos = Integer.MAX_VALUE;
        while ((bytes = tenum.next()) != null) {
            int formId = hashDic.find(bytes);
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
     * Scores a {@link FormEnum} with freqs extracted from co-occurrences extraction
     * in a {@link #coocs(BytesRef[], int, int, BitSet)}. Scoring uses a “mutual information”
     * {@link MI} algorithm (probability like, not tf-idf like). Parameters
     * are
     * 
     * <ul>
     * <li>Oab: count of a form (a) observed in a co-occurrency context (b)</li>
     * <li>Oa: count of a form in full corpus, or in a section (filter)</li>
     * <li>Ob: count of occs of the co-occurrency context</li>
     * <li>N: global count of occs from which is extracted the context (full corpus
     * or filtered section)</li>
     * </ul>
     * 
     * 
     * @param formEnum form enumeration from {@link #coocs(BytesRef[], int, int, BitSet)}.
     * @param pivotIds form ids of pivots words.
     * @param mi mutual information algorithm to calculate score.
     * @throws IOException Lucene errors.
     */
    public void score(final FormEnum formEnum, final int[] pivotIds, final MI mi) throws IOException
    {
        /*
         * Strange, can’t understand why it doesn’t work if (this.ftext.formDic !=
         * results.formDic) { throw new
         * IllegalArgumentException("Not the same fields. Rail for coocs: " +
         * this.ftext.name + ", freqList build with " + results.name + " field"); }
         */
        // if (results.limit == 0) throw new IllegalArgumentException("How many sorted
        // forms do you want? set FormEnum.limit");
        if (formEnum.freqByForm == null || formEnum.freqByForm.length < maxForm) {
            throw new IllegalArgumentException("Scoring this FormEnum required a freqList, set FormEnum.freqs");
        }
        final long N = ftext.occsAll; // global
        // Count of pivot occurrences for MI scorer
        long add = 0;
        for (int formId : pivotIds) {
            add += ftext.occs(formId);
        }
        final long Ob = add;
        int maxForm = ftext.maxForm;
        // reuse score for multiple calculations
        if (formEnum.scoreByform == null || formEnum.scoreByform.length != maxForm) {
            formEnum.scoreByform = new double[maxForm]; // by term, occurrences counts
        } else {
            Arrays.fill(formEnum.scoreByform, 0);
        }
        //
        for (int formId = 0; formId < maxForm; formId++) {
            // No tag filter here, should be done upper
            long Oab = formEnum.freqByForm[formId];
            if (Oab == 0) {
                continue;
            }
            // a form in a cooccurrence, may be more frequent than the pivots (repetition in
            // a large context)
            // this will confuse common algorithms
            if (Oab > Ob) {
                Oab = Ob;
            }
            formEnum.scoreByform[formId] = mi.score(Oab, ftext.occs(formId), Ob, N);
        }
        // results is populated of scores, do not sort here, let consumer choose
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
        DirectoryReader reader = alix.reader();
        int maxDoc = reader.maxDoc();
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
        lock = channel.lock(); // may throw OverlappingFileLockException if someone else has lock

        // get document sizes
        // fieldText.docOccs is not correct because of holes
        int[] docLen = new int[maxDoc];
        // new API, not tested
        TermVectors tread = reader.termVectors();
        for (int docId = 0; docId < maxDoc; docId++) {
            Terms termVector = tread.get(docId, fieldName);
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

        // new API, not tested
        tread = reader.termVectors();
        for (int docId = 0; docId < maxDoc; docId++) {
            Terms termVector = tread.get(docId, fieldName);
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
        IntBuffer bufInt = channelMap.position(posInt[docId] * 4).asIntBuffer();
        bufInt.limit(limInt[docId]);
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
