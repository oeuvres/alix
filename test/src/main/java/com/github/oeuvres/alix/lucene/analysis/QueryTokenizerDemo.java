package com.github.oeuvres.alix.lucene.analysis;

import java.util.List;

import org.apache.lucene.analysis.Analyzer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;

/**
 * Console demonstration of {@link QueryTokenizer}.
 *
 * <p>This class is deliberately not a JUnit test. It prints token terms, source
 * slices, offsets, and positions so that the tokenizer behavior can be inspected
 * manually.</p>
 *
 * <p>The expected streams below use {@code |} only as a visual token boundary;
 * it is not itself a token.</p>
 */
public final class QueryTokenizerDemo {
    /** Field name supplied to the demonstration analyzer. */
    private static final String FIELD = "text";

    /**
     * Prevent instantiation of this utility class.
     */
    private QueryTokenizerDemo() {
    }

    /**
     * Run the curated query-tokenizer demonstrations.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        try (Analyzer analyzer = new DemoAnalyzer()) {
            AnalysisDemoHelper.runAll(analyzer, FIELD, cases());
        }
    }

    /**
     * Build the ordered list of demonstrations.
     *
     * @return immutable demonstration cases
     */
    private static List<Case> cases() {
        return List.of(
            new Case(
                "Quoted d’Alembert",
                "\"d’Alembert\"",
                "Expected: d'Alembert ; quoted text is one protected token, without quote delimiters"
            ),
            new Case(
                "Quoted expression with space",
                "\"Bachelard Suzanne\"",
                "Expected: Bachelard Suzanne ; one token, because quotes protect the expression"
            ),
            new Case(
                "Unclosed quote",
                "\"Bachelard Suzanne",
                "Expected: Bachelard Suzanne ; unmatched opening quote protects until end of input"
            ),
            new Case(
                "Parentheses and prefix joker",
                "(Bach*)",
                "Expected: ( | Bach* | ) ; parentheses are standalone tokens, joker is kept"
            ),
            new Case(
                "Prefix and wildcard jokers",
                "Bach* psycholog?",
                "Expected: Bach* | psycholog? ; both are kept as pattern tokens"
            ),
            new Case(
                "Mixed query syntax",
                "\"d’Alembert\" (Bach*) j’aime",
                "Expected: d'Alembert | ( | Bach* | ) | j'aime"
            ),
            new Case(
                "Unquoted apostrophes are preserved by this tokenizer",
                "d’Alembert d’accord j’aime",
                "Expected: d'Alembert | d'accord | j'aime ; no clitic split at tokenizer level"
            ),
            new Case(
                "Hyphenated and underscored forms",
                "Jean-Jacques Bachelard_Suzanne",
                "Expected: Jean-Jacques | Bachelard_Suzanne"
            ),
            new Case(
                "Dots kept inside query atoms",
                "M. Piaget vol.42",
                "Expected: M. | Piaget | vol.42 ; dot is kept inside query atoms for now"
            ),
            new Case(
                "Noise punctuation is skipped",
                "Piaget, Bachelard; d’Alembert.",
                "Expected: Piaget | Bachelard | d'Alembert. ; comma and semicolon are separators"
            )
        );
    }

    /**
     * Analyzer used only by this demonstration.
     */
    private static final class DemoAnalyzer extends Analyzer {
        /**
         * Build tokenizer components for one analyzed field.
         *
         * @param fieldName analyzed field name
         * @return analyzer components containing a {@link QueryTokenizer}
         */
        @Override
        protected TokenStreamComponents createComponents(final String fieldName) {
            return new TokenStreamComponents(new QueryTokenizer());
        }
    }
}