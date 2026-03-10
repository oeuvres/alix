package com.github.oeuvres.alix.lucene.terms;

import com.github.oeuvres.alix.util.TopArray;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Minimal demo for {@link ThemeTerms}.
 *
 * <p>Usage:</p>
 * <pre>
 * java ... com.github.oeuvres.alix.lucene.terms.ThemeTermsDemo /path/to/index text
 * java ... com.github.oeuvres.alix.lucene.terms.ThemeTermsDemo /path/to/index text 16 30
 * </pre>
 *
 * <p>Arguments:</p>
 * <ul>
 *   <li>{@code args[0]}: path to an existing Lucene index directory</li>
 *   <li>{@code args[1]}: indexed field to analyze</li>
 *   <li>{@code args[2]}: optional number of token-balanced parts, default {@code 16}</li>
 *   <li>{@code args[3]}: optional number of displayed terms, default {@code 30}</li>
 * </ul>
 */
public final class ThemeTermsDemo {
    /** Default number of token-balanced parts. */
    private static final int DEFAULT_PARTS = 100;

    /** Default number of displayed terms. */
    private static final int DEFAULT_TOP_K = 50;

    private ThemeTermsDemo() {
    }

    public static void main(final String[] args) throws Exception {
        /*
        if (args.length < 2 || args.length > 4) {
            usageAndExit();
        }
        */

        // final Path indexPath = Path.of("D:\\code\\piaget-labo\\lucene\\test");
        final Path indexPath = Path.of("D:\\code\\piaget-labo\\lucene\\piaget");
        final String field = "text";
        final int partCount = (args.length >= 3) ? Integer.parseInt(args[2]) : DEFAULT_PARTS;
        final int topK = (args.length >= 4) ? Integer.parseInt(args[3]) : DEFAULT_TOP_K;

        if (partCount < 1) {
            throw new IllegalArgumentException("partCount must be >= 1");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }

        
        
        ensureLexicon(indexPath, field);
        ensureFieldStats(indexPath, field);

        try (
            FSDirectory dir = FSDirectory.open(indexPath);
            DirectoryReader reader = DirectoryReader.open(dir)
        ) {
            final TermLexicon lexicon = TermLexicon.open(indexPath, field);
            final FieldStats fieldStats = FieldStats.open(indexPath, field);
            final ThemeTerms themeTerms = new ThemeTerms(reader, lexicon, fieldStats);
            final TermStats stats = new TermStats(field, lexicon.vocabSize());

            final long[] partTokenCounts = new long[partCount];
            final int[] partByDocId = ThemeTerms.quantiles(
                fieldStats,
                naturalOrder(fieldStats.maxDoc()),
                partTokenCounts
            );

            // final TermScorer scorer = TermScorers.JACCARD;
            // final ThemeTerms.Aggregation aggregation = ThemeTerms.Aggregation.SUM_POSITIVE;

            TermScorer scorer = new TermScorer.G();
            themeTerms.score(stats, partByDocId, partTokenCounts, scorer);

            printContext(indexPath, fieldStats, partCount, scorer);
            printTopScores(lexicon, stats.scores(), topK);
        }
    }

    /**
     * Ensures that the term lexicon exists for one field.
     *
     * @param indexPath Lucene index directory
     * @param field indexed field
     * @throws IOException if creation fails
     */
    private static void ensureLexicon(final Path indexPath, final String field) throws IOException {
        if (!TermLexicon.exists(indexPath, field)) {
            System.out.println("Lexicon files not found for field '" + field + "'. Building them...");
            TermLexicon.write(indexPath, field);
        }
    }

    /**
     * Ensures that the field statistics exist for one field.
     *
     * @param indexPath Lucene index directory
     * @param field indexed field
     * @throws IOException if creation fails
     */
    private static void ensureFieldStats(final Path indexPath, final String field) throws IOException {
        if (!FieldStats.exists(indexPath, field)) {
            System.out.println("FieldStats file not found for field '" + field + "'. Building it...");
            FieldStats.write(indexPath, field);
        }
    }

    /**
     * Returns the natural global doc-id order {@code 0..maxDoc-1}.
     *
     * @param maxDoc reader maxDoc
     * @return global doc ids in natural order
     */
    private static int[] naturalOrder(final int maxDoc) {
        final int[] docIds = new int[maxDoc];
        for (int docId = 0; docId < maxDoc; docId++) {
            docIds[docId] = docId;
        }
        return docIds;
    }

    /**
     * Prints the context of one run.
     *
     * @param indexPath Lucene index directory
     * @param fieldStats field statistics
     * @param partCount number of parts
     * @param aggregation aggregation rule
     * @param scorer local scorer
     */
    private static void printContext(
        final Path indexPath,
        final FieldStats fieldStats,
        final int partCount,
        final TermScorer scorer
    ) {
        System.out.println("Index         : " + indexPath);
        System.out.println("Field         : " + fieldStats.field());
        System.out.println("maxDoc        : " + fieldStats.maxDoc());
        System.out.println("docCount      : " + fieldStats.docCount());
        System.out.println("vocabSize     : " + fieldStats.vocabSize());
        System.out.println("totalTermFreq : " + fieldStats.totalTermFreq());
        System.out.println("parts         : " + partCount);
        System.out.println("scorer  : " + scorer.getClass().getName());
        System.out.println();
    }

    /**
     * Prints the top positive scores.
     *
     * @param lexicon dense field lexicon
     * @param scores dense score vector indexed by term id
     * @param topK maximum number of rows to print
     */
    private static void printTopScores(
        final TermLexicon lexicon,
        final double[] scores,
        final int topK
    ) {
        final TopArray top = new TopArray(topK);

        for (int termId = 0; termId < scores.length; termId++) {
            final double score = scores[termId];
            if (Double.isNaN(score) || score <= 0d) {
                continue;
            }
            top.push(termId, score);
        }

        // System.out.println("Top " + top.length() + " theme terms");
        // System.out.println("------------------------------");
        for (TopArray.IdScore row : top) {
            System.out.print(lexicon.term(row.id() ) + ", ");
        }
    }

    /**
     * Prints usage and exits with code 2.
     */
    private static void usageAndExit() {
        System.err.println("Usage:");
        System.err.println("  ThemeTermsDemo <indexPath> <field>");
        System.err.println("  ThemeTermsDemo <indexPath> <field> <partCount> <topK>");
        System.exit(2);
    }
}