package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.util.MweLexicon;

/**
 * Standalone regression test for the French verbal MWE compiler and runtime
 * analyzer. Run with the normal Alix dependencies and French resources.
 */
public final class VerbalMweTest
{
    /** Prevents instantiation of the test utility. */
    private VerbalMweTest() {}

    /**
     * Tests default and corpus-specific compilation, explicit-key precedence,
     * and the Word2Vec token stream.
     *
     * @param args unused command-line arguments
     * @throws IOException if a resource or token stream cannot be read
     */
    public static void main(final String[] args) throws IOException
    {
        final Path custom = Files.createTempFile("alix-mwe-test-", ".csv");
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(MarkupMode.NONE)) {
            analyzer.addExpression("de fait");
            analyzer.addExpression("de mise");
            // A new verb loaded after a default explicit nominal expression
            // must not erase that expression. A later explicit declaration
            // must also override an earlier generated key.
            Files.writeString(custom, """
                GRAPH,TAG,NORM,freq
                mettre en place,VERB,mettre en place,
                prendre en charge,VERB,prendre en charge,
                prise en charge,NOUN,prise en charge,
                """, StandardCharsets.UTF_8);
            analyzer.addExpressions(List.of(custom));
            final MweLexicon lexicon = analyzer.expressions;
            check(lookup(lexicon, "prend conscience"), "prendre conscience");
            check(lookup(lexicon, "prenaient conscience"), "prendre conscience");
            check(lookup(lexicon, "pris conscience"), "prendre conscience");
            check(lookup(lexicon, "prise de conscience"), "prise de conscience");
            check(lookup(lexicon, "feront savoir"), "faire savoir");
            check(lookup(lexicon, "fait savait"), null);
            check(lookup(lexicon, "se rend compte"), "se rendre compte");
            check(lookup(lexicon, "mise en place"), "mise en place");
            check(lookup(lexicon, "prise en charge"), "prise en charge");
            check(lookup(lexicon, "pris en charge"), "prendre en charge");
            check(lookup(lexicon, "de fait"), "de fait");
            check(lookup(lexicon, "de faire"), null);
            check(lookup(lexicon, "de mise"), "de mise");
            check(lookup(lexicon, "de mettre"), null);
            requireToken(analyzer, "Il prend conscience de son erreur.", "prendre conscience");
            requireToken(analyzer, "La prise de conscience est progressive.", "prise de conscience");
            requireToken(analyzer, "Il se rend compte de son erreur.", "se rendre compte");
            System.out.println("Verbal MWE regression tests passed.");
        } finally {
            Files.deleteIfExists(custom);
        }
    }

    /**
     * Checks an expected test result.
     *
     * @param actual returned value
     * @param expected expected value
     */
    private static void check(final String actual, final String expected)
    {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("Expected: " + expected + "; actual: " + actual);
        }
    }

    /**
     * Looks up a complete token sequence in the frozen MWE automaton.
     *
     * @param lexicon frozen lexicon
     * @param expression space-separated normalized components
     * @return canonical form, or null if not accepted
     */
    private static String lookup(final MweLexicon lexicon, final String expression)
    {
        int state = lexicon.root();
        for (String form : expression.split(" ")) {
            final char[] chars = form.toCharArray();
            state = lexicon.step(state, chars, chars.length);
            if (state < 0) {
                return null;
            }
        }
        final int ord = lexicon.accept(state);
        return ord < 0 ? null : lexicon.asString(ord);
    }

    /**
     * Checks that the Word2Vec analyzer emits the requested compounded token.
     *
     * @param analyzer French analyzer with a frozen expression dictionary
     * @param sentence test sentence
     * @param expected expected MWE token, including internal spaces
     * @throws IOException if the stream fails
     */
    private static void requireToken(final FrenchAnalyzer analyzer,
        final String sentence, final String expected) throws IOException
    {
        final List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("word2vec", sentence)) {
            final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        }
        if (!terms.contains(expected)) {
            throw new AssertionError("Missing " + expected + " in " + terms);
        }
    }
}
