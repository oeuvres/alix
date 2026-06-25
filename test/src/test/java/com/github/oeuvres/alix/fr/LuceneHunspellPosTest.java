package com.github.oeuvres.alix.fr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.hunspell.AffixedWord;
import org.apache.lucene.analysis.hunspell.DictEntries;
import org.apache.lucene.analysis.hunspell.DictEntry;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.analysis.hunspell.HunspellStemFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;

/**
 * Tests how Lucene 10.4 preserves and exposes Hunspell {@code po:} morphology.
 *
 * <p>The tests deliberately distinguish {@link Hunspell#analyzeSimpleWord(String)}, which exposes
 * the originating {@link DictEntry}, from {@link HunspellStemFilter}, which emits only stem text
 * and therefore cannot preserve POS metadata.
 */
class LuceneHunspellPosTest {

    private static final String BASIC_AFFIX = """
            SET UTF-8
            """;

    private static final String PLURAL_AFFIX = """
            SET UTF-8
            SFX S Y 1
            SFX S 0 s .
            """;

    /**
     * Verifies that several {@code po:} fields on one entry are retained as separate values.
     *
     * <p>Lucene sorts morphology fields, so the serialized morphology need not preserve source
     * order.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded
     */
    @Test
    void directEntryRetainsEveryPoValue() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "large po:NOUN po:ADJ"
        );

        DictEntries entries = dictionary.lookupEntries("large");
        assertNotNull(entries);
        assertEquals(1, entries.size());

        DictEntry entry = entries.get(0);
        assertEquals("po:ADJ po:NOUN", entry.getMorphologicalData());
        assertEquals(List.of("ADJ", "NOUN"), entry.getMorphologicalValues("po:"));
        assertEquals(List.of(), entry.getMorphologicalValues("is:"));
    }

    /**
     * Verifies that a suffix-generated form retains the {@code po:} value of its source entry.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void generatedFormRetainsSourcePoAndAffix() throws Exception {
        Dictionary dictionary = loadDictionary(
                PLURAL_AFFIX,
                "large/S po:ADJ"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        List<AffixedWord> analyses = hunspell.analyzeSimpleWord("larges");
        assertEquals(1, analyses.size());

        AffixedWord analysis = analyses.get(0);
        assertEquals("larges", analysis.getWord());
        assertEquals("large", analysis.getDictEntry().getStem());
        assertEquals(List.of("ADJ"), analysis.getDictEntry().getMorphologicalValues("po:"));
        assertEquals(List.of(), analysis.getPrefixes());
        assertEquals(
                List.of("S"),
                analysis.getSuffixes().stream().map(AffixedWord.Affix::getFlag).toList()
        );
    }

    /**
     * Verifies that one affix flag on a multi-POS entry applies to every POS value on that entry.
     *
     * <p>This test documents why a line such as {@code moral/W po:NOUN po:ADJ} is unsafe when the
     * noun and adjective do not share the same paradigm: {@code po:} is metadata, not a condition
     * on affix application.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void oneFlagOnCombinedPosEntryAppliesToAllPoValues() throws Exception {
        Dictionary dictionary = loadDictionary(
                PLURAL_AFFIX,
                "large/S po:ADJ po:NOUN"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        List<AffixedWord> analyses = hunspell.analyzeSimpleWord("larges");
        assertEquals(1, analyses.size());
        assertEquals(
                Set.of("ADJ", "NOUN"),
                Set.copyOf(analyses.get(0).getDictEntry().getMorphologicalValues("po:"))
        );
    }

    /**
     * Verifies that an MWE passed as one string retains its {@code po:} morphology.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void multiwordEntryRetainsPoWhenAnalyzedAsOneToken() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "en fait de po:ADP"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        List<AffixedWord> analyses = hunspell.analyzeSimpleWord("en fait de");
        assertEquals(1, analyses.size());
        assertEquals("en fait de", analyses.get(0).getDictEntry().getStem());
        assertEquals(
                List.of("ADP"),
                analyses.get(0).getDictEntry().getMorphologicalValues("po:")
        );
    }

    /**
     * Verifies that two homographic entries remain two POS-specific analyses.
     *
     * <p>The order of {@link Hunspell#analyzeSimpleWord(String)} results is intentionally not
     * asserted because Lucene does not promise dictionary-file order.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void separateHomographsProduceSeparatePoAnalyses() throws Exception {
        Dictionary dictionary = loadDictionary(
                PLURAL_AFFIX,
                "large/S po:ADJ",
                "large/S po:NOUN"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        Set<Set<String>> analyses = hunspell.analyzeSimpleWord("larges").stream()
                .map(AffixedWord::getDictEntry)
                .map(entry -> Set.copyOf(entry.getMorphologicalValues("po:")))
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(Set.of("ADJ"), Set.of("NOUN")),
                analyses
        );
    }

    /**
     * Verifies that {@link HunspellStemFilter} emits only lemma text and deduplicates identical
     * lemmas by default, thereby losing the distinction between POS-specific analyses.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void stemFilterDropsPoAndDeduplicatesEqualLemmasByDefault() throws Exception {
        Dictionary dictionary = loadDictionary(
                PLURAL_AFFIX,
                "large/S po:ADJ",
                "large/S po:NOUN"
        );

        assertEquals(
                List.of(new AnalyzedToken("large", 1)),
                analyze(new KeywordTokenizer(), dictionary, "larges", true)
        );

        assertEquals(
                List.of(
                        new AnalyzedToken("large", 1),
                        new AnalyzedToken("large", 0)
                ),
                analyze(new KeywordTokenizer(), dictionary, "larges", false)
        );
    }

    /**
     * Runs a tokenizer followed by {@link HunspellStemFilter}.
     *
     * @param tokenizer tokenizer receiving the input text
     * @param dictionary loaded Hunspell dictionary
     * @param text input text
     * @param deduplicate whether the filter should deduplicate equal stems
     * @return emitted terms and position increments
     * @throws IOException if token-stream processing fails
     */
    private static List<AnalyzedToken> analyze(
            Tokenizer tokenizer,
            Dictionary dictionary,
            String text,
            boolean deduplicate
    ) throws IOException {
        tokenizer.setReader(new StringReader(text));
        List<AnalyzedToken> result = new ArrayList<>();

        try (TokenStream stream = new HunspellStemFilter(tokenizer, dictionary, deduplicate)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            PositionIncrementAttribute position =
                    stream.addAttribute(PositionIncrementAttribute.class);

            stream.reset();
            while (stream.incrementToken()) {
                result.add(new AnalyzedToken(term.toString(), position.getPositionIncrement()));
            }
            stream.end();
        }

        return result;
    }

    /**
     * Loads an in-memory Lucene Hunspell dictionary.
     *
     * @param affix complete affix-file contents
     * @param entries dictionary body entries without the numeric header
     * @return loaded dictionary
     * @throws IOException if Lucene cannot read or sort the dictionary
     * @throws ParseException if the affix or dictionary syntax is invalid
     */
    private static Dictionary loadDictionary(
            String affix,
            String... entries
    ) throws IOException, ParseException {
        String dictionaryText = entries.length
                + "\n"
                + String.join("\n", Arrays.asList(entries))
                + "\n";

        try (
                Directory temporaryDirectory = new ByteBuffersDirectory();
                InputStream affixStream = utf8(affix);
                InputStream dictionaryStream = utf8(dictionaryText)
        ) {
            return new Dictionary(
                    temporaryDirectory,
                    "junit-hunspell-pos",
                    affixStream,
                    dictionaryStream
            );
        }
    }

    /**
     * Creates a UTF-8 input stream.
     *
     * @param text source text
     * @return UTF-8 byte stream
     */
    private static InputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Token emitted by the test analysis chain.
     *
     * @param term emitted term text
     * @param positionIncrement Lucene position increment
     */
    private record AnalyzedToken(String term, int positionIncrement) {
    }
}
