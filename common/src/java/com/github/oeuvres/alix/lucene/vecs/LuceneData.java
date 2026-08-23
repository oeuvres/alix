package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.lucene.terms.TermStats;

/**
 * Small Lucene extraction helpers shared by vector-building experiments.
 *
 * <p>This class deliberately contains no vector mathematics and no vector-file
 * I/O. Corpus statistics come from {@link TermStats}; vector serialisation
 * belongs to {@link VecModel}.</p>
 */
public final class LuceneData
{
    /**
     * One selected indexed term.
     *
     * @param termId dense {@link TermStats} / lexicon term id
     * @param bytes immutable indexed UTF-8 bytes
     * @param word decoded term form
     * @param docFreq document frequency
     * @param totalFreq total term frequency
     */
    public record SelectedTerm(
        int termId,
        BytesRef bytes,
        String word,
        int docFreq,
        long totalFreq
    ) {}

    /**
     * Selected dense term × document frequency matrix.
     *
     * <p>The frequency rows and the {@link TermStats} arrays use global Lucene
     * document ids in {@code [0, stats.maxDoc())}. The returned arrays are live
     * references for experimental hot loops and must be treated as read-only.</p>
     *
     * @param terms selected terms in matrix-row order
     * @param freqs term × global-doc-id frequencies
     * @param stats corpus/document/term statistics for the same reader snapshot
     */
    public record TermDoc(
        SelectedTerm[] terms,
        int[][] freqs,
        TermStats stats
    ) {
        public TermDoc
        {
            Objects.requireNonNull(terms, "terms");
            Objects.requireNonNull(freqs, "freqs");
            Objects.requireNonNull(stats, "stats");
            if (terms.length != freqs.length) {
                throw new IllegalArgumentException(
                    "term/frequency row mismatch: " + terms.length + " != " + freqs.length);
            }
            final int maxDoc = stats.maxDoc();
            for (int row = 0; row < freqs.length; row++) {
                if (freqs[row] == null || freqs[row].length != maxDoc) {
                    throw new IllegalArgumentException(
                        "bad frequency row " + row + ": expected " + maxDoc + " documents");
                }
            }
        }

        /** Number of selected term rows. */
        public int termCount()
        {
            return terms.length;
        }

        /** Global Lucene document-address size. */
        public int maxDoc()
        {
            return stats.maxDoc();
        }

        /** Selected words in matrix-row order. */
        public String[] words()
        {
            final String[] words = new String[terms.length];
            for (int row = 0; row < terms.length; row++) {
                words[row] = terms[row].word();
            }
            return words;
        }
    }

    /** Utility class. */
    private LuceneData()
    {
    }

    /**
     * Selects terms from the same frozen reader snapshot as {@code stats}.
     *
     * <p>Terms first pass the minimum document-frequency and optional exclusion
     * filter, then are ordered by decreasing total frequency, decreasing
     * document frequency, and finally lexical form. The cap is applied last.</p>
     *
     * @param reader frozen Lucene reader
     * @param stats statistics aligned with this reader and field
     * @param minDocFreq minimum document frequency, inclusive
     * @param maxTerms maximum number of retained terms
     * @param excludedTermIds optional dense term ids to exclude; may be null
     * @return selected terms in decreasing corpus-frequency order
     * @throws IOException if the term dictionary cannot be read
     */
    public static SelectedTerm[] selectTerms(
        final IndexReader reader,
        final TermStats stats,
        final int minDocFreq,
        final int maxTerms,
        final BitSet excludedTermIds
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(stats, "stats");
        if (reader.maxDoc() != stats.maxDoc()) {
            throw new IllegalArgumentException(
                "reader/stats maxDoc mismatch: "
                    + reader.maxDoc() + " != " + stats.maxDoc());
        }
        if (minDocFreq < 1) {
            throw new IllegalArgumentException("minDocFreq must be >= 1");
        }
        if (maxTerms < 1) {
            throw new IllegalArgumentException("maxTerms must be >= 1");
        }

        final Terms fieldTerms = MultiTerms.getTerms(reader, stats.field());
        if (fieldTerms == null) {
            throw new IllegalArgumentException(
                "no indexed terms for field: " + stats.field());
        }
        if (!fieldTerms.hasFreqs()) {
            throw new IllegalArgumentException(
                "field has no term frequencies: " + stats.field());
        }

        final List<SelectedTerm> kept = new ArrayList<>();
        final TermsEnum scan = fieldTerms.iterator();
        BytesRef bytes;
        int termId = 1; // TermStats / TermLexicon reserve id 0 as sentinel.
        while ((bytes = scan.next()) != null) {
            if (termId >= stats.vocabSize()) {
                throw new IOException(
                    "reader vocabulary exceeds TermStats for field " + stats.field());
            }

            final int docFreq = stats.termDocs(termId);
            final long totalFreq = stats.termFreq(termId);
            if (docFreq >= minDocFreq
                    && (excludedTermIds == null || !excludedTermIds.get(termId))) {
                kept.add(new SelectedTerm(
                    termId,
                    BytesRef.deepCopyOf(bytes),
                    bytes.utf8ToString(),
                    docFreq,
                    totalFreq));
            }
            termId++;
        }

        if (termId != stats.vocabSize()) {
            throw new IOException(
                "reader/TermStats vocabulary mismatch for field " + stats.field()
                    + ": reader=" + (termId - 1)
                    + ", stats=" + (stats.vocabSize() - 1));
        }

        kept.sort(
            Comparator.comparingLong(SelectedTerm::totalFreq).reversed()
                .thenComparing(
                    Comparator.comparingInt(SelectedTerm::docFreq).reversed())
                .thenComparing(SelectedTerm::word));

        if (kept.size() > maxTerms) {
            return kept.subList(0, maxTerms).toArray(SelectedTerm[]::new);
        }
        return kept.toArray(SelectedTerm[]::new);
    }

    /**
     * Convenience overload retaining stopwords and every other eligible term.
     */
    public static SelectedTerm[] selectTerms(
        final IndexReader reader,
        final TermStats stats,
        final int minDocFreq,
        final int maxTerms
    ) throws IOException {
        return selectTerms(reader, stats, minDocFreq, maxTerms, null);
    }

    /**
     * Collects a selected dense term × document frequency matrix.
     *
     * <p>Document lengths and corpus totals are intentionally not recomputed:
     * use {@code result.stats().docTokens()} and
     * {@code result.stats().fieldTokens()}. In particular, density models must
     * use indexed-token counts, not positional widths, when stopwords were
     * removed at indexing time.</p>
     *
     * @param reader frozen Lucene reader
     * @param stats statistics for the same reader snapshot
     * @param minDocFreq minimum document frequency, inclusive
     * @param maxTerms maximum number of selected terms
     * @param excludedTermIds optional dense term ids to exclude; may be null
     * @return selected term × document frequencies plus shared statistics
     * @throws IOException if postings cannot be read
     */
    public static TermDoc termDoc(
        final IndexReader reader,
        final TermStats stats,
        final int minDocFreq,
        final int maxTerms,
        final BitSet excludedTermIds
    ) throws IOException {
        final SelectedTerm[] selected =
            selectTerms(reader, stats, minDocFreq, maxTerms, excludedTermIds);
        final int[][] freqs = new int[selected.length][stats.maxDoc()];

        final Terms fieldTerms = MultiTerms.getTerms(reader, stats.field());
        if (fieldTerms == null) {
            throw new IllegalArgumentException(
                "no indexed terms for field: " + stats.field());
        }

        final TermsEnum scan = fieldTerms.iterator();
        PostingsEnum postings = null;
        for (int row = 0; row < selected.length; row++) {
            final SelectedTerm term = selected[row];
            if (!scan.seekExact(term.bytes())) {
                throw new IOException(
                    "selected term disappeared from reader: " + term.word());
            }

            postings = scan.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc();
                    docId != DocIdSetIterator.NO_MORE_DOCS;
                    docId = postings.nextDoc()) {
                freqs[row][docId] = postings.freq();
            }
        }

        return new TermDoc(selected, freqs, stats);
    }

    /**
     * Convenience overload retaining stopwords and every other eligible term.
     */
    public static TermDoc termDoc(
        final IndexReader reader,
        final TermStats stats,
        final int minDocFreq,
        final int maxTerms
    ) throws IOException {
        return termDoc(reader, stats, minDocFreq, maxTerms, null);
    }
}
