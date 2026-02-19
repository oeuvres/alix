package com.github.oeuvres.alix.lucene.analysis.fr;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoSupport;
import com.github.oeuvres.alix.lucene.analysis.MLTokenizer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import java.util.List;

/**
 * Demo driver for {@link FrenchCliticSplitFilter}.
 *
 * <p>This program is for human inspection. It runs curated French strings containing
 * apostrophe elisions and hyphen clitics through an {@link Analyzer} composed of:
 *
 * <pre>
 *   MLTokenizer -> FrenchCliticSplitFilter
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>The filter only triggers if the upstream tokenizer keeps apostrophes/hyphens inside tokens.
 *       If your tokenizer already splits on '-' or '\'', you will see little to no effect.</li>
 *   <li>One case is intentionally pathological and may trigger the filter's MAX_STEPS protection.
 *       The demo catches the exception so you can see which input caused it.</li>
 * </ul>
 */
public final class FrenchCliticSplitFilterDemo {

    private FrenchCliticSplitFilterDemo() {}

    private static final String FIELD = "f";

    /** 17 times "-le" should exceed MAX_STEPS=16 if the tokenizer keeps the whole token. */
    private static final String MAX_STEPS_TOKEN = buildRepeatedSuffixToken("fais", "-le", 17);

    /**
     * Critical cases aimed at stressing edge behaviors:
     * - curly apostrophes normalization (U+2019)
     * - hyphen variants normalization (U+2011)
     * - multiple hyphen suffix clitics and their ordering
     * - known side effects noted in the filter Javadoc
     * - robustness limit (MAX_STEPS)
     */
    static final List<AnalysisDemoSupport.Case> CASES = List.of(

        new AnalysisDemoSupport.Case(
            "apos-curly",
            "Curly apostrophes + common prefixes",
            "D’Alembert n’a pas dit d’abord.",
            "Exercises normalization of U+2019 to '\'' and prefix table: D'/n'/d'."
        ),

        new AnalysisDemoSupport.Case(
            "apos-long",
            "Longer apostrophe prefixes (lorsqu', puisqu', qu')",
            "Lorsqu’il arrive, puisqu’on sait ce qu’il veut.",
            "Should emit tokens like 'lorsque' + 'il', 'puisque' + 'on', 'que' + 'il'."
        ),

        new AnalysisDemoSupport.Case(
            "hyph-inversion",
            "Inversion and linking -t",
            "Habite-t-il ici ? Serait-ce vrai ?",
            "Should drop '-t' (suffix -t => null), split '-il' and '-ce'."
        ),

        new AnalysisDemoSupport.Case(
            "hyph-multi",
            "Multiple clitic suffixes (ordering)",
            // U+2011 (non-breaking hyphen) to test lastHyphenIndexAndNormalize
            "Rends‑le‑moi; donne-les-leur.",
            "Expect base then clitics in surface order: rends + le + moi; donne + les + leur."
        ),

        new AnalysisDemoSupport.Case(
            "hyph-y-en",
            "Y/En clitics",
            "Allons-y, parlons-en.",
            "Expect allons + y; parlons + en."
        ),

        new AnalysisDemoSupport.Case(
            "xml-skip",
            "Do not split inside XML tags",
            "<a href=\"d'Artagnan\">d'Artagnan</a> d'Artagnan",
            "Filter skips splitting when PosAttribute==XML for tag tokens. If you observe splits inside <...>, your upstream does not set pos=XML or tags are not tokenized as XML."
        ),

        new AnalysisDemoSupport.Case(
            "hyph-null-suffix",
            "Suffixes mapped to null (-ci, -là)",
            "Ceux-ci viennent cette année-là.",
            "Filter strips -ci/-là without emitting a token (value null). Verify this is desired."
        ),

        new AnalysisDemoSupport.Case(
            "known-side-effect",
            "Known side effect: qu’en-dira-t-on",
            "Qu’en-dira-t-on ?",
            "Comment in filter mentions this form. Observe what splits and what remains glued."
        ),

        new AnalysisDemoSupport.Case(
            "lexicalized",
            "Potentially over-aggressive splits (quelqu', l')",
            "Quelqu’un l’aime; L’Oreal est cité.",
            "PREFIX contains quelqu' => quelque + un; l' is preserved as " +
                "l' (not expanded). Both behaviors may be controversial."
        ),

        new AnalysisDemoSupport.Case(
            "max-steps",
            "Pathological: exceed MAX_STEPS",
            MAX_STEPS_TOKEN,
            "If this crashes, it demonstrates a robustness limit. If it does not, your tokenizer likely split the token earlier."
        )
    );

    public static void main(String[] args) throws Exception {
        try (Analyzer analyzer = buildAnalyzer()) {
            System.out.println("\n==== FrenchCliticSplitFilterDemo ====\n");
            for (AnalysisDemoSupport.Case c : CASES) {
                System.out.println("---- " + c.id() + " | " + c.title() + " ----");
                if (c.notes() != null && !c.notes().isEmpty()) {
                    System.out.println("Notes: " + c.notes());
                }
                System.out.println("Input: " + AnalysisDemoSupport.escape(c.input()));

                try {
                    // Use the Analyzer path to avoid any Tokenizer-only helper limitations.
                    AnalysisDemoSupport.printTokens(analyzer, FIELD, c.input());
                }
                catch (RuntimeException ex) {
                    // Intentional: keep demo running so you can see which case triggered the failure.
                    System.out.println("[ERROR] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
                System.out.println();
            }
        }
    }

    /** Minimal Analyzer for MLTokenizer -> FrenchCliticSplitFilter. */
    private static Analyzer buildAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MLTokenizer(FrenchLexicons.getDotEndingWords());
                TokenStream stream = new FrenchCliticSplitFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    private static String buildRepeatedSuffixToken(final String base, final String suffix, final int count) {
        StringBuilder sb = new StringBuilder(base.length() + suffix.length() * count);
        sb.append(base);
        for (int i = 0; i < count; i++) sb.append(suffix);
        return sb.toString();
    }
}
