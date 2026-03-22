package com.github.oeuvres.alix.lucene.analysis.fr;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MweFilter;
import com.github.oeuvres.alix.lucene.analysis.MweLexicon;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

import java.util.List;

/**
 */
public final class FrenchMweFilterDemo {

    private FrenchMweFilterDemo() {}

    private static final String FIELD = "f";


    /**
     * Critical cases aimed at stressing edge behaviors:
     * - curly apostrophes normalization (U+2019)
     * - hyphen variants normalization (U+2011)
     * - multiple hyphen suffix clitics and their ordering
     * - known side effects noted in the filter Javadoc
     * - robustness limit (MAX_STEPS)
     */
    static final List<AnalysisDemoHelper.Case> CASES = List.of(

        new AnalysisDemoHelper.Case(
            "Clitique",
            " c’est parce que l’homme est d’abord cause désastre du chemin de fer d'intérêt local.",
            null
        )    );

    public static void main(String[] args) throws Exception {
        MweLexicon lexicon = new MweLexicon(expressionAnalyzer(), "mwe", 2000);
        LexiconHelper.loadExpressions(lexicon, LexiconHelper.class, "/com/github/oeuvres/alix/fr/expressions.csv");
        lexicon.freeze();
        try (Analyzer analyzer = buildAnalyzer(lexicon)) {
            
            
            
            AnalysisDemoHelper.runAll(analyzer, FIELD, CASES);

        }
    }
    
    private static Analyzer expressionAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new WhitespaceTokenizer();
                TokenStream ts = new FrenchCliticSplitFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, ts);
            }
        };
    }


    /** Minimal Analyzer for MLTokenizer -> FrenchCliticSplitFilter. */
    private static Analyzer buildAnalyzer(MweLexicon lexicon) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MarkupTokenizer(FrenchLexicons.buildBrevidots());
                TokenStream ts = new FrenchCliticSplitFilter(tokenizer);
                ts = new MweFilter(ts, lexicon);
                return new TokenStreamComponents(tokenizer, ts);
            }
        };
    }

}
