package com.github.oeuvres.alix.lucene.analysis.fr;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoSupport;
import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoSupport.Case;
import com.github.oeuvres.alix.lucene.analysis.MLTokenizer;

import java.util.List;
import java.util.function.Supplier;

/**
 * Demo driver for {@link MLTokenizer}.
 *
 * <p>
 * Runs curated inputs through the tokenizer and dumps the resulting token
 * stream using {@link AnalysisDemoSupport}. This is intended for human
 * inspection and algorithm iteration (manual regression), not automated
 * testing.
 * </p>
 */
public class MLTokenizerDemo
{

    // --- curated cases (edit freely) ---
    static final List<Case> CASES = List.of(

            new Case("dots", "Abbreviation dot",
                    "M. Dupont rencontre Dr. Martin, etc. U.S.A. fin. C. H. Waddington, par exemple.",
                    "Decide policy: keep 'M.' as one token; internal dots in initialisms."),

            new Case("pb", "Page break",
                    "<a class=\"pb\" role=\"doc-pagebreak\" aria-hidden=\"true\" tabindex=\"-1\" href=\"#p137\" id=\"p137\">[p.\u00a0137]</a>",
                    ""),

            new Case("apos", "Curly apostrophe (French elision)",
                    "Lorsque l’on reconnaît, par exemple, que l’on n’a pas. ",
                    "Check whether ’ stays inside token (or is handled by a specific filter)."),

            new Case("C02", "Guillemets + nested quotes",
                    "Guyénot parle de « fonctionnement prophétique » et d’« ontogenèse préparant le futur ».",
                    "Quote marks should be punctuation tokens; words should not absorb them."),

            new Case("C03", "Em dash separators", "— et personne ne songe à en nier la nécessité —",
                    "Dash should be punctuation token(s); check spaces around."),

            new Case("Numbers", "Numbers + decimal separators + trailing punctuation",
                    "3,14 2.718 12, 12. 1,234 1.234 99... fin",
                    "Ensure trailing '.'/',' after a number becomes punctuation, not part of number."),

            new Case("C06", "Sentence punct adjacency", "Hello!World What?!No ... …?!",
                    "Ensure '!World' does not occur; sentence punct runs should not absorb letters."),

            new Case("C07", "Tags + entities", "<a href=\"x&y\">A&amp;B</a> &lt;p&gt;Texte&lt;/p&gt;",
                    "Check XML tokenization and decoding of &amp; / &lt; / &gt;."),

            new Case("C08", "Broken/truncated tag (EOF)", "<a href=\"x\" Texte",
                    "Decide behavior for unterminated tags; offsets must remain consistent."));

    public final class AnalyzerFactory
    {
        private AnalyzerFactory()
        {
        }

        /** Minimal analyzer that only uses the provided tokenizer (no filters). */
        public static Analyzer forTokenizer(Supplier<? extends Tokenizer> tokenizerFactory)
        {
            return new Analyzer()
            {
                @Override
                protected TokenStreamComponents createComponents(String fieldName)
                {
                    Tokenizer t = tokenizerFactory.get();
                    return new TokenStreamComponents(t);
                }
            };
        }
    }

    public static void main(String[] args) throws Exception
    {
        final String which = (args.length == 0) ? "orig" : args[0]; // orig | (reserved for future modes)
        switch (which) {
            case "orig" -> {
                Analyzer a = AnalyzerFactory.forTokenizer(() -> new MLTokenizer(FrenchLexicons.getDotEndingWords()));
                AnalysisDemoSupport.runAll(a, "f", CASES);
            }
            default -> {
                System.err.println("Usage: MLTokenizerDemo [orig]");
                System.exit(2);
            }
        }
    }

}
