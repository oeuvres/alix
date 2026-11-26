package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRefBuilder;

/**
 * Computes whole-field lexical dispersion for selected terms using their
 * postings and the document token counts already available in {@link TermStats}.
 * The returned measure is 1 - DP (Gries), the overlap between the distribution
 * of a term's occurrences and the distribution of indexed tokens across live
 * documents. One means proportional usage; zero is the limiting case of
 * complete concentration. The scale is absolute, not normalized over the
 * selected terms.
 */
public final class TermDispersion
{
    /** Prevents instantiation of this utility class. */
    private TermDispersion() {}

    /**
     * Computes dispersion scores, in the order of the supplied term IDs.
     * Only the selected terms' postings are visited. Documents with no term
     * occurrences contribute zero to the overlap and need not be visited.
     *
     * @param reader reader corresponding to the lexicon and statistics
     * @param lexicon lexicon for the indexed field
     * @param stats whole-field statistics for the same reader and field
     * @param termIds selected dense term IDs
     * @return scores in [0, 1], or NaN for an absent term or empty field
     * @throws IOException if postings access fails
     */
    public static double[] scores(final IndexReader reader, final TermLexicon lexicon,
            final TermStats stats, final int[] termIds) throws IOException
    {
        final double[] scores = new double[termIds.length];
        Arrays.fill(scores, Double.NaN);
        final long tokens = stats.fieldTokens();
        if (tokens <= 0 || termIds.length == 0) return scores;
        final Terms terms = MultiTerms.getTerms(reader, stats.field());
        if (terms == null || !terms.hasFreqs()) return scores;
        final TermsEnum iterator = terms.iterator();
        final BytesRefBuilder bytes = new BytesRefBuilder();
        final BitSet live = reader.hasDeletions() ? TermStats.liveDocs(reader) : null;
        PostingsEnum postings = null;
        for (int i = 0; i < termIds.length; i++) {
            final int id = termIds[i];
            final long freq = stats.termFreq(id);
            if (freq <= 0 || !iterator.seekExact(lexicon.formBytes(id, bytes))) continue;
            postings = iterator.postings(postings, PostingsEnum.FREQS);
            double overlap = 0d;
            for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS;
                    doc = postings.nextDoc()) {
                if (live != null && !live.get(doc)) continue;
                overlap += Math.min(postings.freq() / (double) freq,
                    stats.docTokens(doc) / (double) tokens);
            }
            scores[i] = Math.max(0d, Math.min(1d, overlap));
        }
        return scores;
    }
}
