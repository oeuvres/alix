package com.github.oeuvres.alix.lucene.analysis.fr;

import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;


import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.CleanupFilter;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.TermReplaceFilter;

public class FrenchStopwordsDemo
{
    static String FIELD = "f";
    
    private FrenchStopwordsDemo()
    {
    }
    
    /** Minimal Analyzer for StandardTokenizer ->TermReplaceFilter. */
    private static Analyzer buildAnalyzer()
    {
        return new Analyzer()
        {
            @Override
            protected TokenStreamComponents createComponents(String fieldName)
            {
                Tokenizer tokenizer = new MarkupTokenizer(FrenchLexicons.buildBrevidot());
                TokenStream ts = tokenizer;
                // stop words
                ts = new CleanupFilter(ts);
                ts = new StopFilter(ts, FrenchLexicons.buildStopwords());
                // last filter prepare term to index
                return new TokenStreamComponents(tokenizer, ts);
            }
        };
    }
    
    static final List<Case> CASES = List.of(
        // --- Common 1990 forms that should normalize to classical canonical forms ---
        
        new Case(
                "Name",
                "<p>Les <a href=\"pop\">bons amis</a>, il faut aimer y aller.</p>",
                "explanation")
        
       
    );
            
    public static void main(String[] args) throws Exception
    {
        
        try (Analyzer analyzer = buildAnalyzer()) {
            System.out.println("\n==== PosTaggingFilterDemo ====\n");
            AnalysisDemoHelper.runAll(analyzer, FIELD, CASES);
        }
    }
    
}
