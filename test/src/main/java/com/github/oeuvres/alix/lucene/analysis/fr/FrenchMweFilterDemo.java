package com.github.oeuvres.alix.lucene.analysis.fr;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
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
            "L’homme est l’Avenir de l’Apocalypse à l’abord du désastre.",
            null
        )    );

    public static void main(String[] args) throws Exception {
        MweLexicon lexicon = new MweLexicon(expressionAnalyzer(), "mwe", 2000);
        
        try (Analyzer analyzer = buildAnalyzer()) {
            
            
            
            AnalysisDemoHelper.runAll(analyzer, FIELD, CASES);

        }
    }
    
    private static Analyzer expressionAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new WhitespaceTokenizer();
                TokenStream stream = new FrenchCliticSplitFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }


    /** Minimal Analyzer for MLTokenizer -> FrenchCliticSplitFilter. */
    private static Analyzer buildAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MarkupTokenizer(FrenchLexicons.buildBrevidots());
                TokenStream stream = new FrenchCliticSplitFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

}
