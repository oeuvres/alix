package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.queries.spans.RecordingSpans;
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
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.TopScoreDocCollectorManager;

/**
 * Iterates over documents matching a {@link SpanQuery}, exposing matched span
 * token positions and character offsets for each document, in score, sort, or
 * natural order.
 *
 * <h2>Collection strategy — single pass</h2>
 * <p>The span query is wrapped in a {@link RecordingSpanQuery} that injects a
 * {@link RecordingSpans} into the scorer. {@link RecordingSpans} intercepts every
 * {@link Spans#nextStartPosition()} call, recording token positions and immediately
 * collecting character offsets from the leaf postings via
 * {@link Spans#collect(org.apache.lucene.queries.spans.SpanCollector)}. Postings
 * are requested at {@link SpanWeight.Postings#OFFSETS} level. All three sort modes
 * use the same single pass with no extra I/O.</p>
 *
 * <h2>Sort cases</h2>
 * <ul>
 *   <li>{@code sort == null} — natural index order, all docs collected.</li>
 *   <li>{@link Sort#RELEVANCE} — BM25 score descending, top {@code maxHits}.</li>
 *   <li>any other {@link Sort} — DocValues sort, top {@code maxHits}.</li>
 * </ul>
 *
 * <h2>Span position and offset convention</h2>
 * <ul>
 *   <li>{@link #spanStart} — token position, inclusive.</li>
 *   <li>{@link #spanEnd} — token position, exclusive (Lucene {@link Spans} contract).</li>
 *   <li>{@link #spanCharStart} — character offset of the first pivot token's start.</li>
 *   <li>{@link #spanCharEnd} — character offset of the last pivot token's end (exclusive).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SpanQuery spanQ = new SpanPivotParser("text", 19).parse("libre, responsable");
 *
 * try (SpanDocs sd = SpanDocs.search(searcher, spanQ, null, Sort.RELEVANCE, 500)) {
 *     while (sd.next()) {
 *         int   docId = sd.docId();
 *         float score = sd.score();
 *         for (int i = 0; i < sd.spanCount(); i++) {
 *             int start     = sd.spanStart(i);     // token, inclusive
 *             int end       = sd.spanEnd(i);       // token, exclusive
 *             int charStart = sd.spanCharStart(i); // char, inclusive
 *             int charEnd   = sd.spanCharEnd(i);   // char, exclusive
 *         }
 *     }
 * }
 * }</pre>
 */
public final class SpanDocs implements Closeable {

    private final int[]   docIds;
    private final float[] scores;

    /**
     * {@code spanBase[i]} is the first index in {@link #spanData} and
     * {@link #charData} for document {@code i}.
     * Span count = {@code (spanBase[i+1] - spanBase[i]) >> 1}.
     */
    private final int[] spanBase;

    /** Token positions: interleaved (startPos, endPos) pairs, endPos exclusive. */
    private final int[] spanData;

    /** Character offsets: interleaved (charStart, charEnd) pairs, indexed like {@link #spanData}. */
    private final int[] charData;

    private int cursor = -1;

    private SpanDocs(
        final int[]   docIds,
        final float[] scores,
        final int[]   spanBase,
        final int[]   spanData,
        final int[]   charData
    ) {
        this.docIds   = docIds;
        this.scores   = scores;
        this.spanBase = spanBase;
        this.spanData = spanData;
        this.charData = charData;
    }

    /**
     * Executes the span query and returns an iterator over matching documents.
     *
     * @param searcher    cached index searcher (not closed by this class)
     * @param spanQuery   span query; wrapped internally in a recording proxy
     * @param filterQuery optional filter; {@code null} if none
     * @param sort        {@code null} = natural order; {@link Sort#RELEVANCE} = score;
     *                    other = DocValues sort
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
                .add(recording,   BooleanClause.Occur.MUST)
                .add(filterQuery, BooleanClause.Occur.FILTER)
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
     * Returns the character start offset of the first pivot token in span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return character start offset (inclusive)
     */
    public int spanCharStart(final int i) {
        return charData[spanBase[cursor] + (i << 1)];
    }

    /**
     * Returns the character end offset of the last pivot token in span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return character end offset (exclusive)
     */
    public int spanCharEnd(final int i) {
        return charData[spanBase[cursor] + (i << 1) + 1];
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
     * Wraps an inner {@link SpanWeight}, overriding {@link #getSpans} to return
     * {@link RecordingSpans} and upgrading the postings request to
     * {@link SpanWeight.Postings#OFFSETS} so character offsets are available in
     * the leaf postings.
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
            final org.apache.lucene.index.FieldInfo fi =
                ctx.reader().getFieldInfos().fieldInfo(field);
            final boolean hasOffsets = fi != null && fi.getIndexOptions().compareTo(
                org.apache.lucene.index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
            final Postings required = hasOffsets ? p.atLeast(Postings.OFFSETS) : p;
            final Spans raw = inner.getSpans(ctx, required);
            return raw == null ? null : new RecordingSpans(raw, hasOffsets);
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
     * {@link CollectorManager} producing and reducing {@link SpanCollector} instances.
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
            final int[]   mCharData = new int[totalSpans];
            int di = 0, si = 0;
            for (final SpanCollector c : collectors) {
                System.arraycopy(c.docIds,   0, mDocIds,   di, c.docCount);
                System.arraycopy(c.scores,   0, mScores,   di, c.docCount);
                System.arraycopy(c.spanData, 0, mSpanData, si, c.spanCount);
                System.arraycopy(c.charData, 0, mCharData, si, c.spanCount);
                for (int i = 0; i < c.docCount; i++) {
                    mSpanBase[di + i + 1] = si + c.spanBase[i + 1];
                }
                di += c.docCount;
                si += c.spanCount;
            }
            return new SpanDocs(mDocIds, mScores, mSpanBase, mSpanData, mCharData);
        }

        final class SpanCollector implements Collector {

            private final TopScoreDocCollector scoreCollector;
            private final TopFieldCollector    fieldCollector;

            int[]   docIds   = new int[256];
            float[] scores   = new float[256];
            int[]   spanBase = new int[257];
            int[]   spanData = new int[1024];
            int[]   charData = new int[1024];
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
                            delegateLeaf.collect(leafDoc);
                            scores[docCount] = currentScorer.score();
                        } else {
                            scores[docCount] = Float.NaN;
                            if (recording != null) {
                                recording.count = 0;
                                while (recording.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                                }
                            }
                        }

                        if (recording != null) {
                            final int n = recording.count;
                            if (spanCount + n * 2 > spanData.length) {
                                final int newCap = Math.max(spanData.length * 2, spanCount + n * 2);
                                spanData = Arrays.copyOf(spanData, newCap);
                                charData = Arrays.copyOf(charData, newCap);
                            }
                            for (int i = 0; i < n; i++) {
                                spanData[spanCount]     = recording.starts[i];
                                spanData[spanCount + 1] = recording.ends[i];
                                charData[spanCount]     = recording.charStarts[i];
                                charData[spanCount + 1] = recording.charEnds[i];
                                spanCount += 2;
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
                final int[]   fCharData = Arrays.copyOf(charData, spanCount);

                if (scoreCollector == null && fieldCollector == null) {
                    return new SpanDocs(fDocIds, fScores, fSpanBase, fSpanData, fCharData);
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
                final int[]   sCData  = new int[fCharData.length];
                int write = 0;
                sBase[0] = 0;
                for (int r = 0; r < m; r++) {
                    final int gDoc = topDocs.scoreDocs[r].doc;
                    final int i    = byDocId[gDoc];
                    sDocIds[r]     = gDoc;
                    sScores[r]     = fScores[i];
                    final int len  = fSpanBase[i + 1] - fSpanBase[i];
                    System.arraycopy(fSpanData, fSpanBase[i], sData,  write, len);
                    System.arraycopy(fCharData, fSpanBase[i], sCData, write, len);
                    write += len;
                    sBase[r + 1] = write;
                }
                return new SpanDocs(
                    sDocIds, sScores, sBase,
                    Arrays.copyOf(sData,  write),
                    Arrays.copyOf(sCData, write)
                );
            }

            private static int maxGlobalDoc(final int[] ids) {
                int max = 0;
                for (final int d : ids) if (d > max) max = d;
                return max;
            }
        }
    }

    /**
     * Locates the {@link SpanScorer} in a scorer tree by recursive traversal.
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
