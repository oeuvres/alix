package com.github.oeuvres.alix.fr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.hunspell.DictEntries;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.analysis.hunspell.HunspellStemFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;

/**
 * Compatibility tests for Lucene Hunspell apostrophe conversion and multiword entries.
 *
 * <p>The tests use small in-memory dictionaries so they document Lucene behavior without depending
 * on a production dictionary. They target Lucene 10.4.0.
 */
class LuceneHunspellTest {

    private static final String ASCII_CANONICAL_AFFIX = """
            SET UTF-8
            ICONV 2
            ICONV ’ '
            ICONV ʼ '
            OCONV 1
            OCONV ' ’
            """;

    private static final String BASIC_AFFIX = """
            SET UTF-8
            """;

    private static final String RIGHT_EDGE_PLURAL_AFFIX = """
            SET UTF-8
            SFX S Y 1
            SFX S 0 s .
            """;

    private static final String UNICODE_CANONICAL_AFFIX = """
            SET UTF-8
            ICONV 2
            ICONV ' ’
            ICONV ʼ ’
            """;

    /**
     * Verifies that an ASCII-canonical dictionary accepts all apostrophe variants and returns
     * U+2019 through OCONV when Lucene emits a stem.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void asciiCanonicalDictionaryAcceptsApostropheVariantsAndReturnsFrenchTypography()
            throws Exception {
        Dictionary dictionary = loadDictionary(
                ASCII_CANONICAL_AFFIX,
                "aujourd'hui po:ADV"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        assertTrue(hunspell.spell("aujourd'hui"));
        assertTrue(hunspell.spell("aujourd’hui"));
        assertTrue(hunspell.spell("aujourdʼhui"));

        assertEquals(List.of("aujourd’hui"), hunspell.getRoots("aujourd'hui"));
        assertEquals(List.of("aujourd’hui"), hunspell.getRoots("aujourd’hui"));
        assertEquals(List.of("aujourd’hui"), hunspell.getRoots("aujourdʼhui"));

        assertEquals(
                List.of("aujourd’hui"),
                terms(analyze(new KeywordTokenizer(), dictionary, "aujourd'hui"))
        );
    }

    /**
     * Verifies that Dictionary.lookupEntries performs a raw canonical-key lookup rather than an
     * ICONV-normalized lookup.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded
     */
    @Test
    void dictionaryLookupRequiresCanonicalStorageCharacter() throws Exception {
        Dictionary dictionary = loadDictionary(
                ASCII_CANONICAL_AFFIX,
                "aujourd'hui po:ADV"
        );

        assertNotNull(dictionary.lookupEntries("aujourd'hui"));
        assertNull(dictionary.lookupEntries("aujourd’hui"));
        assertNull(dictionary.lookupEntries("aujourdʼhui"));
    }

    /**
     * Verifies that HunspellStemFilter can analyze an MWE when an upstream tokenizer emits the
     * entire expression as one token.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void keywordTokenizerPreservesMultiwordExpressionForHunspell() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "en fait de po:ADP"
        );

        List<AnalyzedToken> tokens = analyze(
                new KeywordTokenizer(),
                dictionary,
                "en fait de"
        );

        assertEquals(
                List.of(new AnalyzedToken("en fait de", 1)),
                tokens
        );
    }

    /**
     * Documents Lucene's current interpretation of a literal ASCII space inside an st: value.
     *
     * <p>The dictionary parser preserves the full morphological string, but the stem exception
     * reader terminates the st: value at the first ASCII space. Therefore this representation must
     * not be used for a multiword lemma.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void literalSpaceInStemExceptionIsTruncatedByLucene() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "châteaux forts po:NOUN st:château fort is:plural"
        );

        DictEntries entries = dictionary.lookupEntries("châteaux forts");
        assertNotNull(entries);
        assertTrue(entries.get(0).getMorphologicalData().contains("st:château fort"));

        assertEquals(
                List.of("château"),
                terms(analyze(new KeywordTokenizer(), dictionary, "châteaux forts"))
        );
    }

    /**
     * Verifies that the public Hunspell API accepts an MWE when the complete expression is passed
     * directly as one String.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded
     */
    @Test
    void multiwordEntryIsAcceptedByDirectHunspellApi() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "en fait de po:ADP"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        assertNotNull(dictionary.lookupEntries("en fait de"));
        assertNull(dictionary.lookupEntries("en"));
        assertTrue(hunspell.spell("en fait de"));
    }

    /**
     * Verifies that StandardTokenizer splits an MWE before HunspellStemFilter can inspect it.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void standardTokenizerPreventsMultiwordLookup() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "en fait de po:ADP"
        );

        assertEquals(
                List.of("en", "fait", "de"),
                terms(analyze(new StandardTokenizer(), dictionary, "en fait de"))
        );
    }

    /**
     * Verifies that an ordinary suffix rule applies to the right edge of the complete MWE.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void suffixRuleInflectsOnlyRightEdgeOfMultiwordEntry() throws Exception {
        Dictionary dictionary = loadDictionary(
                RIGHT_EDGE_PLURAL_AFFIX,
                "compte rendu/S po:NOUN"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        assertTrue(hunspell.spell("compte rendu"));
        assertTrue(hunspell.spell("compte rendus"));
        assertFalse(hunspell.spell("comptes rendus"));

        assertEquals(
                List.of("compte rendu"),
                terms(analyze(new KeywordTokenizer(), dictionary, "compte rendus"))
        );
    }

    /**
     * Verifies a practical encoding for multiword stem exceptions when the application explicitly
     * decodes underscores after Hunspell stemming.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded or analyzed
     */
    @Test
    void underscoreEncodedStemExceptionCanBeDecodedByApplication() throws Exception {
        Dictionary dictionary = loadDictionary(
                BASIC_AFFIX,
                "châteaux forts po:NOUN st:château_fort is:plural"
        );

        List<String> rawStems = terms(
                analyze(new KeywordTokenizer(), dictionary, "châteaux forts")
        );

        assertEquals(List.of("château_fort"), rawStems);
        assertEquals(
                List.of("château fort"),
                rawStems.stream().map(LuceneHunspellTest::decodeMweStem).toList()
        );
    }

    /**
     * Verifies that a U+2019-canonical dictionary accepts ASCII apostrophe and U+02BC through
     * ICONV without requiring apostrophe OCONV.
     *
     * @throws Exception if the in-memory dictionary cannot be loaded
     */
    @Test
    void unicodeCanonicalDictionaryAcceptsApostropheVariantsWithoutOconv() throws Exception {
        Dictionary dictionary = loadDictionary(
                UNICODE_CANONICAL_AFFIX,
                "aujourd’hui po:ADV"
        );
        Hunspell hunspell = new Hunspell(dictionary);

        assertTrue(hunspell.spell("aujourd'hui"));
        assertTrue(hunspell.spell("aujourd’hui"));
        assertTrue(hunspell.spell("aujourdʼhui"));

        assertEquals(List.of("aujourd’hui"), hunspell.getRoots("aujourd'hui"));
        assertEquals(List.of("aujourd’hui"), hunspell.getRoots("aujourd’hui"));
        assertEquals(List.of("aujourd’hui"), hunspell.getRoots("aujourdʼhui"));

        assertNotNull(dictionary.lookupEntries("aujourd’hui"));
        assertNull(dictionary.lookupEntries("aujourd'hui"));
    }

    /**
     * Runs a tokenizer followed by Lucene's HunspellStemFilter.
     *
     * @param tokenizer tokenizer that determines whether the MWE reaches Hunspell as one token
     * @param dictionary loaded Hunspell dictionary
     * @param text input text
     * @return emitted terms and position increments
     * @throws IOException if token-stream processing fails
     */
    private static List<AnalyzedToken> analyze(
            Tokenizer tokenizer,
            Dictionary dictionary,
            String text
    ) throws IOException {
        tokenizer.setReader(new StringReader(text));

        List<AnalyzedToken> result = new ArrayList<>();

        try (TokenStream stream = new HunspellStemFilter(tokenizer, dictionary)) {
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
     * Replaces the test suite's explicit MWE separator with a normal space.
     *
     * @param stem stem returned by Lucene Hunspell
     * @return decoded multiword lemma
     */
    private static String decodeMweStem(String stem) {
        return stem.replace('_', ' ');
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
                    "junit-hunspell",
                    affixStream,
                    dictionaryStream
            );
        }
    }

    /**
     * Extracts only terms from analyzed tokens.
     *
     * @param tokens analyzed tokens
     * @return terms in emission order
     */
    private static List<String> terms(List<AnalyzedToken> tokens) {
        return tokens.stream().map(AnalyzedToken::term).toList();
    }

    /**
     * Creates a UTF-8 input stream for a test dictionary resource.
     *
     * @param text source text
     * @return UTF-8 byte stream
     */
    private static InputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A token emitted by the test analysis chain.
     *
     * @param term emitted term text
     * @param positionIncrement Lucene position increment
     */
    private record AnalyzedToken(String term, int positionIncrement) {
    }
}
