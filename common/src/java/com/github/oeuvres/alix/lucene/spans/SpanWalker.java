package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.SpanWeight.Postings;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

import com.github.oeuvres.alix.lucene.terms.TermLexicon;

/**
 * Walks a {@link SpanQuery} document by document and folds its span matches
 * into reusable {@link Snippets}.
 *
 * <p>
 * The walker owns Lucene iteration. It does not aggregate, score, render, or
 * collect co-occurrents. Those operations are delegated to a
 * {@link DocConsumer}, called once per accepted document after the
 * {@link Snippets} object has been finished.
 * </p>
 *
 * <p>
 * Optional filtering is supported in two forms:
 * </p>
 * <ul>
 * <li>a Lucene {@link Query}, evaluated per leaf;</li>
 * <li>a global-doc-id {@link FixedBitSet}, evaluated directly on
 * {@code docBase + leafDoc}.</li>
 * </ul>
 *
 * <p>
 * If both filters are provided, a document must satisfy both.
 * </p>
 *
 * <p>
 * The {@link Snippets} instance is reused for every accepted document.
 * Consumers must copy data if they need to keep it after
 * {@link DocConsumer#accept(int, Snippets)} returns.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class SpanWalker {
	private static final int INITIAL_LEAF_CAPACITY = 1;

	private final IndexSearcher searcher;
	private final SpanQuery spanQuery;
	private final Query filterQuery;
	private final Snippets snippets;
	private final DocConsumer consumer;
	
	private final Postings postings;

	/**
	 * Cached per-leaf {@link Spans} instances, indexed by leaf ordinal. Reused when
	 * the next visited docId is strictly greater than the last visited docId in the
	 * same leaf. Rebuilt otherwise, since {@link Spans#advance} is forward-only.
	 */
	private final Spans[] leafSpans;
	/**
	 * Last global docId visited per leaf, indexed by leaf ordinal. Initialised to
	 * {@code -1}. Used to decide whether to advance or rebuild.
	 */
	private final int[] leafLastDocId;

	/**
	 * Consumes snippets collected for one accepted document.
	 */
	@FunctionalInterface
	public interface DocConsumer {
		/**
		 * Consumes the finished snippets of one document.
		 *
		 * @param docId    global Lucene document id
		 * @param snippets finished snippets for the document; reused after this call
		 *                 returns
		 * @throws IOException if the consumer needs Lucene I/O and it fails
		 */
		public void accept(int docId, Snippets snippets) throws IOException;
	}

	/**
	 * Creates a walker bound to a query, an optional filter, and a listener. Both
	 * queries are rewritten once, here.
	 *
	 * @param searcher    used for query rewrite and leaf access
	 * @param spanQuery   span query to enumerate
	 * @param filterQuery non-scoring filter, or {@code null}
	 * @param listener    consumer of streamed matches
	 * @throws IOException          on rewrite failure
	 * @throws NullPointerException if {@code searcher}, {@code spanQuery}, or
	 *                              {@code listener} is {@code null}
	 */
	public SpanWalker(
			final IndexSearcher searcher,
			final SpanQuery spanQuery,
			final Query filterQuery,
			final Snippets snippets,
			final DocConsumer consumer
	) throws IOException {
		this.searcher = Objects.requireNonNull(searcher, "searcher");
		Objects.requireNonNull(spanQuery, "spanQuery");
		this.spanQuery = (SpanQuery) searcher.rewrite(spanQuery);
		if (!(this.spanQuery instanceof SpanQuery)) {
			throw new IllegalArgumentException("rewritten span query is not a SpanQuery: "
					+ this.spanQuery.getClass().getName() + " " + this.spanQuery);
		}
		this.filterQuery = (filterQuery == null) ? null : searcher.rewrite(filterQuery);
		this.snippets = Objects.requireNonNull(snippets, "snippets");
		if (snippets.usage() == Snippets.Usage.OFFSETS) {
			postings = Postings.OFFSETS;
		} else {
			postings = Postings.POSITIONS;
		}

		this.consumer = Objects.requireNonNull(consumer, "consumer");
		this.leafSpans = new Spans[searcher.getLeafContexts().size()];

		// caches for random access by docId
		this.leafLastDocId = new int[searcher.getLeafContexts().size()];
		java.util.Arrays.fill(this.leafLastDocId, -1);
	}

	/**
	 * Counts the documents matched by the span query intersected with the optional
	 * filter. Performs a full scan; does not interact with {@link #walk(int)}. Used
	 * for search results.
	 *
	 * @return matching document count
	 * @throws IOException on I/O failure
	 */
	public int hits() throws IOException {
		final Query countQuery = (filterQuery == null) ? spanQuery
				: new BooleanQuery.Builder().add(spanQuery, BooleanClause.Occur.MUST)
						.add(filterQuery, BooleanClause.Occur.FILTER).build();
		return searcher.count(countQuery);
	}

	/**
	 * TODO, full Javadoc
	 */
	public int walk(final int docStart) throws IOException {
		final SpanWeight spanWeight = (SpanWeight) spanQuery.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
		final Weight filterWeight = (filterQuery == null) ? null
				: searcher.createWeight(filterQuery, ScoreMode.COMPLETE_NO_SCORES, 1f);

		final List<LeafReaderContext> leaves = searcher.getLeafContexts();

		for (final LeafReaderContext leaf : leaves) {
			// here, how advance a cursor to docStart for a paginated walk?
			walkLeaf(leaf, spanWeight, filterWeight);
		}

		// here, how to return the next docId to continue the walk on next page?
		return -1;
	}

	/**
	 * Visit docs randomly (example, get snippets from search results sorted by
	 * relevance, get all snippets of 1 doc on demand)
	 * 
	 * 
	 * @param docId
	 * @throws IOException
	 */
	public void visitDoc(final int docId) throws IOException {
		// Find the leaf containing this docId, advance Spans to it.
		Spans spans = null;
		for (final LeafReaderContext ctx : searcher.getLeafContexts()) {
			final int localDocId = docId - ctx.docBase;
			if (localDocId < 0 || localDocId >= ctx.reader().maxDoc())
				continue;
	
			// Reuse cached Spans if docId is strictly greater than the last
			// visited docId in this leaf (advance is possible). Otherwise discard
			// and rebuild — relevance order does not guarantee ascending docIds.
	
			// TODO, review logic here, I don't remember
			if (leafSpans[ctx.ord] == null || docId <= leafLastDocId[ctx.ord]) {
				// ????
				// leafSpans[ctx.ord] = spanWeight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
			}
			spans = leafSpans[ctx.ord];
			if (spans == null)
				return;
			if (spans.advance(localDocId) != localDocId)
				return;
			leafLastDocId[ctx.ord] = docId;
			break;
		}
		if (spans == null)
			return;
		walkDoc(spans, docId);
	}

	private void walkLeaf(final LeafReaderContext leaf, final SpanWeight spanWeight, final Weight filterWeight) throws IOException {
		final Spans spans = spanWeight.getSpans(leaf, postings);
		if (spans == null) {
			return;
		}

		final DocIdSetIterator filterIterator;
		if (filterWeight == null) {
			filterIterator = null;
		} else {
			final Scorer scorer = filterWeight.scorer(leaf);
			if (scorer == null) {
				return;
			}
			filterIterator = scorer.iterator();
		}

		final int docBase = leaf.docBase;
		for (int leafDoc = spans.nextDoc(); leafDoc != DocIdSetIterator.NO_MORE_DOCS; leafDoc = spans.nextDoc()) {
			final int docId = docBase + leafDoc;
			if (!acceptedByFilter(filterIterator, leafDoc)) {
				continue;
			}
			walkDoc(spans, docId);
		}
	}

	/**
	 * Walks one accepted document.
	 *
	 * <p>
	 * This method is intentionally isolated because the same per-document sequence
	 * is reused by histogram counting, co-occurrence collection, snippet scoring,
	 * and rendering.
	 * </p>
	 *
	 * @param spans positioned on {@code leafDoc}
	 * @param docId global Lucene document id
	 * @throws IOException if Lucene span collection or the consumer fails
	 */
	private void walkDoc(final Spans spans, final int docId) throws IOException {
		// do not change here, The API has been rewritten
		snippets.openDoc(docId);

		for (int start = spans.nextStartPosition(); start != Spans.NO_MORE_POSITIONS; start = spans
				.nextStartPosition()) {
			snippets.commitSpan(start, spans.endPosition());
			if (snippets.wantsOffsets()) {
				spans.collect(snippets);
			}
		}
		// do not change here, The API has been rewritten
		snippets.closeDoc();
		consumer.accept(docId, snippets);
	}

	/**
	 * Returns whether a leaf-local document id is accepted by the optional filter
	 * iterator.
	 *
	 * <p>
	 * The iterator is advanced monotonically and must therefore be used in
	 * increasing document order, which is the order provided by
	 * {@link Spans#nextDoc()}.
	 * </p>
	 */
	private static boolean acceptedByFilter(final DocIdSetIterator filterIterator, final int leafDoc)
			throws IOException {
		if (filterIterator == null) {
			return true;
		}
		int filterDoc = filterIterator.docID();
		if (filterDoc < leafDoc) {
			filterDoc = filterIterator.advance(leafDoc);
		}
		return filterDoc == leafDoc;
	}

}
