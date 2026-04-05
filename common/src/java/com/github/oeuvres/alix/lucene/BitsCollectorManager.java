package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.FixedBitSet;

/**
 * Not yet tested
 */
public class BitsCollectorManager implements CollectorManager<BitsCollector, FixedBitSet>
{
    final int maxDoc;

    /**
     * Build a manager from a lucene searcher to get maximum docId with {@link IndexReader#maxDoc()}.
     * 
     * @param searcher Lucene searcher to get results from.
     */
    public BitsCollectorManager(final IndexSearcher searcher) {
        maxDoc = searcher.getIndexReader().maxDoc();
    }

    /**
     * Build a manager from the maximum docId of the lucene reader {@link IndexReader#maxDoc()}.
     * If maxDoc is too small for the index, errors may be thrown if docId &gt; maxDoc are found.
     * 
     * @param maxDoc Biggest docId + 1 for this lucene index.
     */
    public BitsCollectorManager(final int maxDoc) {
        this.maxDoc = maxDoc;
    }

    @Override
    public BitsCollector newCollector() throws IOException
    {
        return new BitsCollector(maxDoc);
    }

    @Override
    public FixedBitSet reduce(Collection<BitsCollector> collectors) throws IOException
    {
        FixedBitSet bits = null;
        for (BitsCollector c : collectors) {
            if (bits == null) {
                bits = c.bits();
                continue;
            }
            bits.or(c.bits());
        }
        return bits;
    }
    
    /**
     * Collect found documents as a set of docids in a bitSet. Caching should be
     * ensure by user.
     * 
     * <pre>
     * CollectorBits colBits = new CollectorBits(searcher);
     * searcher.search(myQuery, colBits);
     * final BitSet bits = colBits.bits();
     * for (int docId = bits.nextSetBit(0), max = bits.length(); docId &lt; max; docId = bits.nextSetBit(docId + 1)) {
     *     out.print(", " + docId);
     * }
     * </pre>
     */
    static public class BitsCollector extends SimpleCollector implements Collector
    {
        /** The bitset (optimized for sparse or all bits) */
        private FixedBitSet bits;
        /** Number of hits */
        private int hits = 0;
        /** Current context reader */
        LeafReaderContext context;
        /** Current docBase for the context */
        int docBase;

        /**
         * Build Collector with the destination searcher to have maximum docId {@link IndexReader#maxDoc()}.
         * 
         * @param searcher A lucene searcher.
         */
        public BitsCollector(IndexSearcher searcher) {
            bits = new FixedBitSet(searcher.getIndexReader().maxDoc());
        }

        /**
         * Build collector from the maximum docId of the lucene reader {@link IndexReader#maxDoc()}.
         * If maxDoc is too small for the index, errors may be thrown if docId &gt; maxDoc are found.
         * 
         * @param maxDoc Biggest docId + 1 for this lucene index.
         */
        public BitsCollector(int maxDoc) {
            bits = new FixedBitSet(maxDoc);
        }

        /**
         * Get the document filter.
         * 
         * @return A bitSet of docId.
         */
        public FixedBitSet bits()
        {
            return bits;
        }

        /**
         * Get current number of hits (doc found).
         * 
         * @return Count of documents found.
         */
        public int hits()
        {
            return hits;
        }

        @Override
        protected void doSetNextReader(LeafReaderContext context) throws IOException
        {
            this.context = context;
            this.docBase = context.docBase;
        }

        @Override
        public void collect(int docLeaf) throws IOException
        {
            bits.set(docBase + docLeaf);
            hits++;
        }

        @Override
        public ScoreMode scoreMode()
        {
            return ScoreMode.COMPLETE_NO_SCORES;
        }

    }
}