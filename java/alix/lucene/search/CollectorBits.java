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

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.SparseFixedBitSet;

/**
 * Collect found documents as a set of docids in a bitSet. Caching should be
 * ensure by user.
 * 
 * <pre>
 * CollectorBits colBits = new CollectorBits(searcher);
 * searcher.search(myQuery, colBits);
 * final BitSet bits = colBits.bits();
 * for (
 *     int docId = bits.nextSetBit(0), max = bits.length();
 *     docId < max;
 *     docId = bits.nextSetBit(docId + 1)
 * ) {
 *     out.print(", " + docId);
 * }
 * </pre>
 *
 * 
 * @author glorieux-f
 */
public class CollectorBits extends SimpleCollector implements Collector
{
    /** The bitset (optimized for sparse or all bits) */
    private BitSet bits;
    /** Number of hits */
    private int hits = 0;
    /** Current context reader */
    LeafReaderContext context;
    /** Current docBase for the context */
    int docBase;

    /**
     * Build Collector with the destination searcher to have maximum docId, IndexReader
     * @param searcher
     */
    public CollectorBits(IndexSearcher searcher)
    {
        bits = new SparseFixedBitSet(searcher.getIndexReader().maxDoc());
    }

    /**
     * Get the document filter.
     */
    public BitSet bits()
    {
        return bits;
    }

    /**
     * Get current number of hits
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
