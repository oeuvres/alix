package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanScorer;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.TopScoreDocCollectorManager;

/**
 * Iterates over documents matching a {@link SpanQuery}, exposing the matched
 * span token positions for each document, in score, sort, or natural order.
 *
 * <h2>Collection strategy — single pass</h2>
 * <p>A custom {@link Collector} wraps Lucene's own {@link TopFieldCollector}
 * or {@link TopScoreDocCollector}. In {@link LeafCollector#setScorer}, the
 * scorer is cast to {@link SpanScorer} — or located among its children when
 * a filter wraps the span query — giving access to the {@link Spans} already
 * advanced to the current document. Span positions are drained immediately
 * in {@link LeafCollector#collect}: no second pass, no extra posting read.</p>
 *
 * <h2>Filter query</h2>
 * <p>Filters (date range, tags…) are passed as {@code filterQuery} and
 * combined with the span query as {@code MUST+FILTER}. They restrict matching
 * documents but do not affect the score. The {@link SpanScorer} is always the
 * child scorer driving positions.</p>
 *
 * <h2>Sort cases</h2>
 * <ul>
 *   <li>{@code sort == null} — natural (index) order; all matching documents
 *       are collected with no priority-queue overhead.</li>
 *   <li>{@code Sort.RELEVANCE} — BM25 span score order via
 *       {@link TopScoreDocCollectorManager}.</li>
 *   <li>any other {@link Sort} — DocValues sort via
 *       {@link TopFieldCollectorManager}.</li>
 * </ul>
 *
 * <h2>Span position convention</h2>
 * <p>{@link #spanStart} is inclusive; {@link #spanEnd} is exclusive (one past
 * the last matched token position). This matches Lucene's {@link Spans}
 * contract.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SpanQuery spanQ = new SpanPivotParser("text", 19).parse("libre, responsable");
 * Query filter = new TermQuery(new Term("tag", "philosophy"));
 *
 * try (SpanDocs sd = SpanDocs.search(searcher, spanQ, filter, Sort.RELEVANCE, 100)) {
 *     while (sd.next()) {
 *         int   docId = sd.docId();
 *         float score = sd.score();
 *         for (int i = 0; i < sd.spanCount(); i++) {
 *             int start = sd.spanStart(i);
 *             int end   = sd.spanEnd(i);   // exclusive
 *         }
 *     }
 * }
 * }</pre>
 */
public final class SpanDocs implements Closeable {

    /** Global Lucene doc IDs in iteration order. */
    private final int[] docIds;

    /** BM25 scores parallel to {@link #docIds}; {@code NaN} when not scored. */
    private final float[] scores;

    /**
     * {@code spanBase[i]} is the first index in {@link #spanData} for document
     * {@code i}. Span count = {@code (spanBase[i+1] - spanBase[i]) >> 1}.
     */
    private final int[] spanBase;

    /** Flat span storage: interleaved (startPosition, endPosition) pairs, endPosition exclusive. */
    private final int[] spanData;

    /** Iteration cursor; -1 before first {@link #next()}. */
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
     * Executes the span query and collects matched documents with their span
     * positions in a single pass.
     *
     * @param searcher    cached index searcher (not closed by this class)
     * @param spanQuery   span query for matching and position extraction
     * @param filterQuery optional filter (date range, tags…); {@code null} if none
     * @param sort        {@code null} = natural index order (all docs collected);
     *                    {@link Sort#RELEVANCE} = BM25 score order;
     *                    any other {@link Sort} = DocValues-based order
     * @param maxHits     maximum hits to collect; ignored when {@code sort == null}
     * @return iterator positioned before the first document
     * @throws IOException if a Lucene I/O error occurs
     */
    public static SpanDocs search(
        final IndexSearcher searcher,
        final Query query,
        final Sort sort,
        final int maxHits
    ) throws IOException {
        final SpanCollector collector = new SpanCollector(sort, maxHits);
        searcher.search(query, collector);
        return collector.build();
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
     * Returns the BM25 score of the current document, or {@link Float#NaN}
     * when the query ran in natural or DocValues sort order.
     *
     * @return score, or {@code NaN}
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
     * @return end position (exclusive); last matched token is at {@code spanEnd(i) - 1}
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
        // nothing owned
    }

    /**
     * Single-pass {@link Collector} that simultaneously drives Lucene's own
     * sorting collector and drains span positions from the {@link SpanScorer}.
     *
     * <p>When {@code sort == null} all documents are appended in index order
     * with no delegate. When a filter is present the top-level scorer is a
     * conjunction scorer; {@link #findSpanScorer} locates the {@link SpanScorer}
     * child by traversing {@link Scorable#getChildren()}.</p>
     */
    private static final class SpanCollector implements Collector {

        private final Sort sort;
        private final TopScoreDocCollector scoreCollector;
        private final TopFieldCollector    fieldCollector;

        private int[]   docIds   = new int[256];
        private float[] scores   = new float[256];
        private int[]   spanBase = new int[257];
        private int[]   spanData = new int[1024];
        private int docCount  = 0;
        private int spanCount = 0;

        SpanCollector(final Sort sort, final int maxHits) throws IOException {
            this.sort = sort;
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
            return ScoreMode.COMPLETE;
        }

        @Override
        public LeafCollector getLeafCollector(final LeafReaderContext ctx) throws IOException {
            final Collector delegate = scoreCollector != null ? scoreCollector : fieldCollector;
            final LeafCollector delegateLeaf = delegate == null ? null : delegate.getLeafCollector(ctx);
            final int docBase = ctx.docBase;

            return new LeafCollector() {

                private Spans   spans;
                private Scorable currentScorer;

                @Override
                public void setScorer(final Scorable scorer) throws IOException {
                    if (delegateLeaf != null) delegateLeaf.setScorer(scorer);
                    currentScorer = scorer;
                    final SpanScorer ss = findSpanScorer(scorer);
                    spans = ss != null ? ss.getSpans() : null;
                }

                @Override
                public void collect(final int leafDoc) throws IOException {
                    if (delegateLeaf != null) delegateLeaf.collect(leafDoc);
                    if (spans == null) return;

                    float score = Float.NaN;
                    try {
                        score = currentScorer.score();
                    } catch (UnsupportedOperationException ignored) {
                    }

                    if (docCount == docIds.length) {
                        final int newCap = docIds.length * 2;
                        docIds   = Arrays.copyOf(docIds,   newCap);
                        scores   = Arrays.copyOf(scores,   newCap);
                        spanBase = Arrays.copyOf(spanBase, newCap + 1);
                    }
                    docIds[docCount]  = docBase + leafDoc;
                    scores[docCount]  = score;

                    while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                        if (spanCount + 2 > spanData.length) {
                            spanData = Arrays.copyOf(spanData, spanData.length * 2);
                        }
                        spanData[spanCount++] = spans.startPosition();
                        spanData[spanCount++] = spans.endPosition();
                    }
                    spanBase[++docCount] = spanCount;
                }
            };
        }

        /**
         * Assembles the final {@link SpanDocs}, reordered to match the delegate
         * collector's sorted output when a sort was requested.
         */
        SpanDocs build() throws IOException {
            final int n = docCount;
            final int[]   fDocIds   = Arrays.copyOf(docIds,   n);
            final float[] fScores   = Arrays.copyOf(scores,   n);
            final int[]   fSpanBase = Arrays.copyOf(spanBase, n + 1);
            final int[]   fSpanData = Arrays.copyOf(spanData, spanCount);

            if (sort == null) {
                return new SpanDocs(fDocIds, fScores, fSpanBase, fSpanData);
            }

            final TopDocs topDocs = scoreCollector != null
                ? scoreCollector.topDocs()
                : fieldCollector.topDocs();

            final int[] indexByDocId = new int[maxGlobalDoc(fDocIds) + 1];
            Arrays.fill(indexByDocId, -1);
            for (int i = 0; i < n; i++) indexByDocId[fDocIds[i]] = i;

            final int     m       = topDocs.scoreDocs.length;
            final int[]   sDocIds = new int[m];
            final float[] sScores = new float[m];
            final int[]   sBase   = new int[m + 1];
            final int[]   sData   = new int[fSpanData.length];
            int write = 0;
            sBase[0] = 0;
            for (int r = 0; r < m; r++) {
                final int gDoc = topDocs.scoreDocs[r].doc;
                final int i    = indexByDocId[gDoc];
                sDocIds[r]     = gDoc;
                sScores[r]     = topDocs.scoreDocs[r].score;
                final int len  = fSpanBase[i + 1] - fSpanBase[i];
                System.arraycopy(fSpanData, fSpanBase[i], sData, write, len);
                write += len;
                sBase[r + 1] = write;
            }
            return new SpanDocs(sDocIds, sScores, sBase, Arrays.copyOf(sData, write));
        }

        private static int maxGlobalDoc(final int[] docIds) {
            int max = 0;
            for (final int d : docIds) if (d > max) max = d;
            return max;
        }
    }

    /**
     * Finds the {@link SpanScorer} in a scorer tree by recursive traversal of
     * {@link Scorable#getChildren()}.
     *
     * <p>When the effective query is a plain {@link SpanQuery} the scorer is
     * already a {@link SpanScorer}. When a filter wraps it as a
     * {@code BooleanQuery}, the top-level scorer is a conjunction scorer and
     * the {@link SpanScorer} is one of its children.</p>
     *
     * @param scorer root of the scorer tree for the current leaf
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
