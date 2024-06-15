package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * Not yet tested
 */
public class BitsCollectorManager implements CollectorManager<BitsCollector, FixedBitSet> {
    final int maxDoc;
    
    public BitsCollectorManager(final IndexSearcher searcher)
    {
        maxDoc = searcher.getIndexReader().maxDoc();
    }
    public BitsCollectorManager(final int maxDoc)
    {
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