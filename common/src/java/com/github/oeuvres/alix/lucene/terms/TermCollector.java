package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;

/**
 * Accumulates per-term occurrence counts for a pre-selected set of documents
 * into a {@link TopTerms}.
 *
 * <p>
 * The caller resolves which documents form the focus subset (typically with
 * {@link com.github.oeuvres.alix.lucene.BitsCollectorManager}) and passes the
 * resulting bitset alongside a target {@link TopTerms}. This class walks the
 * postings of the bound field, summing term frequencies for matched documents
 * and writing directly into the package-private focus arrays of the target.
 * </p>
 *
 * <p>
 * After {@link #collect}, the target holds:
 * </p>
 * <ul>
 *   <li>{@code focusCounts[termId]} — occurrences of each term in the focus subset,</li>
 *   <li>{@code focusDocCounts[termId]} — documents containing each term, if requested,</li>
 *   <li>{@code focusTotal} — total token occurrences across the focus subset,</li>
 *   <li>{@code focusDocCount} — number of documents in the focus subset.</li>
 * </ul>
 *
 * <p>
 * The target {@code TopTerms} is not scored by this call; invoke
 * {@link TopTerms#focusScore} afterwards to rank the collected counts.
 * </p>
 *
 * <h2>Document frequency</h2>
 * <p>
 * Per-term document counts ({@code focusDocCounts}) are allocated only when
 * {@code withDocCounts = true}. This is optional because it doubles the
 * scratch memory ({@code int[vocabSize]} on top of {@code long[vocabSize]})
 * and many scorers ignore dispersion.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * This class holds no mutable state. All mutable output lives in the passed
 * {@link TopTerms}. A single {@code TermCollector} may therefore be shared
 * across threads provided each thread uses its own {@code TopTerms}.
 * </p>
 *
 * <h2>Preconditions</h2>
 * <ul>
 *   <li>The target {@code TopTerms}, its {@code FieldStats} and the
 *       {@code TermLexicon} bound to this collector must all come from the
 *       same frozen index snapshot.</li>
 *   <li>The field must store term frequencies. If frequencies were omitted
 *       at index time, occurrence counting is impossible.</li>
 * </ul>
 */
public final class TermCollector {

    /** Searcher whose reader supplies the postings. */
    private final IndexSearcher searcher;

    /** Lexicon that maps term bytes to dense term ids. */
    private final TermLexicon lexicon;

    /** Indexed field collected from; taken from {@link #lexicon}. */
    private final String field;

    /**
     * Binds the collector to one searcher and one lexicon.
     *
     * @param searcher searcher whose reader supplies the postings
     * @param lexicon  immutable lexicon for the collected field
     * @throws NullPointerException if an argument is {@code null}
     */
    public TermCollector(final IndexSearcher searcher, final TermLexicon lexicon) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.lexicon  = Objects.requireNonNull(lexicon, "lexicon");
        this.field    = lexicon.field();
    }

    /**
     * Populates the focus arrays of {@code target} with term occurrence counts
     * for the documents marked in {@code focusDocs}. Per-term document counts
     * are not collected.
     *
     * <p>Equivalent to {@link #collect(FixedBitSet, TopTerms, boolean)
     * collect(focusDocs, target, false)}.</p>
     *
     * @param focusDocs global bitset of focus document ids
     * @param target    destination; its focus arrays are reset before collection
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the field does not store term frequencies
     * @throws IOException              if postings traversal fails
     */
    public void collect(final FixedBitSet focusDocs, final TopTerms target) throws IOException {
        collect(focusDocs, target, false);
    }

    /**
     * Populates the focus arrays of {@code target} with term occurrence counts
     * for the documents marked in {@code focusDocs}.
     *
     * <p>
     * Walks the term dictionary of the bound field on every leaf. For each
     * term, the postings are read with frequencies and the count is added to
     * {@code target.focusCounts[termId]} for every live document whose global
     * id is marked in {@code focusDocs}. When {@code withDocCounts} is true,
     * {@code target.focusDocCounts[termId]} is also incremented for each
     * matched document.
     * </p>
     *
     * <p>
     * The target's focus arrays are reset (reallocated or zero-filled) before
     * collection, via {@link TopTerms#initFocus}. Previous focus data in the
     * target is discarded.
     * </p>
     *
     * @param focusDocs      global bitset of focus document ids; its bit space
     *                       must cover the reader's maxDoc
     * @param target         destination; its focus arrays are written in place
     * @param withDocCounts  {@code true} to also populate
     *                       {@link TopTerms#focusDocCounts}
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the field does not store term frequencies
     * @throws IOException              if postings traversal fails
     */
    public void collect(
        final FixedBitSet focusDocs,
        final TopTerms    target,
        final boolean     withDocCounts
    ) throws IOException {
        Objects.requireNonNull(focusDocs, "focusDocs");
        Objects.requireNonNull(target, "target");

        target.initFocus(withDocCounts);

        long totalTokens = 0L;
        for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
            totalTokens += collectLeaf(ctx, focusDocs, target);
        }
        target.focusTotal    = totalTokens;
        target.focusDocCount = focusDocs.cardinality();
    }

    /**
     * Accumulates term occurrence counts for one leaf segment.
     *
     * @param ctx       leaf context
     * @param focusDocs global matched-doc bitset
     * @param target    destination whose focus arrays are being populated
     * @return total token count added from this leaf
     * @throws IllegalArgumentException if the field has no term frequencies
     * @throws IOException              if term iteration or postings traversal fails
     */
    private long collectLeaf(
        final LeafReaderContext ctx,
        final FixedBitSet       focusDocs,
        final TopTerms          target
    ) throws IOException {
        final LeafReader leaf  = ctx.reader();
        final Terms      terms = leaf.terms(field);
        if (terms == null) return 0L;
        if (!terms.hasFreqs()) {
            throw new IllegalArgumentException(
                "Field '" + field + "' does not store term frequencies");
        }

        final long[] focusCounts    = target.focusCounts;
        final int[]  focusDocCounts = target.focusDocCounts;
        final Bits   liveDocs       = leaf.getLiveDocs();
        final int    docBase        = ctx.docBase;
        long totalTokens = 0L;

        final TermsEnum  tenum    = terms.iterator();
        PostingsEnum     postings = null;
        BytesRef         bytes;

        while ((bytes = tenum.next()) != null) {
            final int termId = lexicon.id(bytes);
            if (termId < 0) continue;

            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = postings.nextDoc()) {
                if (liveDocs != null && !liveDocs.get(doc)) continue;
                if (!focusDocs.get(docBase + doc)) continue;

                final int freq = postings.freq();
                focusCounts[termId] += freq;
                totalTokens         += freq;
                if (focusDocCounts != null) focusDocCounts[termId]++;
            }
        }
        return totalTokens;
    }
}
