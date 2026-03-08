package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Collects term occurrence counts for the documents matched by a Lucene query.
 * <p>
 * The collector is bound to:
 * </p>
 * <ul>
 *   <li>one {@link IndexSearcher},</li>
 *   <li>one immutable {@link TermLexicon},</li>
 *   <li>one indexed field, given by {@link TermLexicon#field()}.</li>
 * </ul>
 * <p>
 * For a query {@code q}, the collector computes a vocabulary vector {@code counts}
 * such that, for every dense global {@code termId}, {@code counts[termId]} is the sum
 * of term frequencies of that term over all documents matched by {@code q}.
 * </p>
 * <p>
 * Counts are therefore:
 * </p>
 * <ul>
 *   <li><b>occurrence counts</b>, not document frequencies,</li>
 *   <li>restricted to the matched document set,</li>
 *   <li>stored by the global dense term ids defined by the lexicon.</li>
 * </ul>
 * <p>
 * The collector itself is immutable and thread-safe. Mutable working memory is supplied
 * by the caller on each invocation through:
 * </p>
 * <ul>
 *   <li>an {@code int[]} counts vector of length {@link #vocabSize()},</li>
 *   <li>a {@link FixedBitSet} of length at least {@link #maxDoc()}.</li>
 * </ul>
 * <p>
 * This design allows the caller to choose its own reuse policy for scratch buffers
 * without storing mutable state inside the collector.
 * </p>
 * <p>
 * Query evaluation is performed with {@link ScoreMode#COMPLETE_NO_SCORES}. This allows
 * Lucene to use its internal query cache when a cache is configured and the query is
 * eligible for caching.
 * </p>
 * <p>
 * Preconditions:
 * </p>
 * <ul>
 *   <li>The lexicon must have been built from the same frozen index snapshot as the searcher reader.</li>
 *   <li>The field must store term frequencies. If frequencies were omitted at index time,
 *       occurrence counting is impossible.</li>
 * </ul>
 */
public final class TermCollector {
    /** Searcher used to rewrite queries and create weights. */
    private final IndexSearcher searcher;

    /** Reader behind the searcher. */
    private final IndexReader reader;

    /** Leaf contexts of the current reader. */
    private final List<LeafReaderContext> leaves;

    /** Immutable global term lexicon. */
    private final TermLexicon lexicon;

    /** Indexed field to collect from. */
    private final String field;

    /**
     * Creates a collector bound to one searcher and one term lexicon.
     *
     * @param searcher searcher used to evaluate queries
     * @param lexicon immutable lexicon for the collected field
     * @throws NullPointerException if an argument is {@code null}
     */
    public TermCollector(final IndexSearcher searcher, final TermLexicon lexicon) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.reader = searcher.getIndexReader();
        this.leaves = reader.leaves();
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        this.field = lexicon.field();
    }

    /**
     * Returns the indexed field collected by this instance.
     *
     * @return field name
     */
    public String field() {
        return field;
    }

    /**
     * Returns the number of distinct terms in the bound lexicon.
     * <p>
     * This is also the required length of the {@code counts} array passed to
     * {@link #collect(Query, int[], FixedBitSet)}.
     * </p>
     *
     * @return vocabulary size
     */
    public int vocabSize() {
        return lexicon.vocabSize();
    }

    /**
     * Returns the maximum document count of the bound reader.
     * <p>
     * This is the minimum required bit capacity of the {@code matchedDocs} bit set passed to
     * {@link #collect(Query, int[], FixedBitSet)}.
     * </p>
     *
     * @return reader maxDoc
     */
    public int maxDoc() {
        return reader.maxDoc();
    }

    /**
     * Populates a vocabulary vector with occurrence counts for the documents matched by a query.
     * <p>
     * The method performs two phases:
     * </p>
     * <ol>
     *   <li>Evaluate the query once and mark all matched documents in {@code matchedDocs}.</li>
     *   <li>Traverse the term dictionaries and postings of the collected field, and accumulate
     *       term frequencies only for documents marked as matched.</li>
     * </ol>
     * <p>
     * Before collection starts:
     * </p>
     * <ul>
     *   <li>{@code counts} is fully reset to zero,</li>
     *   <li>{@code matchedDocs} is cleared on the range {@code [0, maxDoc())}.</li>
     * </ul>
     * <p>
     * After return:
     * </p>
     * <ul>
     *   <li>{@code counts[termId]} contains the occurrence count for that term over the matched documents,</li>
     *   <li>{@code matchedDocs} contains the matched document set and may be reused by the caller if needed.</li>
     * </ul>
     *
     * @param query query whose matched documents define the population
     * @param counts caller-owned output vector, of length exactly {@link #vocabSize()}
     * @param matchedDocs caller-owned scratch bit set, of length at least {@link #maxDoc()}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code counts.length != vocabSize()} or
     *         {@code matchedDocs.length() < maxDoc()}
     * @throws IllegalArgumentException if the collected field does not store term frequencies
     * @throws IOException if Lucene query evaluation, term iteration or postings traversal fails
     */
    public void collect(final Query query, final int[] counts, final FixedBitSet matchedDocs) throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(matchedDocs, "matchedDocs");

        if (counts.length != vocabSize()) {
            throw new IllegalArgumentException(
                "counts.length=" + counts.length + ", expected " + vocabSize()
            );
        }
        if (matchedDocs.length() < maxDoc()) {
            throw new IllegalArgumentException(
                "matchedDocs.length()=" + matchedDocs.length() + ", expected at least " + maxDoc()
            );
        }

        Arrays.fill(counts, 0);
        matchedDocs.clear(0, maxDoc());

        final Query rewritten = searcher.rewrite(query);
        final Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1f);

        for (LeafReaderContext ctx : leaves) {
            markMatchedDocs(ctx, weight, matchedDocs);
        }

        for (LeafReaderContext ctx : leaves) {
            collectLeaf(ctx, counts, matchedDocs);
        }
    }

    /**
     * Marks in a global bit set all documents matched by the query weight on one leaf.
     *
     * @param ctx leaf context
     * @param weight query weight created for {@link ScoreMode#COMPLETE_NO_SCORES}
     * @param matchedDocs global bit set indexed by reader-global doc ids
     * @throws IOException if Lucene scorer creation or iteration fails
     */
    private static void markMatchedDocs(
        final LeafReaderContext ctx,
        final Weight weight,
        final FixedBitSet matchedDocs
    ) throws IOException {
        final Scorer scorer = weight.scorer(ctx);
        if (scorer == null) {
            return;
        }

        final DocIdSetIterator it = scorer.iterator();
        if (it.nextDoc() == DocIdSetIterator.NO_MORE_DOCS) {
            return;
        }

        /*
         * The iterator is now positioned on a valid leaf-local doc id.
         * intoBitSet() writes global doc ids by applying:
         * globalDoc = leafDoc - offset
         * hence offset = -docBase.
         */
        it.intoBitSet(DocIdSetIterator.NO_MORE_DOCS, matchedDocs, -ctx.docBase);
    }

    /**
     * Accumulates term occurrence counts for one leaf.
     * <p>
     * The method iterates the leaf term dictionary of the collected field. For each term:
     * </p>
     * <ol>
     *   <li>resolve the global dense {@code termId} through the lexicon,</li>
     *   <li>read postings with frequencies,</li>
     *   <li>add the term frequency for every live document whose global doc id is marked
     *       in {@code matchedDocs}.</li>
     * </ol>
     *
     * @param ctx leaf context
     * @param counts global vocabulary vector to populate
     * @param matchedDocs global matched-doc bit set
     * @throws IOException if term iteration or postings traversal fails
     * @throws IllegalArgumentException if the field has no term frequencies
     */
    private void collectLeaf(
        final LeafReaderContext ctx,
        final int[] counts,
        final FixedBitSet matchedDocs
    ) throws IOException {
        final LeafReader leaf = ctx.reader();
        final Terms terms = leaf.terms(field);
        if (terms == null) {
            return;
        }
        if (!terms.hasFreqs()) {
            throw new IllegalArgumentException(
                "Field '" + field + "' does not store term frequencies"
            );
        }

        final Bits liveDocs = leaf.getLiveDocs();
        final int docBase = ctx.docBase;
        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef term;

        while ((term = tenum.next()) != null) {
            final int termId = lexicon.id(term);
            if (termId < 0) {
                /*
                 * This indicates a mismatch between the lexicon snapshot and the searcher reader,
                 * or a corrupted/incompatible lexicon. Ignore the term rather than writing outside
                 * the contract of the output vector.
                 */
                continue;
            }

            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = postings.nextDoc()) {
                if (liveDocs != null && !liveDocs.get(doc)) {
                    continue;
                }
                if (!matchedDocs.get(docBase + doc)) {
                    continue;
                }
                counts[termId] += postings.freq();
            }
        }
    }
}