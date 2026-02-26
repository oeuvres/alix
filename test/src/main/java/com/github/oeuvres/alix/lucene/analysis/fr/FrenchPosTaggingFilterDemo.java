/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.analysis.fr;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.MarkupFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.SentenceStartLowerCaseFilter;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

import java.io.IOException;
import java.io.InputStream;
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

    private static Analyzer buildAnalyzer(final POSModel model) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MarkupTokenizer(FrenchLexicons.getDotEndingWords());
                TokenStream stream = tokenizer;
                stream = new MarkupFilter(stream);
                stream = new FrenchCliticSplitFilter(stream);
                stream = new SentenceStartLowerCaseFilter(stream, FrenchLexicons.getLemmaLexicon());
                stream = new PosTaggingFilter(stream, model, PosTaggingFilter.HYPHEN_REWRITER);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }


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
    static final List<AnalysisDemoHelper.Case> CASES = List.of(
        new AnalysisDemoHelper.Case(
            "Clitique",
            "Le sujet, tout d’abord, peut le classer de plusieurs manières :",
            null
        ),

        new AnalysisDemoHelper.Case(
            "Clitique",
            "L’Homme est l’Avenir de l’Apocalypse.",
            null
        ),
        new AnalysisDemoHelper.Case(
                "Clitique",
                "Ce est la vie.",
                null
            ),
        
        new AnalysisDemoHelper.Case(
            "Mixed XML tag",
            "il <i>ira</i> où ?",
            null
        ),

        new AnalysisDemoHelper.Case(
            "Sentence-initial capital (workaround for 'Tu' mis-tagging)",
            "Tu vas bien ?",
            null
        ),

        new AnalysisDemoHelper.Case(
            "Sentence-initial capital (workaround for 'Tu' mis-tagging)",
            "Tu crois ça ?",
            null
        ),

        
        new AnalysisDemoHelper.Case(
            "Sentence-initial capital (workaround for 'Tu' mis-tagging)",
            "Je vais bien.",
            null
        ),

        new AnalysisDemoHelper.Case(
            "Baseline: DET NOUN VERB DET NOUN",
            "Le chat mange la souris.",
            null
        ),
        


        new AnalysisDemoHelper.Case(
            "Adjectives and adverbs",
            "Ce très grand arbre tombe vite.",
            "Expect: ce=DET/PRON, très=ADV, grand=ADJ, arbre=NOUN, tombe=VERB, vite=ADV."
        ),


        new AnalysisDemoHelper.Case(
            "Proper nouns after token #0 (exposes 'firstToken' bug if lowercased)",
            "Jean Dupont vit à Paris.",
            "Watch whether 'Dupont' and 'Paris' are fed lowercased to the tagger (should NOT happen)."
        ),

        new AnalysisDemoHelper.Case(
            "Acronym at sentence start (corner case for downcasing workaround)",
            "USA signe un accord.",
            "If you lowercased 'USA' -> 'usa', you may harm tagging/term preservation."
        ),

        new AnalysisDemoHelper.Case(
            "Contracted ADP+DET forms (au/du/aux)",
            "Il va au marché du village, puis aux halles.",
            "Expect tags for au/du/aux often come back as ADP+DET; you map that to Upos.ADP_DET."
        ),

        new AnalysisDemoHelper.Case(
            "Numbers + comma decimals",
            "En 2024, il a gagné 3,14 euros.",
            "Check: 2024=NUM, 3,14=NUM (often), euros=NOUN; punctuation should remain punctuation."
        ),

        new AnalysisDemoHelper.Case(
            "SCONJ 'que' (common dependency)",
            "Je sais que tu viens.",
            "Many UD models tag 'que' as SCONJ here; verify it lands in Upos.SCONJ."
        ),

        new AnalysisDemoHelper.Case(
            "Quotes / parentheses / colon",
            "« Oui », dit-il (très calmement) : \"je viens\".",
            "Primarily checks robustness + punctuation boundary detection in your upstream tokenizer."
        ),

        new AnalysisDemoHelper.Case(
            "Two sentences (queue flush on sentence punctuation)",
            "Le chat mange. Le chien dort.",
            "Verify tagging happens per sentence boundary; ensure no token loss between sentences."
        ),

        new AnalysisDemoHelper.Case(
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
            AnalysisDemoHelper.runAll(analyzer, FIELD, CASES);
        }
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

}

