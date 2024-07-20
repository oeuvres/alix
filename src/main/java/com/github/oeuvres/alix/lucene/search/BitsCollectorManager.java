package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
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
}