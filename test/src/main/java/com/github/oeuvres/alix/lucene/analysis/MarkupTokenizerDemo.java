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
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchLexicons;

/** Demonstrates the principal lexical, numerical and XML decisions of MarkupTokenizer. */
public final class MarkupTokenizerDemo
{
    /** Prevents instantiation of this demo. */
    private MarkupTokenizerDemo() {}

    /**
     * Runs the selected inputs with the actual French brevidot dictionary.
     * @param args unused command-line arguments
     */
    public static void main(final String[] args)
    {
        final CharArraySet brevidots = FrenchLexicons.buildBrevidots();
        try (Analyzer analyzer = AnalysisDemoHelper.analyzer(
                () -> new MarkupTokenizer(brevidots), UnaryOperator.identity())) {
            AnalysisDemoHelper.runAll(analyzer, "text", List.of(
                new Case("Initials and configured abbreviations",
                    "M. Dupont cite J.-J. Rousseau, Dr. Martin, etc. U.S.A. fin.",
                    "Initialisms, abbreviation protection, trailing sentence dots."),
                new Case("Uncertain abbreviation sequence",
                    "in Bull. Soc. neuch. sc. nat., La Sauge et Cornaux. Var. ventricosa",
                    "Comma and sentence context disambiguate trailing dots."),
                new Case("Inline and block markup",
                    "var. <i>parva</i> Moq. qu’on. <p>suite en bas de casse.</p>",
                    "Inline markup is transparent to lookahead; block markup is not."),
                new Case("French apostrophes, quotes and dash",
                    "Lorsque l’on parle d’« ontogenèse » — et que l’on n’a pas.",
                    "Inspect word boundaries and punctuation before clitic splitting."),
                new Case("Numerical forms",
                    "1\u202f234,56 12. 7–8 16ème 3E-10 2½ 25% 20°C 3×4",
                    "Grouping, decimal punctuation, range, ordinal, exponent, fraction and symbols."),
                new Case("Adjacent sentence punctuation",
                    "Phrase.?! Suite!Encore… Non.",
                    "Punctuation runs must not absorb adjacent lexical tokens."),
                new Case("Markup, entities and quoted delimiters",
                    "<a href=\"x>y\">A&amp;B</a> &lt;p&gt;Texte&lt;/p&gt;",
                    "Inspect XML tokens, decoded text and source offsets."),
                new Case("Page break",
                    "<a class=\"pb\" role=\"doc-pagebreak\" href=\"#p137\">[p.\u00a0137]</a>",
                    "The tokenizer exposes markup and page text to downstream filters."),
                new Case("Truncated markup",
                    "<a href=\"x\" Texte",
                    "Inspect behavior and offsets at end of input.")
            ));
        }
    }
}
