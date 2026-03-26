package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanScorer;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.index.TermStates;

/**
 * Iterates over documents matching a {@link SpanQuery}, exposing matched span
 * token positions for each document, in score, sort, or natural order.
 *
 * <h2>Collection strategy — single pass</h2>
 * <p>The span query is wrapped in a {@link RecordingSpanQuery} that injects a
 * {@link RecordingSpans} into the scorer. {@link RecordingSpans} intercepts
 * every {@link Spans#nextStartPosition()} call and records the (start, end)
 * pair. This means positions are captured at exactly the moment
 * {@link SpanScorer} traverses them for BM25 frequency computation, with no
 * additional iteration cost. All three sort modes use the same single pass.</p>
 *
 * <h2>Sort cases</h2>
 * <ul>
 *   <li>{@code sort == null} — natural (index) order; all docs collected;
 *       positions drained manually since no scorer triggers traversal.
 *       Recommended when the index was built with a matching
 *       {@code IndexWriterConfig.setIndexSort}.</li>
 *   <li>{@link Sort#RELEVANCE} — BM25 score descending, top {@code maxHits};
 *       the delegate {@link TopScoreDocCollector} triggers {@code score()} which
 *       traverses spans via {@link RecordingSpans}.</li>
 *   <li>any other {@link Sort} — DocValues sort; delegate
 *       {@link TopFieldCollector} also triggers {@code score()} via
 *       {@link ScoreMode#COMPLETE}.</li>
 * </ul>
 *
 * <h2>API</h2>
 * <pre>{@code
 * SpanQuery spanQ = new SpanPivotParser("text", 19).parse("libre, responsable");
 * Query filter = new TermQuery(new Term("tag", "philosophy"));
 *
 * // score order — validate the query
 * try (SpanDocs sd = SpanDocs.search(searcher, spanQ, filter, Sort.RELEVANCE, 500)) {
 *     while (sd.next()) {
 *         int   docId = sd.docId();
 *         float score = sd.score();
 *         for (int i = 0; i < sd.spanCount(); i++) {
 *             int start = sd.spanStart(i); // inclusive
 *             int end   = sd.spanEnd(i);   // exclusive
 *         }
 *     }
 * }
 *
 * // natural order — full corpus scan
 * try (SpanDocs sd = SpanDocs.search(searcher, spanQ, null, null, 0)) { ... }
 * }</pre>
 *
 * <h2>Span position convention</h2>
 * <p>{@link #spanStart} inclusive, {@link #spanEnd} exclusive (Lucene's own
 * {@link Spans} contract).</p>
 */
public final class SpanDocs implements Closeable {

    private final int[]   docIds;
    private final float[] scores;
    private final int[]   spanBase;
    private final int[]   spanData;
    private int cursor = -1;

    private SpanDocs(
        final int[]   docIds,
        final float[] scores,
        final int[]   spanBase,
        final int[]   spanData
    ) {
        this.docIds   = docIds;
        this.scores   = scores;
        this.spanBase = spanBase;
        this.spanData = spanData;
    }

    /**
     * Executes the span query and returns an iterator over matching documents
     * with their span positions.
     *
     * @param searcher    cached index searcher (not closed by this class)
     * @param spanQuery   span query; wrapped internally in a recording proxy
     * @param filterQuery optional filter (date range, tags…); {@code null} if none
     * @param sort        {@code null} = natural index order;
     *                    {@link Sort#RELEVANCE} = BM25 score descending;
     *                    any other {@link Sort} = DocValues sort
     * @param maxHits     maximum hits for score/sort modes; ignored when
     *                    {@code sort == null}
     * @return iterator positioned before the first document
     * @throws IOException if a Lucene I/O error occurs
     */
    public static SpanDocs search(
        final IndexSearcher searcher,
        final SpanQuery spanQuery,
        final Query filterQuery,
        final Sort sort,
        final int maxHits
    ) throws IOException {
        final RecordingSpanQuery recording = new RecordingSpanQuery(spanQuery);
        final Query effectiveQuery = filterQuery == null
            ? recording
            : new BooleanQuery.Builder()
                .add(recording,     BooleanClause.Occur.MUST)
                .add(filterQuery,   BooleanClause.Occur.FILTER)
                .build();
        return searcher.search(effectiveQuery, new SpanCollectorManager(sort, maxHits));
    }

    /**
     * Advances to the next matching document.
     *
     * @return {@code true} if a document is available
     */
    public boolean next() {
        return ++cursor < docIds.length;
    }

    /**
     * Returns the global Lucene doc ID of the current document.
     *
     * @return global doc ID
     */
    public int docId() {
        return docIds[cursor];
    }

    /**
     * Returns the BM25 score, or {@link Float#NaN} when {@code sort == null}.
     *
     * @return score or {@code NaN}
     */
    public float score() {
        return scores[cursor];
    }

    /**
     * Returns the total number of matching documents collected.
     *
     * @return hit count
     */
    public int size() {
        return docIds.length;
    }

    /**
     * Returns the number of spans matched in the current document.
     *
     * @return span count
     */
    public int spanCount() {
        return (spanBase[cursor + 1] - spanBase[cursor]) >> 1;
    }

    /**
     * Returns the end token position (exclusive) of span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return end position (exclusive)
     */
    public int spanEnd(final int i) {
        return spanData[spanBase[cursor] + (i << 1) + 1];
    }

    /**
     * Returns the start token position (inclusive) of span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return start position (inclusive)
     */
    public int spanStart(final int i) {
        return spanData[spanBase[cursor] + (i << 1)];
    }

    /** No-op; provided for try-with-resources symmetry. */
    @Override
    public void close() {
    }

    /**
     * Intercepts every {@link #nextStartPosition()} call to record span
     * positions as {@link SpanScorer} traverses them during BM25 frequency
     * computation. Positions are accumulated per document; {@link #nextDoc}
     * and {@link #advance} reset the counter.
     *
     * <p>After the scorer has called {@link SpanScorer#score()}, the recorded
     * positions are available in {@link #starts} and {@link #ends} up to
     * index {@link #count - 1}.</p>
     */
    static final class RecordingSpans extends Spans {

        private final Spans in;
        int[]  starts = new int[16];
        int[]  ends   = new int[16];
        int    count  = 0;

        RecordingSpans(final Spans in) {
            this.in = in;
        }

        @Override
        public int nextStartPosition() throws IOException {
            final int start = in.nextStartPosition();
            if (start != NO_MORE_POSITIONS) {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                    ends   = Arrays.copyOf(ends,   count * 2);
                }
                starts[count] = start;
                ends[count]   = in.endPosition();
                count++;
            }
            return start;
        }

        @Override public int startPosition()   { return in.startPosition(); }
        @Override public int endPosition()     { return in.endPosition(); }
        @Override public int width()           { return in.width(); }

        @Override
        public int nextDoc() throws IOException {
            count = 0;
            return in.nextDoc();
        }

        @Override
        public int advance(final int target) throws IOException {
            count = 0;
            return in.advance(target);
        }

        @Override public int  docID()  { return in.docID(); }
        @Override public long cost()   { return in.cost(); }

        @Override
        public void doStartCurrentDoc() throws IOException {
            in.doStartCurrentDoc();
        }

        @Override
        public void doCurrentSpans() throws IOException {
            in.doCurrentSpans();
        }

        @Override
        public void collect(final SpanCollector collector) throws IOException {
            in.collect(collector);
        }

        @Override public float positionsCost() { return in.positionsCost(); }

        @Override
        public TwoPhaseIterator asTwoPhaseIterator() {
            return in.asTwoPhaseIterator();
        }
    }

    /**
     * Wraps an inner {@link SpanWeight}, overriding {@link #getSpans} to return
     * {@link RecordingSpans}. Uses {@link SpanQuery#getTermStates} on the inner
     * weight to correctly initialize BM25 scoring in the parent constructor.
     */
    private static final class RecordingSpanWeight extends SpanWeight {

        private final SpanWeight inner;

        RecordingSpanWeight(
            final SpanQuery query,
            final SpanWeight inner,
            final IndexSearcher searcher,
            final float boost
        ) throws IOException {
            super(query, searcher, SpanQuery.getTermStates(List.of(inner)), boost);
            this.inner = inner;
        }

        @Override
        public Spans getSpans(final LeafReaderContext ctx, final Postings p) throws IOException {
            final Spans raw = inner.getSpans(ctx, p);
            return raw == null ? null : new RecordingSpans(raw);
        }

        @Override
        public void extractTermStates(final Map<Term, TermStates> contexts) {
            inner.extractTermStates(contexts);
        }

        @Override
        public boolean isCacheable(final LeafReaderContext ctx) {
            return inner.isCacheable(ctx);
        }

        @Override
        public Explanation explain(final LeafReaderContext ctx, final int doc) throws IOException {
            return inner.explain(ctx, doc);
        }
    }

    /**
     * Wraps a {@link SpanQuery} so that its weight injects {@link RecordingSpans}.
     * Delegates all query methods to the wrapped query.
     */
    private static final class RecordingSpanQuery extends SpanQuery {

        private final SpanQuery inner;

        RecordingSpanQuery(final SpanQuery inner) {
            this.inner = inner;
        }

        @Override
        public String getField() {
            return inner.getField();
        }

        @Override
        public SpanWeight createWeight(
            final IndexSearcher searcher,
            final ScoreMode scoreMode,
            final float boost
        ) throws IOException {
            final SpanWeight innerWeight = inner.createWeight(searcher, scoreMode, boost);
            return new RecordingSpanWeight(inner, innerWeight, searcher, boost);
        }

        @Override
        public Query rewrite(final IndexSearcher searcher) throws IOException {
            final Query rewritten = inner.rewrite(searcher);
            if (rewritten == inner) return this;
            if (rewritten instanceof SpanQuery sq) return new RecordingSpanQuery(sq);
            return rewritten;
        }

        @Override
        public void visit(final QueryVisitor visitor) {
            inner.visit(visitor);
        }

        @Override
        public String toString(final String field) {
            return inner.toString(field);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof RecordingSpanQuery other)) return false;
            return inner.equals(other.inner);
        }

        @Override
        public int hashCode() {
            return inner.hashCode() ^ 0x52454300;
        }
    }

    /**
     * {@link CollectorManager} that creates and reduces {@link SpanCollector}
     * instances. For single-threaded {@link IndexSearcher} exactly one collector
     * is created and returned from {@link #reduce} directly.
     */
    private static final class SpanCollectorManager
        implements CollectorManager<SpanCollectorManager.SpanCollector, SpanDocs> {

        private final Sort sort;
        private final int  maxHits;

        SpanCollectorManager(final Sort sort, final int maxHits) {
            this.sort    = sort;
            this.maxHits = maxHits;
        }

        @Override
        public SpanCollector newCollector() throws IOException {
            return new SpanCollector(sort, maxHits);
        }

        @Override
        public SpanDocs reduce(final Collection<SpanCollector> collectors) throws IOException {
            if (collectors.size() == 1) {
                return collectors.iterator().next().build();
            }
            int totalDocs = 0, totalSpans = 0;
            for (final SpanCollector c : collectors) {
                totalDocs  += c.docCount;
                totalSpans += c.spanCount;
            }
            final int[]   mDocIds   = new int[totalDocs];
            final float[] mScores   = new float[totalDocs];
            final int[]   mSpanBase = new int[totalDocs + 1];
            final int[]   mSpanData = new int[totalSpans];
            int di = 0, si = 0;
            for (final SpanCollector c : collectors) {
                System.arraycopy(c.docIds,   0, mDocIds,   di, c.docCount);
                System.arraycopy(c.scores,   0, mScores,   di, c.docCount);
                System.arraycopy(c.spanData, 0, mSpanData, si, c.spanCount);
                for (int i = 0; i < c.docCount; i++) {
                    mSpanBase[di + i + 1] = si + c.spanBase[i + 1];
                }
                di += c.docCount;
                si += c.spanCount;
            }
            return new SpanDocs(mDocIds, mScores, mSpanBase, mSpanData);
        }

        /**
         * Collects (docId, score, spans) in a single pass. The delegate
         * {@link TopScoreDocCollector} or {@link TopFieldCollector} triggers
         * {@code score()} which drives {@link RecordingSpans} to record positions.
         * For natural order (no delegate), spans are drained manually.
         */
        final class SpanCollector implements Collector {

            private final TopScoreDocCollector scoreCollector;
            private final TopFieldCollector    fieldCollector;

            int[]   docIds   = new int[256];
            float[] scores   = new float[256];
            int[]   spanBase = new int[257];
            int[]   spanData = new int[1024];
            int docCount  = 0;
            int spanCount = 0;

            SpanCollector(final Sort sort, final int maxHits) throws IOException {
                if (sort == null) {
                    scoreCollector = null;
                    fieldCollector = null;
                } else if (sort == Sort.RELEVANCE) {
                    scoreCollector = new TopScoreDocCollectorManager(maxHits, Integer.MAX_VALUE).newCollector();
                    fieldCollector = null;
                } else {
                    scoreCollector = null;
                    fieldCollector = new TopFieldCollectorManager(sort, maxHits, null, Integer.MAX_VALUE).newCollector();
                }
            }

            @Override
            public ScoreMode scoreMode() {
                return (scoreCollector == null && fieldCollector == null)
                    ? ScoreMode.COMPLETE_NO_SCORES
                    : ScoreMode.COMPLETE;
            }

            @Override
            public LeafCollector getLeafCollector(final LeafReaderContext ctx) throws IOException {
                final Collector delegate = scoreCollector != null ? scoreCollector : fieldCollector;
                final LeafCollector delegateLeaf = delegate == null ? null : delegate.getLeafCollector(ctx);
                final int docBase = ctx.docBase;

                return new LeafCollector() {

                    private RecordingSpans recording;
                    private Scorable currentScorer;

                    @Override
                    public void setScorer(final Scorable scorer) throws IOException {
                        if (delegateLeaf != null) delegateLeaf.setScorer(scorer);
                        currentScorer = scorer;
                        final SpanScorer ss = findSpanScorer(scorer);
                        if (ss != null && ss.getSpans() instanceof RecordingSpans rs) {
                            recording = rs;
                        }
                    }

                    @Override
                    public void collect(final int leafDoc) throws IOException {
                        if (docCount == docIds.length) {
                            final int newCap = docIds.length * 2;
                            docIds   = Arrays.copyOf(docIds,   newCap);
                            scores   = Arrays.copyOf(scores,   newCap);
                            spanBase = Arrays.copyOf(spanBase, newCap + 1);
                        }
                        docIds[docCount] = docBase + leafDoc;

                        if (delegateLeaf != null) {
                            // delegate.collect() triggers score() → setFreqCurrentDoc()
                            // → RecordingSpans.nextStartPosition() records all positions
                            delegateLeaf.collect(leafDoc);
                            scores[docCount] = currentScorer.score();
                        } else {
                            // natural order: no score() call, drain manually
                            scores[docCount] = Float.NaN;
                            if (recording != null) {
                                while (recording.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                                }
                            }
                        }

                        if (recording != null) {
                            final int n = recording.count;
                            if (spanCount + n * 2 > spanData.length) {
                                spanData = Arrays.copyOf(spanData,
                                    Math.max(spanData.length * 2, spanCount + n * 2));
                            }
                            for (int i = 0; i < n; i++) {
                                spanData[spanCount++] = recording.starts[i];
                                spanData[spanCount++] = recording.ends[i];
                            }
                        }
                        spanBase[++docCount] = spanCount;
                    }
                };
            }

            SpanDocs build() throws IOException {
                final int     n         = docCount;
                final int[]   fDocIds   = Arrays.copyOf(docIds,   n);
                final float[] fScores   = Arrays.copyOf(scores,   n);
                final int[]   fSpanBase = Arrays.copyOf(spanBase, n + 1);
                final int[]   fSpanData = Arrays.copyOf(spanData, spanCount);

                if (scoreCollector == null && fieldCollector == null) {
                    return new SpanDocs(fDocIds, fScores, fSpanBase, fSpanData);
                }

                final org.apache.lucene.search.TopDocs topDocs = scoreCollector != null
                    ? scoreCollector.topDocs()
                    : fieldCollector.topDocs();

                final int[] byDocId = new int[maxGlobalDoc(fDocIds) + 1];
                Arrays.fill(byDocId, -1);
                for (int i = 0; i < n; i++) byDocId[fDocIds[i]] = i;

                final int     m       = topDocs.scoreDocs.length;
                final int[]   sDocIds = new int[m];
                final float[] sScores = new float[m];
                final int[]   sBase   = new int[m + 1];
                final int[]   sData   = new int[fSpanData.length];
                int write = 0;
                sBase[0] = 0;
                for (int r = 0; r < m; r++) {
                    final int gDoc = topDocs.scoreDocs[r].doc;
                    final int i    = byDocId[gDoc];
                    sDocIds[r]     = gDoc;
                    sScores[r]     = fScores[i];
                    final int len  = fSpanBase[i + 1] - fSpanBase[i];
                    System.arraycopy(fSpanData, fSpanBase[i], sData, write, len);
                    write += len;
                    sBase[r + 1] = write;
                }
                return new SpanDocs(sDocIds, sScores, sBase, Arrays.copyOf(sData, write));
            }

            private static int maxGlobalDoc(final int[] ids) {
                int max = 0;
                for (final int d : ids) if (d > max) max = d;
                return max;
            }
        }
    }

    /**
     * Locates the {@link SpanScorer} in a scorer tree by recursive traversal
     * of {@link Scorable#getChildren()}.
     *
     * <p>When the effective query is a plain {@link SpanQuery} (no filter) the
     * scorer is already a {@link SpanScorer}. When a filter wraps it in a
     * {@link BooleanQuery}, the top-level scorer is a conjunction and the
     * {@link SpanScorer} is one of its children.</p>
     *
     * @param scorer root scorer for the current leaf
     * @return the first {@link SpanScorer} found, or {@code null}
     */
    private static SpanScorer findSpanScorer(final Scorable scorer) throws IOException {
        if (scorer instanceof SpanScorer ss) return ss;
        for (final Scorable.ChildScorable cs : scorer.getChildren()) {
            final SpanScorer found = findSpanScorer(cs.child());
            if (found != null) return found;
        }
        return null;
    }
}
