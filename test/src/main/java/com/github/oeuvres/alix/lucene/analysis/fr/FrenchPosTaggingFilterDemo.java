package com.github.oeuvres.alix.lucene.analysis.fr;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoSupport;
import com.github.oeuvres.alix.lucene.analysis.MLTokenizer;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Manual regression demo for PosTaggingFilter (OpenNLP UD French POS).
 *
 * Pipeline:
 *   MLTokenizer -> PosTaggingFilter(POSModel) -> FlagsToPosAttributeFilter
 *
 * Notes:
 * - PosTaggingFilter writes the predicted UPOS code into FlagsAttribute.
 * - AnalysisDemoSupport prints PosAttribute; the bridge filter copies flags -> pos.
 * - This program is for human inspection; it does not assert.
 */
public final class FrenchPosTaggingFilterDemo {

    private FrenchPosTaggingFilterDemo() {}

    private static final String FIELD = "f";


    /**
     * Curated cases targeting:
     * - sentence boundary handling (., !, ?, paragraph-ish inputs)
     * - initial-cap downcasing workaround (and its current bug: all caps tokens)
     * - contracted forms (au/du/aux) => ADP+DET
     * - proper nouns and acronyms
     * - numbers/punctuation
     * - "que" and other ambiguous function words
     * - robustness on long sentences (SENTMAX=300)
     */
    static final List<AnalysisDemoSupport.Case> CASES = List.of(

        new AnalysisDemoSupport.Case(
                "Clitique",
                "L’Homme est l’Avenir de l’Apocalypse.",
                null
            ),
        new AnalysisDemoSupport.Case(
                "Clitique",
                "Ce est la vie.",
                null
            ),
        
        new AnalysisDemoSupport.Case(
            "Mixed XML tag",
            "il <i>ira</i> où ?",
            null
        ),

        new AnalysisDemoSupport.Case(
            "Sentence-initial capital (workaround for 'Tu' mis-tagging)",
            "Tu vas bien ?",
            null
        ),

        new AnalysisDemoSupport.Case(
            "Sentence-initial capital (workaround for 'Tu' mis-tagging)",
            "Tu crois ça ?",
            null
        ),

        
        new AnalysisDemoSupport.Case(
            "Sentence-initial capital (workaround for 'Tu' mis-tagging)",
            "Je vais bien.",
            null
        ),

        new AnalysisDemoSupport.Case(
            "Baseline: DET NOUN VERB DET NOUN",
            "Le chat mange la souris.",
            null
        ),
        


        new AnalysisDemoSupport.Case(
            "Adjectives and adverbs",
            "Ce très grand arbre tombe vite.",
            "Expect: ce=DET/PRON, très=ADV, grand=ADJ, arbre=NOUN, tombe=VERB, vite=ADV."
        ),


        new AnalysisDemoSupport.Case(
            "Proper nouns after token #0 (exposes 'firstToken' bug if lowercased)",
            "Jean Dupont vit à Paris.",
            "Watch whether 'Dupont' and 'Paris' are fed lowercased to the tagger (should NOT happen)."
        ),

        new AnalysisDemoSupport.Case(
            "Acronym at sentence start (corner case for downcasing workaround)",
            "USA signe un accord.",
            "If you lowercased 'USA' -> 'usa', you may harm tagging/term preservation."
        ),

        new AnalysisDemoSupport.Case(
            "Contracted ADP+DET forms (au/du/aux)",
            "Il va au marché du village, puis aux halles.",
            "Expect tags for au/du/aux often come back as ADP+DET; you map that to Upos.ADP_DET."
        ),

        new AnalysisDemoSupport.Case(
            "Numbers + comma decimals",
            "En 2024, il a gagné 3,14 euros.",
            "Check: 2024=NUM, 3,14=NUM (often), euros=NOUN; punctuation should remain punctuation."
        ),

        new AnalysisDemoSupport.Case(
            "SCONJ 'que' (common dependency)",
            "Je sais que tu viens.",
            "Many UD models tag 'que' as SCONJ here; verify it lands in Upos.SCONJ."
        ),

        new AnalysisDemoSupport.Case(
            "Quotes / parentheses / colon",
            "« Oui », dit-il (très calmement) : \"je viens\".",
            "Primarily checks robustness + punctuation boundary detection in your upstream tokenizer."
        ),

        new AnalysisDemoSupport.Case(
            "Two sentences (queue flush on sentence punctuation)",
            "Le chat mange. Le chien dort.",
            "Verify tagging happens per sentence boundary; ensure no token loss between sentences."
        ),

        new AnalysisDemoSupport.Case(
            "Ellipsis and exclamation",
            "Alors… vraiment !",
            "Check punctuation and whether ellipsis confuses sentence segmentation."
        )

        /* it works
        new AnalysisDemoSupport.Case(
            "long-01",
            "Long sentence > SENTMAX (stress chunking; SENTMAX=300)",
            buildLongSentence(320),
            "Should not crash; should process in chunks. Verify no token duplication or loss around the 300-token boundary."
        )
        */
    );

    public static void main(String[] args) throws Exception {
        
        final POSModel model = loadModel();
        POSTaggerME tagger = new POSTaggerME(model);
        System.out.println(Arrays.toString(tagger.getAllPosTags()));

        try (Analyzer analyzer = buildAnalyzer(model)) {
            System.out.println("\n==== PosTaggingFilterDemo ====\n");
            AnalysisDemoSupport.runAll(analyzer, FIELD, CASES);
        }
    }

    private static Analyzer buildAnalyzer(final POSModel model) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MLTokenizer(FrenchLexicons.getDotEndingWords());
                TokenStream stream = new FrenchCliticSplitFilter(tokenizer);
                // stream = new PosTaggingFilter(stream, model);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }
    
    private static POSModel loadModel() throws IOException {
        final String spec = "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";

        InputStream in = FrenchPosTaggingFilterDemo.class.getResourceAsStream(spec);

        /*
        // 2) Try filesystem path
        if (in == null) {
            final Path p = Path.of(spec);
            if (Files.exists(p)) {
                in = Files.newInputStream(p);
            }
        }
         */

        try (InputStream autoClose = in) {
            return new POSModel(autoClose);
        }
    }


    private static String buildLongSentence(final int tokenCount) {
        // tokenCount word tokens + final period.
        StringBuilder sb = new StringBuilder(tokenCount * 6);
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) sb.append(' ');
            sb.append("mot").append(i);
        }
        sb.append('.');
        return sb.toString();
    }
}

