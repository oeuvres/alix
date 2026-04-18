package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Arrays;
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
 * Accumulates per-term occurrence counts for a pre-selected set of documents.
 *
 * The caller resolves which documents are in focus (via BitsCollectorManager
 * or any other means) and passes the result as a FixedBitSet. This class
 * only handles the postings traversal.
 *
 * Thread-safe: holds no mutable state. All mutable output lives in caller-owned arrays.
 */
public final class TermCollector {

    private final IndexSearcher searcher;
    private final TermLexicon   lexicon;
    private final String        field;

    public TermCollector(final IndexSearcher searcher, final TermLexicon lexicon) {
        this.searcher = Objects.requireNonNull(searcher);
        this.lexicon  = Objects.requireNonNull(lexicon);
        this.field    = lexicon.field();
    }

    /**
     * Populates {@code termFreqs} and {@code docFreqs} for the documents marked
     * in {@code focusDocs}.
     *
     * @param focusDocs  global bitset of focus document ids (from BitsCollectorManager)
     * @param termFreqs  output: total occurrences per termId; caller allocates [lexicon.vocabSize()]
     * @param docFreqs   output: document count per termId;   caller allocates [lexicon.vocabSize()]
     *                   pass null if document frequency is not needed
     * @return total token count across all matched documents (sum of termFreqs)
     */
    public long collect(
        final FixedBitSet focusDocs,
        final long[]      termFreqs,
        final int[]       docFreqs    // nullable
    ) throws IOException {
        Arrays.fill(termFreqs, 0L);
        if (docFreqs != null) Arrays.fill(docFreqs, 0);
        long totalTokens = 0L;

        for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
            totalTokens += collectLeaf(ctx, focusDocs, termFreqs, docFreqs);
        }
        return totalTokens;
    }

    private long collectLeaf(
        final LeafReaderContext ctx,
        final FixedBitSet       focusDocs,
        final long[]            termFreqs,
        final int[]             docFreqs
    ) throws IOException {
        final LeafReader leaf   = ctx.reader();
        final Terms      terms  = leaf.terms(field);
        if (terms == null) return 0L;

        final Bits liveDocs = leaf.getLiveDocs();
        final int  docBase  = ctx.docBase;
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
                termFreqs[termId] += freq;
                totalTokens       += freq;
                if (docFreqs != null) docFreqs[termId]++;
            }
        }
        return totalTokens;
    }
}