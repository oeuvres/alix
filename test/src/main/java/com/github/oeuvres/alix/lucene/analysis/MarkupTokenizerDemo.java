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

    /**
     * Build the ordered list of demonstrations.
     *
     * @return immutable demonstration cases
     */
    private static List<Case> cases()
    {
        return List.of(
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
     * Analyzer used only by this demonstration.
     */
    private static final class DemoAnalyzer extends Analyzer
    {
        /** Configured brevidots, without their final dot. */
        private static final CharArraySet BREVIDOTS = new CharArraySet(
            List.of("Confer", "Dr", "etc", "larg", "Var"),
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
}
