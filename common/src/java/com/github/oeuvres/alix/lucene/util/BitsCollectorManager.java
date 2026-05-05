package com.github.oeuvres.alix.lucene.util;

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.FixedBitSet;

/**
 * Collects matching document ids into a single {@link FixedBitSet} across all
 * segments, suitable for filtering a subsequent docId loop over the whole index.
 *
 * <p>
 * Implements {@link CollectorManager} for compatibility with Lucene 10's
 * parallel search API. Each segment gets its own {@link BitsCollector}
 * (thread-safe isolation); {@link #reduce} OR-merges all per-segment bitsets
 * into one global result.
 * </p>
 *
 * <h2>Typical usage</h2>
 * <pre>
 * FixedBitSet bits = searcher.search(query, new BitsCollectorManager(searcher));
 * for (int docId = bits.nextSetBit(0);
 *      docId != DocIdSetIterator.NO_MORE_DOCS;
 *      docId = bits.nextSetBit(docId + 1)) {
 *     // process docId
 * }
 * </pre>
 *
 */
public class BitsCollectorManager implements CollectorManager<BitsCollectorManager.BitsCollector, FixedBitSet>
{
    /** One greater than the largest possible document id in this index. */
    private final int maxDoc;

    /**
     * Derives {@code maxDoc} from the searcher's reader.
     *
     * @param searcher active index searcher
     */
    public BitsCollectorManager(final IndexSearcher searcher)
    {
        this.maxDoc = searcher.getIndexReader().maxDoc();
    }

    /**
     * Explicit {@code maxDoc} constructor, useful when the searcher is not
     * available at construction time.
     *
     * @param maxDoc {@link IndexReader#maxDoc()} of the target index
     */
    public BitsCollectorManager(final int maxDoc)
    {
        this.maxDoc = maxDoc;
    }

    @Override
    public BitsCollector newCollector()
    {
        return new BitsCollector(maxDoc);
    }

    /**
     * OR-merges all per-segment bitsets into one global result.
     * Returns an empty (all-zeros) bitset if no documents matched.
     */
    @Override
    public FixedBitSet reduce(final Collection<BitsCollector> collectors) throws IOException
    {
        final FixedBitSet result = new FixedBitSet(maxDoc);
        for (BitsCollector c : collectors) {
            result.or(c.bits);
        }
        return result;
    }

    /**
     * Per-segment collector that records matching document ids into a
     * {@link FixedBitSet}. One instance is created per segment by
     * {@link BitsCollectorManager#newCollector()}; instances are never shared
     * across threads.
     */
    public static class BitsCollector extends SimpleCollector
    {
        /** Bit-per-docId map for the whole index (not just this segment). */
        private final FixedBitSet bits;
        /** Running count of documents collected by this instance. */
        private int hits = 0;
        /** Global docId base for the current leaf segment. */
        private int docBase;

        private BitsCollector(final int maxDoc)
        {
            this.bits = new FixedBitSet(maxDoc);
        }

        /**
         * Total documents collected by this segment collector.
         * For the total across all segments, sum after {@link BitsCollectorManager#reduce}.
         *
         * @return hit count for this segment
         */
        public int hits()
        {
            return hits;
        }

        @Override
        protected void doSetNextReader(final LeafReaderContext context) throws IOException
        {
            this.docBase = context.docBase;
        }

        @Override
        public void collect(final int docLeaf)
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