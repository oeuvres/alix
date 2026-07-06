package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;
import com.github.oeuvres.alix.util.Char;
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
            Larguier des Bancels,Larguier des Bancels
            R. Laffont,R. Laffont
            les Creusaz,les Creusaz
        """.lines().forEach(line -> {
            int comma = line.indexOf(',');
            final String key = line.substring(0, comma);
            if (key.isBlank()) return;
            final String value = line.substring(comma + 1);
            analyzer.expressions.addExpression(tokfr.tokenize(key), value);
        });
        """
            Stud.
        """.lines().forEach(line -> {
            line = line.trim();
            if (line.isBlank()) return;
            analyzer.brevidots.add(line);
        });
        System.out.println(analyzer.brevidots.contains("Stud."));
        return analyzer;
    }
    
    
    
    static final List<Case> CASES = List.of(
        new Case(
            "",
            """
            Les Creusaz et le Crêtet de la Perche (2000-2150 m). Entremont : partie supérieure
            Bringuier, Conversations libres avec Jean Piaget (p. 35-42). R. Laffont.
            Voir Larguier des Bancels, Introduction à la psychologie, 2e éd., p. 149. 15 
            """,
            ""
        ),
        new Case(
            "",
            """
            Orcula dolium Drap, et Pupilla triplicata Stud. — Assez rares, vivant sous les pierres de quelques rocailles.
            """,
            ""
        ),
        new Case(
            "",
            """
                var. <i>parva</i> z>p Moq. qu’on
                in Bull. Ps.,
                in Bull. Soc. neuch. sc. nat.,
                La Sauge et Cornaux. Var. ventricosa.
                Confer. Macy Foundat.,
            """,
            ""
        ),
        new Case(
            "l’intelli-",
            """
<p class="p">D’Alembert souligne cette sorte de « motivation » d’Alembert que Piaget a consacré une bonne partie de sa carrière à formuler en des termes plus spécifiques les structures mentales qui caractérisent le développement  l’intelli- des Children’s </p>
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
