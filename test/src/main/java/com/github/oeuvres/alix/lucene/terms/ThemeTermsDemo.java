package com.github.oeuvres.alix.lucene.terms;

import com.github.oeuvres.alix.lucene.TopTerms;
import com.github.oeuvres.alix.lucene.TopTerms.TermEntry;
import com.github.oeuvres.alix.util.TopArray;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Path;
import java.util.List;

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

        final Path indexPath = Path.of("D:\\code\\alix\\web\\lucene\\piaget");
        final String field = "content";
        final int topK = (args.length >= 4) ? Integer.parseInt(args[3]) : DEFAULT_TOP_K;


        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }

        
        

        try (
            final FSDirectory dir = FSDirectory.open(indexPath);
            final DirectoryReader luceneReader = DirectoryReader.open(dir);
            final TermLexicon lexicon = TermLexicon.openOrBuild(luceneReader, indexPath, field);
        ) {
            final FieldStats fieldStats = FieldStats.openOrBuild(luceneReader, indexPath, field);
            // final TermStats stats = new TermStats(field, lexicon.vocabSize());
            // final int maxDoc = fieldStats.maxDoc();

            List<TermScorer> scorers = List.of(
                new TermScorer.BM25(0.9),
                new TermScorer.BM25(1.0),
                new TermScorer.BM25(1.1),
                new TermScorer.BM25(1.2),
                new TermScorer.BM25(1.3),
                new TermScorer.BM25(1.4),
                new TermScorer.BM25(1.5),
                new TermScorer.BM25(1.6),
                new TermScorer.G(),
                new TermScorer.Jaccard()
            );
            // scorers[1] = new TermScorer.G();
            // scorers[2] = new TermScorer.Jaccard();
            
            for (TermScorer scorer: scorers) {
                System.out.println("\n\n" + scorer + "\n");
                fieldStats.termWeights(luceneReader, scorer);
                TopTerms top = TopTerms.theme(fieldStats, lexicon, topK);
                for(TermEntry term: top) {
                    System.out.print(term.term() + ", ");
                }
            }

        }
    }



}