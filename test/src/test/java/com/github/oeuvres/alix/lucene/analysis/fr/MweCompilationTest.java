package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;

import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.CsvHeader;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.MweLexicon;

/** Regression checks for generic MWE loading and French lexical compilation. */
public final class MweCompilationTest
{
    /** Prevents instantiation of the test utility. */
    private MweCompilationTest() {}

    /**
     * Runs the regression checks with the normal Alix dependencies and resources.
     * @param args unused command-line arguments
     * @throws IOException if a temporary file or analyzer resource cannot be read
     */
    public static void main(final String[] args) throws IOException
    {
        generic();
        french();
    }

    /**
     * Checks exact lexical loading, without Hunspell or lemma aliases.
     * @throws IOException if the CSV cannot be written or read
     */
    private static void generic() throws IOException
    {
        final Path file = Files.createTempFile("alix-mwe-generic-", ".csv");
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            final MweLexicon lexicon = new MweLexicon(16);
            Files.writeString(file, "form,canonical\nde fait,\nNew York,NY\n", StandardCharsets.UTF_8);
            try (CSVReader csv = new CSVReader(file)) {
                LexiconHelper.loadExpressions(lexicon, analyzer, csv, 0, 1, CsvHeader.SKIP);
            }
            Files.writeString(file, "par ailleurs\n", StandardCharsets.UTF_8);
            try (CSVReader csv = new CSVReader(file)) {
                LexiconHelper.loadExpressions(lexicon, analyzer, csv, 0, -1, CsvHeader.KEEP);
            }
            lexicon.freeze();
            check(lexicon, "de fait", "de", "fait");
            check(lexicon, null, "de", "faire");
            check(lexicon, "NY", "New", "York");
            check(lexicon, "par ailleurs", "par", "ailleurs");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Checks that French entry compilation keeps lexical components rather than
     * aggregating numerical expressions, while preserving verbal expansion.
     * @throws IOException if the analyzer resources cannot be read
     */
    private static void french() throws IOException
    {
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(MarkupMode.NONE)) {
            if (!analyzer.addExpression("vingt et un objets")) {
                throw new AssertionError("MWE declaration rejected");
            }
            analyzer.addExpressions(List.of());
            check(analyzer.expressions, "vingt et un objets", "vingt", "et", "un", "objets");
            check(analyzer.expressions, null, "vingt et un", "objets");
            check(analyzer.expressions, "à première vue", "à", "première", "vue");
            check(analyzer.expressions, "prendre conscience", "prend", "conscience");
        }
    }

    /**
     * Checks an exact token sequence accepted by the frozen automaton.
     * @param lexicon frozen expression lexicon
     * @param expected expected canonical form, or null if not accepted
     * @param tokens normalized token sequence
     */
    private static void check(final MweLexicon lexicon, final String expected,
        final String... tokens)
    {
        int state = lexicon.root();
        for (String token : tokens) {
            final char[] chars = token.toCharArray();
            state = lexicon.step(state, chars, chars.length);
            if (state < 0) break;
        }
        final int accepted = state < 0 ? -1 : lexicon.accept(state);
        final String actual = accepted < 0 ? null : lexicon.asString(accepted);
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + "; found " + actual);
        }
    }
}
