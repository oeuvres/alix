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
            "éq. 1",
            "d’une éq. à l’éq. 1, d’où le renversement des signes",
            null
        ),
        new Case(
            "Name reso",
            """
            (Lev, 5 ; 10) C’est Mie tu vois.
            I. Meyerson, I. <span class=\"sc\">Meyerson</span>
            d’I. Meyerson et d'E. Meyerson, et Émile Meyerson, avec Meyerson.
            notre maître M. Arnold Reymond et des œuvres capitales de"
            M. E. Meyerson et de M. Brunschvicg. Parmi ces dernières, Les Etapes
            de la Philosophie mathématique, et, récemment, L’expérience humaine
            et la causalité physique ont eu sur nous une influence décisive.""",
            null
        ),
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
