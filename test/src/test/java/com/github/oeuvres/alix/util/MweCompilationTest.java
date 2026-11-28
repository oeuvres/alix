package com.github.oeuvres.alix.lucene.analysis.fr;

import static com.github.oeuvres.alix.common.Upos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.MweFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
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
            assertEquals(VERB.ordinal(), analyzer.expressions.pos(
                check(analyzer.expressions, "prendre conscience", "prend", "conscience")));
            assertEquals(ADVasp.ordinal(), analyzer.expressions.pos(
                check(analyzer.expressions, "de temps en temps", "de", "temps", "en", "temps")));
            assertEquals(PROPN.ordinal(), analyzer.expressions.pos(
                check(analyzer.expressions, "New York", "new", "york")));
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
     * Checks that both MWE loaders reconcile aliases of the same canonical form.
     * @param dir JUnit-managed temporary directory
     * @throws IOException if a resource cannot be read or written
     */
    @Test
    public void posConflicts(@TempDir final Path dir) throws IOException
    {
        final String declarations = "form,pos,canonical\n"
            + "bureau international,PROPN,BIE\n"
            + "bureau international d'éducation,PROPNorg,BIE\n"
            + "bureau de Genève,PROPN,BIE\n"
            + "ancienne catégorie,NOUN,exemple conflictuel\n"
            + "nouvelle catégorie,ADJ,exemple conflictuel\n"
            + "espèce biologique,NOUNbio,espèce biologique\n"
            + "nom composé,NAMEorg,nom composé\n";
        final MweLexicon lexicon = new MweLexicon(8);
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
                CSVReader csv = new CSVReader(new StringReader(declarations))) {
            LexiconHelper.loadExpressions(lexicon, analyzer, csv, 0, 1, 2, CsvHeader.SKIP);
        }
        lexicon.freeze();
        assertEquals(PROPNorg.ordinal(), lexicon.pos(
            check(lexicon, "BIE", "bureau", "international")));
        assertEquals(PROPNorg.ordinal(), lexicon.pos(
            check(lexicon, "BIE", "bureau", "de", "Genève")));
        assertEquals(ADJ.ordinal(), lexicon.pos(
            check(lexicon, "exemple conflictuel", "ancienne", "catégorie")));
        assertEquals(NOUN.ordinal(), lexicon.pos(
            check(lexicon, "espèce biologique", "espèce", "biologique")));
        assertEquals(TOKEN.ordinal(), lexicon.pos(
            check(lexicon, "nom composé", "nom", "composé")));

        final Path file = dir.resolve("expressions.csv");
        Files.writeString(file, declarations, StandardCharsets.UTF_8);
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(MarkupMode.NONE)) {
            analyzer.addExpressions(List.of(file));
            assertEquals(PROPNorg.ordinal(), analyzer.expressions.pos(
                check(analyzer.expressions, "BIE", "bureau", "international")));
            assertEquals(PROPNorg.ordinal(), analyzer.expressions.pos(
                check(analyzer.expressions, "BIE", "bureau", "de", "Genève")));
            assertEquals(ADJ.ordinal(), analyzer.expressions.pos(
                check(analyzer.expressions, "exemple conflictuel", "ancienne", "catégorie")));
        }
    }

    /**
     * Checks that known aliases are silent and unsupported extensions warn.
     */
    @Test
    public void posResolution()
    {
        final List<String> warnings = new ArrayList<>();
        assertEquals(ADP_DET, LexiconHelper.resolveMwePos("ADP+DET", warnings::add));
        assertEquals(ADP_PRON, LexiconHelper.resolveMwePos("ADP+PRON", warnings::add));
        assertEquals(ADV, LexiconHelper.resolveMwePos("ADVconj", warnings::add));
        assertEquals(PROPNorg, LexiconHelper.resolveMwePos("PROPNorg", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(NOUN, LexiconHelper.resolveMwePos("NOUNbio", warnings::add));
        assertEquals(TOKEN, LexiconHelper.resolveMwePos("NAMEorg", warnings::add));
        assertEquals(List.of("unsupported MWE POS NOUNbio -> NOUN",
            "unsupported MWE POS NAMEorg -> TOKEN"), warnings);
    }

    /**
     * Checks the generic POS column and verifies that MweFilter replaces the
     * first component's POS, including when a declaration has no tag.
     * @throws IOException if reading or tokenization fails
     */
    @Test
    public void propagation() throws IOException
    {
        final MweLexicon lexicon = new MweLexicon(8);
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
                CSVReader csv = new CSVReader(new StringReader(
                    "form,pos,canonical\n25 objets,,\nde temps en temps,ADVasp,\n"))) {
            LexiconHelper.loadExpressions(lexicon, analyzer, csv, 0, 1, 2, CsvHeader.SKIP);
        }
        final MarkupTokenizer tokenizer = new MarkupTokenizer();
        tokenizer.setReader(new StringReader("25 objets de temps en temps 12 enfants"));
        try (TokenStream stream = new MweFilter(tokenizer, lexicon)) {
            final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            final PosAttribute pos = stream.addAttribute(PosAttribute.class);
            final TypeAttribute type = stream.addAttribute(TypeAttribute.class);
            stream.reset();
            assertTrue(stream.incrementToken());
            assertEquals("25 objets", term.toString());
            assertEquals(TOKEN.ordinal(), pos.getPos());
            assertEquals(MweFilter.TYPE_MWE, type.type());
            assertTrue(stream.incrementToken());
            assertEquals("de temps en temps", term.toString());
            assertEquals(ADVasp.ordinal(), pos.getPos());
            assertEquals(MweFilter.TYPE_MWE, type.type());
            assertTrue(stream.incrementToken());
            assertEquals("12", term.toString());
            assertEquals(NUM.ordinal(), pos.getPos());
            assertTrue(stream.incrementToken());
            assertEquals("enfants", term.toString());
            assertEquals(TOKEN.ordinal(), pos.getPos());
            assertFalse(stream.incrementToken());
            stream.end();
        }
    }

    /**
     * Checks an exact token sequence against the frozen MWE automaton.
     *
     * @param lexicon frozen expression lexicon
     * @param expected expected canonical form, or null if not accepted
     * @param tokens normalized token sequence
     * @return canonical-form ordinal, or -1 when not accepted
     */
    private static int check(final MweLexicon lexicon, final String expected,
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
        return accepted;
    }
}
