package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.util.TopArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Corpus-level keyword scorer for one indexed field.
 *
 * <p>Assigns one score per vocabulary term by iterating all postings of the field
 * and accumulating a local per-document score (typically BM25) into a single
 * corpus-level weight. The result is a dense {@code double[]} indexed by
 * {@link TermLexicon} term id, written into a caller-supplied {@link TermStats}.</p>
 *
 * <p>The resulting score vector has two uses:</p>
 * <ul>
 *   <li><strong>Keyword extraction</strong> — the top-ranked terms describe the
 *       thematic vocabulary of the corpus as a whole ({@link #topTerms}).</li>
 *   <li><strong>Passage scoring</strong> — summing {@code scores[termId]} for all
 *       terms in a candidate text window gives a measure of how topically dense
 *       that window is, independently of query term repetition. This is useful for
 *       selecting the most informative passage per document in a relevance-ranked
 *       search.</li>
 * </ul>
 *
 * <p>Instances are bound to a frozen {@link IndexReader} snapshot. The same instance
 * can be reused across multiple {@link #score} calls with different scorers or
 * destination {@link TermStats} objects.</p>
 */
public final class ThemeTerms {

    private final IndexReader reader;
    private final List<LeafReaderContext> leaves;
    private final TermLexicon lexicon;
    private final FieldStats fieldStats;
    private final String field;

    /**
     * Binds the scorer to one frozen field snapshot.
     *
     * @param reader     frozen Lucene reader
     * @param lexicon    dense lexicon for the same field and snapshot
     * @param fieldStats immutable field statistics for the same field and snapshot
     */
    public ThemeTerms(
        final IndexReader reader,
        final TermLexicon lexicon,
        final FieldStats fieldStats
    ) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.leaves = reader.leaves();
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.field = lexicon.field();

        if (!field.equals(fieldStats.field())) {
            throw new IllegalArgumentException(
                "Field mismatch: lexicon='" + field + "', fieldStats='" + fieldStats.field() + "'"
            );
        }
        if (lexicon.vocabSize() != fieldStats.vocabSize()) {
            throw new IllegalArgumentException(
                "vocabSize mismatch: lexicon=" + lexicon.vocabSize()
                + ", fieldStats=" + fieldStats.vocabSize()
            );
        }
        if (reader.maxDoc() != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "maxDoc mismatch: reader=" + reader.maxDoc()
                + ", fieldStats=" + fieldStats.maxDoc()
            );
        }
    }

    private void validateStats(final TermStats stats) {
        if (!field.equals(stats.field())) {
            throw new IllegalArgumentException(
                "Field mismatch: themeTerms='" + field + "', stats='" + stats.field() + "'"
            );
        }
        if (stats.vocabSize() != lexicon.vocabSize()) {
            throw new IllegalArgumentException(
                "vocabSize mismatch: themeTerms=" + lexicon.vocabSize()
                + ", stats=" + stats.vocabSize()
            );
        }
    }

    /**
     * Pre-computed leaf structure for efficient liveDocs checks during
     * merged postings iteration.
     */
    private static final class LeafLayout {
        final int leafCount;
        final int[] docBases;
        final Bits[] liveDocs;

        LeafLayout(final List<LeafReaderContext> leaves, final int maxDoc) {
            this.leafCount = leaves.size();
            this.docBases = new int[leafCount + 1];
            this.liveDocs = new Bits[leafCount];
            for (int i = 0; i < leafCount; i++) {
                final LeafReaderContext ctx = leaves.get(i);
                docBases[i] = ctx.docBase;
                liveDocs[i] = ctx.reader().getLiveDocs();
            }
            docBases[leafCount] = maxDoc;
        }

        boolean isLive(final int globalDocId, final int leafOrd) {
            final Bits bits = liveDocs[leafOrd];
            if (bits == null) return true;
            return bits.get(globalDocId - docBases[leafOrd]);
        }
    }

    /**
     * Scores all terms treating each Lucene document as an independent scoring unit,
     * and writes one score per term into {@link TermStats#scores()}.
     *
     * <p>For BM25 this is the natural mode: IDF is computed from true document
     * frequency, and length normalisation uses individual document lengths against
     * the corpus average. The recommended aggregation is summation across documents,
     * producing {@code IDF(t) × Σ_d saturated_normalised_tf(t,d)} — a single number
     * that reflects both how distinctive a term is and how consistently it appears
     * across documents.</p>
     *
     * <p>The resulting {@code scores[]} array can also be used as a term weight
     * vector for passage scoring: see the class-level documentation.</p>
     *
     * @param stats  destination statistics object; {@link TermStats#scores()} is
     *               filled and any previous content is overwritten
     * @param scorer local scorer (e.g. {@link TermScorer.BM25})
     * @throws IOException if Lucene term or postings iteration fails
     */
    public void score(
        final TermStats stats,
        final TermScorer scorer
    ) throws IOException {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(scorer, "scorer");
        validateStats(stats);

        final int maxDoc = fieldStats.maxDoc();
        final int corpusDocs = fieldStats.fieldDocs();
        final long corpusTokens = fieldStats.fieldWidth();

        final double[] scores = stats.scores();
        Arrays.fill(scores, 0d);

        if (corpusDocs <= 0) {
            return;
        }

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                "Field '" + field + "' was not indexed with term frequencies"
            );
        }

        scorer.corpus(corpusTokens, corpusDocs);

        final LeafLayout layout = new LeafLayout(leaves, maxDoc);

        // Two-pass per term: corpusTermDocs (hitCount) is needed before scorer.term(),
        // but is only known after iterating all postings.
        final int[] bufDocIds = new int[corpusDocs];
        final int[] bufFreqs  = new int[corpusDocs];

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef term;

        while ((term = tenum.next()) != null) {
            final int termId = lexicon.id(term);
            if (termId < 0) {
                continue;
            }

            int hitCount = 0;
            long corpusTermFreq = 0L;

            postings = tenum.postings(postings, PostingsEnum.FREQS);

            int leafOrd = 0;
            int nextLeafBase = layout.docBases[1];

            for (int docId = postings.nextDoc();
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = postings.nextDoc()) {

                while (docId >= nextLeafBase) {
                    leafOrd++;
                    nextLeafBase = layout.docBases[leafOrd + 1];
                }
                if (!layout.isLive(docId, leafOrd)) {
                    continue;
                }

                final int freq = postings.freq();
                if (freq <= 0) {
                    continue;
                }

                bufDocIds[hitCount] = docId;
                bufFreqs[hitCount]  = freq;
                hitCount++;
                corpusTermFreq += freq;
            }

            if (hitCount == 0) {
                scores[termId] = 0d;
                continue;
            }

            scorer.term(corpusTermFreq, hitCount);
            for (int i = 0; i < hitCount; i++) {
                final long docTokens = fieldStats.docWidth(bufDocIds[i]);
                scorer.score(bufFreqs[i], docTokens);
            }
            scores[termId] = scorer.result();
        }
    }

    /**
     * Scores all terms and returns the top-ranked results as an immutable list,
     * ordered by descending score.
     *
     * <p>Convenience wrapper around {@link #score(TermStats, TermScorer)} that
     * allocates a temporary {@link TermStats}, runs the full postings scan, and
     * resolves term strings from the bound lexicon.</p>
     *
     * @param scorer local scorer (e.g. {@code new TermScorer.BM25(1.3)})
     * @param topK   maximum number of results to return
     * @return immutable list of top-ranked terms, ordered by descending score
     * @throws IOException              if Lucene term or postings iteration fails
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public List<TermRow> topTerms(final TermScorer scorer, final int topK) throws IOException {
        Objects.requireNonNull(scorer, "scorer");
        if (topK < 1) {
            throw new IllegalArgumentException("topK=" + topK + ", expected >= 1");
        }

        final int vocabSize = lexicon.vocabSize();
        final TermStats stats = new TermStats(field, vocabSize);
        score(stats, scorer);

        final TopArray top = new TopArray(topK);
        final double[] scores = stats.scores();
        for (int termId = 0; termId < vocabSize; termId++) {
            final double s = scores[termId];
            if (!Double.isNaN(s) && s > 0d) {
                top.push(termId, s);
            }
        }

        final List<TermRow> rows = new ArrayList<>(top.length());
        for (TopArray.IdScore entry : top) {
            rows.add(new TermRow(
                entry.id(),
                lexicon.term(entry.id()),
                fieldStats.termFreq(entry.id()),
                entry.score()
            ));
        }
        return List.copyOf(rows);
    }
}