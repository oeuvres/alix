package com.github.oeuvres.alix.lucene.analysis.fr;

import java.util.List;
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;

import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper;
import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoHelper.Case;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.MweFilter;
import com.github.oeuvres.alix.lucene.analysis.ReplaceFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.MweLexicon;

/** Demonstrates lexical POS propagation with the real French MWE resources and verbal expansion. */
public final class MweLexiconDemo
{
    /** Prevents instantiation of this demo. */
    private MweLexiconDemo() {}

    /**
     * Compiles the French expressions, then prints the stream immediately after MweFilter.
     * No POS model, numerical filter or lexical lemmatizer is involved.
     * @param args unused command-line arguments
     */
    public static void main(final String[] args)
    {
        final CharArraySet brevidots = FrenchLexicons.buildBrevidots();
        final CharsMap normalizer = FrenchLexicons.buildNormalizer();
        final UnaryOperator<TokenStream> lexical = stream -> {
            stream.addAttribute(BoundaryAttribute.class);
            return new ReplaceFilter(new FrenchCliticSplitFilter(stream), normalizer);
        };
        try (Analyzer entries = AnalysisDemoHelper.analyzer(
                () -> new MarkupTokenizer(brevidots, MarkupMode.NONE), lexical)) {
            final VerbalMweCompiler compiler = FrenchLexicons.buildMweCompiler(entries);
            compiler.addExpression("25 objets", "", "25 objets");
            final MweLexicon lexicon = compiler.lexicon();
            lexicon.freeze();
            try (Analyzer analyzer = AnalysisDemoHelper.analyzer(
                    () -> new MarkupTokenizer(brevidots, MarkupMode.NONE),
                    stream -> new MweFilter(lexical.apply(stream), lexicon))) {
                AnalysisDemoHelper.runAll(analyzer, "text", List.of(
                    new Case("Lexical and specialized tags",
                        "De temps en temps, à cause de cela, par ailleurs.",
                        "ADVasp, ADP and ADV come from the lexicon."),
                    new Case("Verbal variants and clitics",
                        "Il prend conscience, puis ils prenaient conscience et se rendent compte.",
                        "Generated forms share the VERB tag of the canonical expression."),
                    new Case("Explicit expression versus generated form",
                        "La mise en place et les objets pris en charge, de fait et de faire.",
                        "Explicit NOUN and ADV expressions remain distinct from verbal expressions."),
                    new Case("Proper names and canonical aliases",
                        "De La Ciotat à New York, puis au Caire.",
                        "Proper-name expressions carry PROPN, including canonical aliases."),
                    new Case("Untagged expression",
                        "25 objets et 25 enfants.",
                        "The MWE receives TOKEN, not the NUM of its first component.")
                ));
            }
        }
    }
}
