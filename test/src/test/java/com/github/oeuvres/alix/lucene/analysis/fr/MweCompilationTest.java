package com.github.oeuvres.alix.lucene.analysis.fr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.CsvHeader;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.MweLexicon;

/** Tests generic MWE loading and French lexical and verbal compilation. */
public class MweCompilationTest
{
    /**
     * Checks that French entry compilation retains lexical number components
     * and still expands verbal heads.
     *
     * @throws IOException if analyzer resources cannot be read
     */
    @Test
    public void french() throws IOException
    {
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(MarkupMode.NONE)) {
            assertTrue(analyzer.addExpression("vingt et un objets"),
                "MWE declaration rejected");
            analyzer.addExpressions(List.of());
            check(analyzer.expressions, "vingt et un objets", "vingt", "et", "un", "objets");
            check(analyzer.expressions, null, "vingt et un", "objets");
            check(analyzer.expressions, "à première vue", "à", "première", "vue");
            check(analyzer.expressions, "prendre conscience", "prend", "conscience");
        }
    }

    /**
     * Checks generic one- and two-column expression loading without
     * French resources or unconditional lemma aliases.
     *
     * @param dir JUnit-managed temporary directory
     * @throws IOException if the CSV cannot be written or read
     */
    @Test
    public void generic(@TempDir final Path dir) throws IOException
    {
        final Path file = dir.resolve("expressions.csv");
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
        }
    }

    /**
     * Checks an exact token sequence against the frozen MWE automaton.
     *
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
        assertEquals(expected, actual, () -> "MWE key: " + Arrays.toString(tokens));
    }
}
