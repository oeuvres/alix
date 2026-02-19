package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

import java.util.List;

/**
 * Demo driver for {@link TermReplaceFilter}.
 *
 * <p>This program is for human inspection. It runs curated strings through two analyzers:
 *
 * <pre>
 *   WhitespaceTokenizer -> TermReplaceFilter   (case-sensitive map)
 *   WhitespaceTokenizer -> TermReplaceFilter   (ignoreCase map)
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>This demo uses {@link WhitespaceTokenizer} so token boundaries are obvious.
 *       In a production analyzer you will likely use {@code StandardTokenizer} (or your own tokenizer),
 *       which changes what can match (punctuation handling, apostrophes, etc.).</li>
 *   <li>{@link TermReplaceFilter} rewrites only {@code CharTermAttribute}; offsets (and other attributes)
 *       remain those of the original token.</li>
 * </ul>
 */
public final class TermReplaceFilterDemo {

    private TermReplaceFilterDemo() {}

    private static final String FIELD = "f";

    /**
     * Curated cases aimed at illustrating:
     * - whole-token matching (including punctuation if the tokenizer keeps it)
     * - unicode normalization via explicit mapping
     * - case-sensitive vs ignoreCase lookup behavior
     */
    static final List<AnalysisDemoSupport.Case> CASES = List.of(

        new AnalysisDemoSupport.Case(
            "Simple spelling normalization",
            "colour colour, colour.",
            "Map contains keys for 'colour', 'colour,' and 'colour.' so that the tokenizer choice is visible."
        ),

        new AnalysisDemoSupport.Case(
            "Diacritics folding via explicit mapping",
            "résumé résumé, Résumé.",
            "Shows explicit replacements only; this is not an ASCIIFoldingFilter."
        ),

        new AnalysisDemoSupport.Case(
            "Unicode punctuation mapping (curly apostrophe, NB hyphen)",
            "D’Alembert l’Oréal e-mail",
            "Curly apostrophe U+2019 and NB hyphen U+2011 are replaced only if the exact token matches the map."
        ),

        new AnalysisDemoSupport.Case(
            "Case sensitivity",
            "NYC nyc Nyc",
            "Compare case-sensitive and ignoreCase maps; replacement is the same canonical form."
        )
    );

    public static void main(String[] args) throws Exception {
        // Two analyzers to make ignoreCase behavior explicit.
        try (
            Analyzer cs = buildAnalyzer(buildMap(false));
            Analyzer ic = buildAnalyzer(buildMap(true))
        ) {
            System.out.println("\n==== TermReplaceFilterDemo ====");
            System.out.println("Tokenizer: WhitespaceTokenizer");
            System.out.println("Filters:   TermReplaceFilter (case-sensitive) vs (ignoreCase)\n");

            for (AnalysisDemoSupport.Case c : CASES) {
                System.out.println("---- " +  c.title() + " ----");
                if (c.notes() != null && !c.notes().isEmpty()) {
                    System.out.println("Notes: " + c.notes());
                }
                System.out.println("Input: " + AnalysisDemoSupport.escape(c.input()));

                System.out.println("\n[case-sensitive]");
                AnalysisDemoSupport.printTokens(cs, FIELD, c.input());

                System.out.println("\n[ignoreCase]");
                AnalysisDemoSupport.printTokens(ic, FIELD, c.input());

                // Optional: first token-level difference between the two analyzers.
                final var a = AnalysisDemoSupport.collect(cs, FIELD, c.input());
                final var b = AnalysisDemoSupport.collect(ic, FIELD, c.input());
                System.out.println();
                AnalysisDemoSupport.firstDiff("case-sensitive", a, "ignoreCase", b, c.input());
                System.out.println();
            }
        }
    }

    /**
     * Minimal Analyzer for WhitespaceTokenizer -> TermReplaceFilter.
     */
    private static Analyzer buildAnalyzer(final CharArrayMap<char[]> map) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                final Tokenizer tokenizer = new WhitespaceTokenizer();
                final TokenStream stream = new TermReplaceFilter(tokenizer, map);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    /**
     * Demo mapping table.
     *
     * <p>Values are {@code char[]} to avoid per-token allocations.
     */
    private static CharArrayMap<char[]> buildMap(final boolean ignoreCase) {
        final CharArrayMap<char[]> map = new CharArrayMap<>(32, ignoreCase);

        // Spelling normalization (with punctuation variants for WhitespaceTokenizer).
        put(map, "colour",  "color");
        put(map, "colour,", "color,");
        put(map, "colour.", "color.");

        // Diacritics folding via explicit mapping.
        put(map, "résumé",  "resume");
        put(map, "résumé,", "resume,");
        put(map, "Résumé.", "resume.");

        // Unicode punctuation: curly apostrophe / NB hyphen.
        put(map, "D’Alembert", "D'Alembert"); // U+2019 -> '
        put(map, "l’Oréal",    "l'Oréal");
        put(map, "e-mail",     "email");     // U+2011 NB hyphen

        // Canonicalization of abbreviations.
        put(map, "NYC", "new_york_city");

        return map;
    }

    private static void put(final CharArrayMap<char[]> map, final String key, final String value) {
        map.put(key, value.toCharArray());
    }
}
