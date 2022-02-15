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
package alix.lucene.search;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import org.apache.lucene.index.DirectoryReader;
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

import alix.fr.Tag.TagFilter;
import alix.lucene.Alix;
import alix.util.Chain;
import alix.util.Edge;
import alix.util.EdgeRoll;
import alix.util.IntList;
import alix.util.IntPair;
import alix.web.OptionMI;

/**
 * Persistent storage of full sequence of all document search for a field. Used
 * for co-occurrences stats. Data structure of the file
 * <p>
 * int:maxDoc, maxDoc*[int:docLength], maxDoc*[docLength*[int:formId], int:-1]
 * 
 * 
 * @author fred
 *
 */
// ChronicleMap has been tested, but it is not more than x2 compared to lucene BinaryField, so stay in Lucene
public class FieldRail
{
    static Logger LOGGER = Logger.getLogger(FieldRail.class.getName());
    /** State of the index */
    private final Alix alix;
    /** Name of the reference text field */
    public final String fname;
    /** Keep the freqs for the field */
    private final FieldText ftext;
    /** Dictionary of search for this field */
    private final BytesRefHash formDic;
    /** The path of underlaying file store */
    private final Path path;
    /** Cache a fileChannel for read */
    private FileChannel channel;
    /** A buffer on file */
    private MappedByteBuffer channelMap;
    /** Max for docId */
    private int maxDoc;
    /** Max for formId */
    private final int maxForm;
    /** Size of file header */
    static final int headerInt = 3;
    /** Index of positions for each doc im channel */
    private int[] posInt;
    /** Index of sizes for each doc */
    private int[] limInt;

    public FieldRail(Alix alix, String field) throws IOException
    {
        this.alix = alix;
        this.ftext = alix.fieldText(field); // build and cache the dictionary for the field
        this.fname = ftext.fname;
        this.formDic = ftext.formDic;
        this.maxForm = formDic.size();
        this.path = Paths.get(alix.path.toString(), field + ".rail");
        load();
    }

    /**
     * Loop on the rail to get bigrams without any intelligence.
     * 
     * @return
     * @throws IOException
     */
    public Map<IntPair, Bigram> bigrams(final BitSet filter) throws IOException
    {
        final boolean hasFilter = (filter != null);
        Map<IntPair, Bigram> dic = new HashMap<IntPair, Bigram>();
        IntPair key = new IntPair();
        int maxDoc = this.maxDoc;
        int[] posInt = this.posInt;
        int[] limInt = this.limInt;
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();
        int lastId = 0;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (hasFilter && !filter.get(docId))
                continue;
            if (limInt[docId] == 0)
                continue; // deleted or with no value for this field
            bufInt.position(posInt[docId]);
            for (int i = 0, max = limInt[docId]; i < max; i++) {
                int formId = bufInt.get();
                // here we can skip holes, pun, or stop words
                // if (formStop.get(formId)) continue;
                key.set(lastId, formId);
                Bigram count = dic.get(key);
                if (count != null)
                    count.inc();
                else
                    dic.put(new IntPair(key), new Bigram(key.x(), key.y()));
                lastId = formId;
            }
        }
        return dic;
    }
    
    /**
     * With a set of int formIds, run accross full or part of rails, to collect co-occs
     * 
     */
    public Iterator<Edge> edges(int[] formIds, int distance, final BitSet filter)
    {
        // loop on docs
        //   loop on occs
        //     push edges
        if (formIds == null || formIds.length == 0) {
            throw new IllegalArgumentException("Search term(s) missing, A set of Ids is required");
        }
        EdgeRoll span = new EdgeRoll(formIds, distance);
        // filter documents
        final boolean hasFilter = (filter != null);
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (limInt[docId] == 0) {
                continue; // deleted or with no value for this field
            }
            if (hasFilter && !filter.get(docId)) {
                continue; // document not in the filter
            }
            span.clear();
            bufInt.position(posInt[docId]);
            for (int position = 0, max = limInt[docId]; position < max; position++) {
                int formId = bufInt.get();
                span.push(position, formId);
            }
        }
        return span.edges();
    }

    /**
     * Build a cooccurrence freqList in formId order, attached to a FormEnum object.
     * Returns the count of occurences found.
     */
    public long coocs(int[] formIds, FormEnum results) throws IOException
    {
        // for each index leave
        //     collect "postings" for each term
        //     for each doc
        //         get position of term found
        if (formIds == null || formIds.length == 0) {
            throw new IllegalArgumentException("Search term(s) missing, FormEnum.search should be not null");
        }
        final int left = results.left;
        final int right = results.right;
        if (left < 0 || right < 0 || (left + right) < 1) {
            throw new IllegalArgumentException("FormEnum.left=" + left + " FormEnum.right=" + right
                    + " not enough context to extract cooccurrences.");
        }
        // filter documents
        final boolean hasFilter = (results.filter != null);
        
        // filter co-occs by tag
        boolean hasTags = (results.tags != null);
        // filter co-occs stops
        boolean noStop = (results.tags != null && results.tags.noStop());
        // collect “locutions” (words like “parce que”)
        boolean locs = (results.tags != null && results.tags.locutions());
        // collect “edges”   A B [O] A C => AOx2, ABx2, ACx2, BOx1, COx1, BCx1. 
        boolean hasEdges = (results.edges != null);

        // for future scoring, formOccs is global or relative to filter ? relative seems bad
        // create or reuse arrays in result, 
        if (results.formOccsFreq == null || results.formOccsFreq.length != maxForm) {
            results.formOccsFreq = new long[maxForm]; // by term, occurrences counts
        }
        else {
            Arrays.fill(results.formOccsFreq, 0);
        }
        // create or reuse hits
        if (results.formDocsHit == null || results.formDocsHit.length != maxForm) {
            results.formDocsHit = new int[maxForm]; // by term, document counts
        }
        else {
            Arrays.fill(results.formDocsHit, 0);
        }

        DirectoryReader reader = alix.reader();
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
        for (LeafReaderContext context : reader.leaves()) {
            final int docBase = context.docBase;
            LeafReader leaf = context.reader();
            // collect all “postings” for the requested search
            ArrayList<PostingsEnum> termDocs = new ArrayList<PostingsEnum>();
            for (String word : results.search) {
                if (word == null) {
                    continue;
                }
                Term term = new Term(fname, word); // do not try to reuse term, false optimisation
                PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS | PostingsEnum.POSITIONS);
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
                if (hasFilter && !results.filter.get(docId)) {
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
                    }
                    else if (docPost == END) {
                        continue; // end of postings, try next term
                    }
                    else if (docPost > docLeaf) {
                        continue; // postings ahead of current doc, try next term
                    }
                    else if (docPost < docLeaf) {
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
                        results.edges.declust();
                    }
                    lastpos = pos;
                    // Check words to count
                    if (formId == 0) { 
                        continue;
                    }
                    // keep pivots
                    else if (isPivot) {
                        
                    }
                    else if (locs && !ftext.formLoc.get(formId)) {
                        continue;
                    }
                    else if (noStop && ftext.isStop(formId)) {
                        continue;
                    }
                    // filter coocs by tag
                    else if (hasTags && !results.tags.accept(ftext.formTag[formId])) {
                        continue;
                    }
                    
                    if (hasEdges) {
                        results.edges.clust(formId);
                    }
                    
                    
                    results.occsPart++;
                    results.formOccsFreq[formId]++;
                    // has been useful for a scoring algorithm
                    if (!formSeen[formId]) {
                        results.formDocsHit[formId]++;
                        formSeen[formId] = true;
                    }
                }
            }
        }
        return found;
    }
    

    

    /**
     * Loop on the rail to find expression (2 plain words with possible stop words
     * between but not holes or punctuation)
     * 
     * @return
     * @throws IOException
     */
    public Map<IntPair, Bigram> expressions(final BitSet docFilter, final TagFilter formFilter) throws IOException
    {

        final boolean hasExclude;
        BitSet exclude = null;
        if (formFilter != null) {
            hasExclude = true;
            exclude = ftext.formRule(formFilter);
        } else {
            hasExclude = false;
        }
        // prepare a rule of the words to exclude as pivots

        final boolean hasFilter = (docFilter != null);
        Map<IntPair, Bigram> expressions = new HashMap<IntPair, Bigram>();
        int[] formPun = ftext.formPun;
        int maxDoc = this.maxDoc;
        int[] posInt = this.posInt;
        int[] limInt = this.limInt;
        BytesRefHash formDic = ftext.formDic;
        // no cost in time and memory to take one int view, seems faster to loop
        IntBuffer bufInt = channelMap.rewind().asIntBuffer();
        // a vector to record formId events
        IntList slider = new IntList();
        Chain chain = new Chain();
        BytesRef bytes = new BytesRef();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (limInt[docId] == 0)
                continue; // deleted or with no value for this field
            if (hasFilter && !docFilter.get(docId))
                continue; // document not in the filter
            bufInt.position(posInt[docId]); // position cursor in the rail
            IntPair key = new IntPair();
            for (int i = 0, max = limInt[docId]; i < max; i++) {
                final int formId = bufInt.get();
                // pun or hole, reset expression
                if (formId == 0 || Arrays.binarySearch(formPun, formId) >= 0) {
                    slider.reset();
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
                    /*
                     * if (Tag.VERB.sameParent(tag)) { slider.reset(); continue; }
                     */
                }
                // should be a plain word here
                if (slider.isEmpty()) { // start of an expression
                    slider.push(formId);
                    continue;
                }
                // here we have something to test or to store
                slider.push(formId); // don’t forget the current formId
                key.set(slider.first(), formId);
                Bigram bigram = expressions.get(key);
                if (bigram == null) { // new expression
                    chain.reset();
                    for (int jj = 0, len = slider.length(); jj < len; jj++) {
                        if (jj > 0 && chain.last() != '\'')
                            chain.append(' ');
                        formDic.get(slider.get(jj), bytes);
                        chain.append(bytes);
                    }
                    bigram = new Bigram(key.x(), key.y(), chain.toString());
                    expressions.put(new IntPair(key), bigram);
                }
                bigram.inc();
                // reset candidate compound, and start by current form
                slider.reset().push(formId);
            }
        }
        return expressions;
    }

    /**
     * From a set of documents provided as a BitSet, return a freqlist as an int
     * vector, where index is the formId for the field, the value is count of
     * occurrences of the term. Counts are extracted from stored <i>rails</i>.
     * 
     * @throws IOException
     */
    public long[] freqs(final BitSet filter) throws IOException
    {
        long[] freqs = new long[formDic.size()];
        final boolean hasFilter = (filter != null);
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
            if (hasFilter && !filter.get(docId))
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
     * Parallel freqs calculation, it works but is more expensive than serial,
     * because of concurrency cost.
     * 
     * @param filter
     * @return
     * @throws IOException
     */
    protected AtomicIntegerArray freqsParallel(final BitSet filter) throws IOException
    {
        // may take big place in mem
        int[] rail = new int[(int) (channel.size() / 4)];
        channelMap.asIntBuffer().get(rail);
        AtomicIntegerArray freqs = new AtomicIntegerArray(formDic.size());
        boolean hasFilter = (filter != null);
        int maxDoc = this.maxDoc;
        int[] posInt = this.posInt;
        int[] limInt = this.limInt;

        IntStream loop = IntStream.range(0, maxDoc).filter(docId -> {
            if (limInt[docId] == 0)
                return false;
            if (hasFilter && !filter.get(docId))
                return false;
            return true;
        }).parallel().map(docId -> {
            // to use a channelMap in parallel, we need a new IntBuffer for each doc, too
            // expensive
            for (int i = posInt[docId], max = posInt[docId] + limInt[docId]; i < max; i++) {
                int formId = rail[i];
                freqs.getAndIncrement(formId);
            }
            return docId;
        });
        loop.count(); // go
        return freqs;
    }

    /**
     * Count document size by the positions in the term vector
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
     * Load and calculate index for the rail file
     * 
     * @throws IOException
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
     * Flatten search of a document in a position order, according to the dictionary
     * of search. Write it in a binary buffer, ready to to be stored in a
     * BinaryField. {@link org.apache.lucene.document.BinaryDocValuesField} The
     * buffer could be modified if resizing was needed.
     * 
     * @param termVector A term vector of a document with positions.
     * @param buf        A reusable binary buffer to index.
     * @throws IOException
     */
    public void rail(Terms termVector, IntList buf) throws IOException
    {
        buf.reset(); // celan all
        BytesRefHash hashDic = this.formDic;
        TermsEnum tenum = termVector.iterator();
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
                buf.put(pos, formId);
            }
        }
    }

    /**
     * Scores a {@link FormEnum} with freqs extracted from co-occurrences extraction
     * in a {@link #coocs(FormEnum)}. Scoring uses a “mutual information”
     * {@link OptionMI} algorithm (probability like, not tf-idf like). Parameters
     * are
     * <li>Oab: count of a form (a) observed in a co-occurrency context (b)
     * <li>Oa: count of a form in full corpus, or in a section (filter)
     * <li>Ob: count of occs of the co-occurrency context
     * <li>N: global count of occs from which is extracted the context (full corpus
     * or filterd section)
     * 
     * @throws IOException
     * 
     */
    public void score(FormEnum results) throws IOException
    {
        if (this.ftext.formDic != results.formDic) {
            throw new IllegalArgumentException("Not the same fields. Rail for coocs: " + this.ftext.fname
                    + ", freqList build with " + results.fieldName + " field");
        }
        // if (results.limit == 0) throw new IllegalArgumentException("How many sorted
        // forms do you want? set FormEnum.limit");
        if (results.occsPart < 1) {
            throw new IllegalArgumentException(
                    "Scoring this FormEnum need the count of occurrences in the part, set FormEnum.partOccs");
        }
        if (results.formOccsFreq == null || results.formOccsFreq.length < maxForm) {
            throw new IllegalArgumentException("Scoring this FormEnum required a freqList, set FormEnum.freqs");
        }
        // A variable for the square scorer
        long add = 0;
        for (String form : results.search) {
            add += ftext.formOccs(form);
        }
        // Count of pivot occurrences for MI scorer
        final long Ob = add;
        // int[] hits = results.hits; // not significant for a transversal cooc
        TagFilter tags = results.tags;
        boolean hasTags = (tags != null);
        boolean noStop = (tags != null && tags.noStop());
        // a bug here, results do not like
        int maxForm = ftext.maxForm;
        // reuse score for multiple calculations
        if (results.formScore == null || results.formScore.length != maxForm)
            results.formScore = new double[maxForm]; // by term, occurrences counts
        else
            Arrays.fill(results.formScore, 0);
        final long N = ftext.occsAll; // global
        OptionMI mi = results.mi;
        if (mi == null) {
            mi = OptionMI.g;
        }
        // 
        for (int formId = 0; formId < maxForm; formId++) {
            // No tag filter here, should be done upper
            long Oab = results.formOccsFreq[formId];
            if (Oab == 0) {
                continue;
            }
            // a form in a cooccurrence, may be more frequent than the pivots (repetition in a large context)
            // this will confuse common algorithms
            if (Oab > Ob) {
                Oab = Ob;
            }
            results.formScore[formId] = mi.score(Oab, ftext.formOccsAll[formId], Ob, N);
        }
        // results is populated of scores, sort it now
        results.sort(FormEnum.Order.score, -1);
    }

    /**
     * Reindex all documents of the text field as an int vector storing search at
     * their positions {@link org.apache.lucene.document.BinaryDocValuesField}. Byte
     * ordering is the java default.
     * 
     * @throws IOException
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
        for (int docId = 0; docId < maxDoc; docId++) {
            Terms termVector = reader.getTermVector(docId, fname);
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

        for (int docId = 0; docId < maxDoc; docId++) {
            Terms termVector = reader.getTermVector(docId, fname);
            if (termVector == null) {
                bufint.put(-1);
                continue;
            }
            rail(termVector, ints);
            bufint.put(ints.data(), 0, ints.length());
            bufint.put(-1);
        }
        buf.force();
        channel.force(true);
        lock.close();
        channel.close();
    }

    /**
     * Tokens of a doc as strings from a byte array
     * 
     * @param rail   Binary version an int array
     * @param offset Start index in the array
     * @param length Length of bytes to consider from offset
     * @return
     * @throws IOException
     */
    public String[] strings(int[] rail) throws IOException
    {
        int len = rail.length;
        String[] words = new String[len];
        BytesRef ref = new BytesRef();
        for (int i = 0; i < len; i++) {
            int formId = rail[i];
            this.formDic.get(formId, ref);
            words[i] = ref.utf8ToString();
        }
        return words;
    }

    public String toString(int docId) throws IOException
    {
        int limit = 100;
        StringBuilder sb = new StringBuilder();
        IntBuffer bufInt = channelMap.position(posInt[docId] * 4).asIntBuffer();
        bufInt.limit(limInt[docId]);
        BytesRef ref = new BytesRef();
        while (bufInt.hasRemaining()) {
            int formId = bufInt.get();
            this.formDic.get(formId, ref);
            sb.append(ref.utf8ToString());
            sb.append(" ");
            if (limit-- <= 0) {
                sb.append("[…]");
            }
        }
        return sb.toString();
    }


    public class Bigram
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
