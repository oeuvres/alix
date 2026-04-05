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
package com.github.oeuvres.alix.lucene;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

/**
 * Materializes the results of a {@link Query} as a {@link BitSet} for a single
 * leaf segment, without going through a full {@link IndexSearcher#search} call.
 *
 * <p>
 * This is the per-segment counterpart of {@link BitsCollectorManager}.
 * It is designed to be called inside a loop that already iterates over
 * {@link LeafReaderContext}s — for example inside a custom
 * {@link org.apache.lucene.search.Weight} or a span walker — where building a
 * global bitset first would be wasteful.
 * </p>
 *
 * <p>
 * Modelled on Lucene's own {@code QueryBitSetProducer} from the join module.
 * A transient {@link IndexSearcher} is created from the top-level reader
 * context to rewrite and weight the query; its query cache is explicitly
 * disabled to avoid polluting the global cache.
 * </p>
 *
 * <h2>When to use vs {@link BitsCollectorManager}</h2>
 * <ul>
 *   <li>Use {@code BitsFromQuery} when you already iterate leaf contexts
 *       and need bits <b>per segment</b> — avoids materialising the full
 *       index result upfront.</li>
 *   <li>Use {@link BitsCollectorManager} when you need a <b>global</b>
 *       bitset over all segments at once.</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>
 * BitsFromQuery bfq = new BitsFromQuery(filterQuery);
 * for (LeafReaderContext ctx : reader.leaves()) {
 *     BitSet bits = bfq.bits(ctx);  // null if no match in this leaf
 *     if (bits == null) continue;
 *     // use bits for this segment
 * }
 * </pre>
 */
public class BitsFromQuery
{
    private final IndexSearcher searcher;
    private final Weight weight;

    /**
     * Prepares per-segment bit materialisation for {@code query}.
     * The {@code Weight} is computed once here; {@link #bits(LeafReaderContext)}
     * calls the cheap {@code weight.scorer(context)} per leaf.
     *
     * @param searcher active index searcher — reused for rewrite and weight;
     *                 its query cache is respected
     * @param query    filter query
     * @throws IOException on Lucene I/O errors
     */
    public BitsFromQuery(final IndexSearcher searcher, final Query query) throws IOException
    {
        this.searcher = searcher;
        final Query rewritten = searcher.rewrite(query);
        this.weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1f);
    }
    
    /**
     * Returns matching docIds across all segments as a single global
     * {@link FixedBitSet}, with globally-offset docIds.
     *
     * <p>
     * This is equivalent to {@link BitsCollectorManager} but reuses the
     * {@link Weight} already computed at construction, avoiding a second
     * rewrite. Prefer this when you need a global bitset and already hold
     * a {@code BitsFromQuery} instance.
     * </p>
     *
     * @return global bitset of matching docIds, never {@code null}
     * @throws IOException on Lucene I/O errors
     */
    public FixedBitSet bits() throws IOException
    {
        final int maxDoc = searcher.getIndexReader().maxDoc();
        final FixedBitSet result = new FixedBitSet(maxDoc);
        for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
            final Scorer scorer = weight.scorer(ctx);
            if (scorer == null) continue;
            final int docBase = ctx.docBase;
            final int leafMax = ctx.reader().maxDoc();
            final BitSet leafBits = BitSet.of(scorer.iterator(), leafMax);
            // copy leaf-local bits into global result with docBase offset
            int docLeaf = leafBits.nextSetBit(0);
            while (docLeaf != DocIdSetIterator.NO_MORE_DOCS) {
                result.set(docBase + docLeaf);
                docLeaf = leafBits.nextSetBit(docLeaf + 1);
            }
        }
        return result;
    }

    /**
     * Returns matching docIds for one leaf segment as a {@link BitSet},
     * or {@code null} if this segment has no matches.
     *
     * <p>
     * DocIds in the returned bitset are <b>segment-local</b> (0-based within
     * this leaf). Add {@code context.docBase} to translate to global docIds.
     * </p>
     *
     * @param context leaf segment context
     * @return per-leaf bitset, or {@code null}
     * @throws IOException on Lucene I/O errors
     */
    public BitSet bits(final LeafReaderContext context) throws IOException
    {
        final Scorer scorer = weight.scorer(context);
        if (scorer == null) return null;
        return BitSet.of(scorer.iterator(), context.reader().maxDoc());
    }
}
