package com.github.oeuvres.alix.lucene.analysis.fr;

import java.util.Arrays;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.SentenceStartLowerCaseFilter;
import com.github.oeuvres.alix.lucene.analysis.TermReplaceFilter;

public class FrenchTermReplaceFilterDemo
{
    static String FIELD = "f";
    
    private FrenchTermReplaceFilterDemo()
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
                Tokenizer tokenizer = new StandardTokenizer();
                TokenStream stream = tokenizer;
                stream = new TermReplaceFilter(stream, FrenchLexicons.getWordNormalizer());
                stream = new LemmaFilter(stream, FrenchLexicons.getLemmaLexicon());
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }
    
    static final List<Case> CASES = List.of(
        // --- Common 1990 forms that should normalize to classical canonical forms ---
        
        new Case(
                "Circumflex (île / août) in clitic context",
                "L'ile est calme en aout.",
                "Expect normalization of ile→île and aout→août; clitic tokens must not trigger ghost d'/l' lexicon entries."),
        
        new Case(
                "Common noun: maitre",
                "Le maitre parle aux élèves.",
                "Expect maitre→maître."),
        
        new Case(
                "Common noun plural: maitres",
                "Les maitres discutent après le cours.",
                "Expect maitres→maîtres."),
        
        new Case(
                "Very common verb infinitive: connaitre",
                "Il faut connaitre ce texte.",
                "Expect connaitre→connaître."),
        
        new Case(
                "Very common finite verb: connait",
                "Elle connait sa mort.",
                "Expect connait→connaît."),
        
        new Case(
                "Common noun: cout",
                "Le cout de la vie augmente.",
                "Expect cout→coût."),
        
        new Case(
                "Common noun: gout",
                "Le gout change avec l'âge.",
                "Expect gout→goût."),
        
        new Case(
                "Common verb infinitive: gouter",
                "Je vais gouter ce plat.",
                "Expect gouter→goûter."),
        
        new Case(
                "Common noun: piqure",
                "La piqure est douloureuse.",
                "Expect piqure→piqûre."),
        
        new Case(
                "Common noun: brulure",
                "La brulure est superficielle.",
                "Expect brulure→brûlure."),
        
        new Case(
                "Common compound noun (hyphen restored): weekend",
                "Le weekend sera court.",
                "Expect weekend→week-end."),
        
        new Case(
                "Common compound noun plural (hyphen restored): weekends",
                "Les weekends d'été passent vite.",
                "Expect weekends→week-ends."),
        
        new Case(
                "Common compound noun (hyphen restored): portemonnaie",
                "J'ai perdu mon portemonnaie.",
                "Expect portemonnaie→porte-monnaie."),
        
        new Case(
                "Common pastry noun (hyphen restored): millefeuille",
                "Un millefeuille pour le dessert.",
                "Expect millefeuille→mille-feuille."),
        
        new Case(
                "Common pastry noun plural (hyphen restored): millefeuilles",
                "Les millefeuilles étaient excellents.",
                "Expect millefeuilles→mille-feuilles."),
        
        // --- Guard / regression cases for lexicon cleanup ---
        
        new Case(
                "Artifact guard: bogus final -è form",
                "Il a abandonnè le projet.",
                "Regression check: abandonnè must NOT be normalized as a valid lexical form (spurious generation artifact)."),
        
        new Case(
                "Control: already classical spelling remains stable",
                "Le maître connaît le coût en août.",
                "No replacement expected on already-canonical forms; only normal analysis/lemmatization should apply.")
    );
            
    public static void main(String[] args) throws Exception
    {
        
        try (Analyzer analyzer = buildAnalyzer()) {
            System.out.println("\n==== PosTaggingFilterDemo ====\n");
            AnalysisDemoHelper.runAll(analyzer, FIELD, CASES);
        }
    }
    
}
