package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;


import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.CleanupFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;

public class FrenchAnalyzerDemo
{
    static String FIELD = "f";
    
    private FrenchAnalyzerDemo()
    {
    }
    
    /** Minimal Analyzer for StandardTokenizer ->TermReplaceFilter. 
     * @throws IOException */
    private static Analyzer buildAnalyzer() throws IOException
    {
        return new FrenchAnalyzer();
    }
    
    static final List<Case> CASES = List.of(
        new Case(
                "Name",
                "<p class=\"p\"><i>D. a</i>. — Cette forme n’est signalée en Suisse qu’aux Brenêts et dans les laisses de l’Aar à Altenbourg. Bollinger (<i>loc. cit</i>.) en donne la distribution générale.</p>",
                "explanation"),
        new Case(
                "a priori",
                "Alors a lieu le débat classique des idées innées et même de l’<i>a priori</i> épistémologique.",
                null
        )
       
    );
            
    public static void main(String[] args) throws Exception
    {
        
        try (Analyzer analyzer = buildAnalyzer()) {
            System.out.println("\n==== PosTaggingFilterDemo ====\n");
            AnalysisDemoHelper.runAll(analyzer, FIELD, CASES);
        }
    }
    
}
