/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
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

package com.github.oeuvres.alix.lucene.analysis;

import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;

/**
 * Console demonstration of {@link MarkupTokenizer} trailing-dot resolution.
 *
 * <p>This class is deliberately not a test. It prints terms, source slices, offsets, positions,
 * and POS values so that a human reader can inspect the tokenizer's decisions.</p>
 *
 * <p>In the expected streams below, {@code |} marks a token boundary and is not itself a token.
 * Configured brevidots are stored without their final dot.</p>
 */
public final class MarkupTokenizerDemo
{
    /** Field name supplied to the demonstration analyzer. */
    private static final String FIELD = "text";

    /**
     * Prevent instantiation of this utility class.
     */
    private MarkupTokenizerDemo()
    {
    }
    
    /**
     * Analyzer used only by this demonstration.
     */
    private static final class DemoAnalyzer extends Analyzer
    {
        /** Configured brevidots, without their final dot. */
        private static final CharArraySet BREVIDOTS = new CharArraySet(
            List.of("Confer.", "Dr.", "etc.", "larg.", "Var.", "Stud."),
            false
        );

        /**
         * Build tokenizer components for one analyzed field.
         *
         * @param fieldName analyzed field name
         * @return analyzer components containing a configured {@link MarkupTokenizer}
         */
        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            return new TokenStreamComponents(new MarkupTokenizer(BREVIDOTS));
        }
    }

    /**
     * Build the ordered list of demonstrations.
     *
     * @return immutable demonstration cases
     */
    private static List<Case> cases()
    {
        return List.of(
            new Case(
                "enfant. a Source",
                """
            Orcula dolium Drap., et Pupilla triplicata Stud. — Assez rares, vivant sous les pierres de quelques rocailles.
                """,
                ""
            ),
            new Case(
                "enfant. a Source",
                """
                (Voir Piaget, Zool. Anzeig., 1913, vol. 42). Ensuite, je dois dire que, conformément.
                sans indice sonore chez l’enfant. Ex. l’enfant ne reproduira le baillement
                """,
                ""
            ),
            new Case(
                "enfant. a Source",
                """
qu’est la psychologie de l’enfant.</p>
          </div>
          <section class="footnotes">
            <aside role="doc-footnote" class="a note source note" data-tei-type="source" id="fna"><a class="noteback" role="doc-backlink" href="#fnrefa">a</a> <p class="bibl source">Source : <span class="sc" style="font-variant:small-caps;">Piaget</span>, J. (1946). 
                """,
                ""
            ),
            new Case(
                "Block tag ends a sentence before a lowercase block",
                "de l'enfant.</p>\n<aside id=\"fna\"><a href=\"#fnrefa\">a</a> <p>Source :",
                "Expected: de | l'enfant | . | </p> | <aside…> | <a…> | a | </a>… ; p is a block tag"
            ),
            new Case(
                "Opening block tag in mixed content",
                "<div>intro enfant.<p>suite en bas de casse",
                "Expected: <div> | intro | enfant | . | <p> | suite | …; opening tags also match"
            ),
            new Case(
                "Unknown abbreviations at block end detach like end of input",
                "in Bull. Soc.</p>",
                "Expected: in | Bull | . | Soc | . | </p>; protection is the brevidot list's job"
            ),
            new Case(
                "différent. — IV. Le",
                """
remercier bien sincèrement M. le Prof. Th. Studer, de Berne, pour l’intérêt qu’il a témoigné
                """,
                ""
            ),
            new Case(
                "différent. — IV. Le",
                """
le sujet ne saurait être différent.</p>
            </section>
            <section class="level2 div">
              <h2 class="head" id="iv-le-constructivisme-et-la-creation-des-nouveautes" tabindex="-1">IV. — Le constructivisme et la création des nouveautés<a class="bookmark" aria-hidden="true" href="#iv-le-constructivisme-et-la-creation-des-nouveautes">🔗</a></h2>
              <p class="noindent p">En conclusion de ce petit ouvrage,
                """,
                ""
            ),
            new Case(
                "ibid.",
                "Le Continu et le discontinu, p. 35-36.6 Ibid., p. 36.7 Ibid., p. 37.8 Ibid., p. 35.9 Ibid., p. 40.10 Ibid., p. 73.11 Ibid.,",
                ""
            ),
            new Case(
                "ça.",
                "Parce que ça fait plus long que ça. — D’accord. Et si je le faisais encore bien plus long",
                ""
            ),
            new Case(
                "Unknown dotted sequence resolved by a number",
                "BAD. abrév. 1914 — ex: larg. 12 cm",
                "Expected: BAD. | abrév | . | 1914 | — | ex | : | larg. | 12 | cm"
            ),
            new Case(
                "XML tokens are transparent to lookahead",
                "var. <i>parva</i> Moq. qu’on",
                "Expected: var. | <i> | parva | </i> | Moq. | qu'on"
            ),
            new Case(
                "Comma keeps an uncertain dotted sequence",
                "in Bull. Ps.,",
                "Expected: in | Bull. | Ps. | ,"
            ),
            new Case(
                "Comma keeps a long dotted sequence",
                "in Bull. Soc. neuch. sc. nat.,",
                "Expected: in | Bull. | Soc. | neuch. | sc. | nat. | ,"
            ),
            new Case(
                "Configured brevidot starts after a sentence boundary",
                "La Sauge et Cornaux. Var. ventricosa",
                "Expected: La | Sauge | et | Cornaux | . | Var. | ventricosa; Var is configured"
            ),
            new Case(
                "Configured brevidot before an uppercase name",
                "Confer. Macy Foundat.,",
                "Expected: Confer. | Macy | Fondat. | ,; Confer is configured"
            ),
            new Case(
                "Configured brevidot before a number",
                "larg. 12 cm",
                "Expected: larg. | 12 | cm; larg is configured"
            ),
            new Case(
                "Configured and structural brevidots",
                "Dr. Martin cite J.-J. Rousseau.",
                "Expected: Dr. | Martin | cite | J.-J. | Rousseau | .; Dr is configured, J.-J. is structural"
            ),
            new Case(
                "A spaced dot is already sentence punctuation",
                "abrév . Suivante",
                "Expected: abrév | . | Suivante"
            ),
            new Case(
                "Detached dot merges with adjacent sentence punctuation",
                "Phrase.?! Suite",
                "Expected: Phrase | .?! | Suite"
            ),
            new Case(
                "End of input resolves only the last unknown dot",
                "Bull. Ps.",
                "Expected: Bull. | Ps | ."
            ),
            new Case(
                "Configured brevidot at end of input",
                "etc.",
                "Expected: etc.; no sentence event is emitted because etc is configured"
            )
        );
    }

    /**
     * Run the curated tokenizer demonstrations.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args)
    {
        try (Analyzer analyzer = new DemoAnalyzer()) {
            AnalysisDemoHelper.runAll(analyzer, FIELD, cases());
        }
    }


}
