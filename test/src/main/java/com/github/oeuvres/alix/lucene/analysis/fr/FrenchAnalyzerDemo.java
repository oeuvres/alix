package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;
import com.github.oeuvres.alix.util.WordTokenizer;
import com.github.oeuvres.alix.util.fr.FrenchCliticTokenizer;
import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;


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
        WordTokenizer tokfr = new FrenchCliticTokenizer();
        FrenchAnalyzer analyzer = new FrenchAnalyzer();
        """
            Le Châtelier,Le Châtelier
            Vinh Bang,Vinh-Bang
        """.lines().forEach(line -> {
            int comma = line.indexOf(',');
            final String key = line.substring(0, comma);
            if (key.isBlank()) return;
            final String value = line.substring(comma + 1);
            analyzer.expressions.addExpression(tokfr.tokenize(key), value);
        });
        return analyzer;
    }
    
    static final List<Case> CASES = List.of(
        new Case(
            "l’intelli-",
            """
<p class="p">C’est pour souligner cette sorte de « motivation » que Piaget a consacré une bonne partie de sa carrière à formuler en des termes plus spécifiques les structures mentales qui caractérisent le développement  l’intelli- des Children’s </p>
              <p class="p"> </p>
""",
            null
        ),
        new Case(
            "Vinh-Bang",
            "processus de « modération » comme dans le principe de Le Châtelier."
            + " L’une des épreuves de Vinh Bang consiste à présenter à l’entrée une règle horizontale",
            null
        ),
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
