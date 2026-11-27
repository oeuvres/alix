package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.util.List;

import com.github.oeuvres.alix.lucene.analysis.PosTagger;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;

import opennlp.tools.util.Sequence;

/**
 * Compares POS tagging of compact and virtually decomposed age expressions
 * in attested sentences from Piaget's works. Inputs are pretokenized;
 * neither Lucene filters nor forced POS constraints are applied.
 */
public final class PosAgeDemo
{
    /** Prevents instantiation. */
    private PosAgeDemo() {}

    /**
     * Prints each input sentence vertically as token,POS pairs.
     * @param args unused command-line arguments
     * @throws IOException if the French lexical resources cannot be loaded
     */
    public static void main(final String[] args) throws IOException
    {
        final PosTagger tagger = new PosTagger(
            LexiconHelper.loadPosModel(FrenchLexicons.class,
                "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin"),
            FrenchLexicons.buildTagDictionary());

        // La naissance de l'intelligence chez l'enfant (1948), XML line 1241.
        compare(tagger,
            "A 0ans1mois3jours Laurent tire à nouveau sa langue plusieurs fois de suite .",
            "A 0 ans 1 mois 3 jours Laurent tire à nouveau sa langue plusieurs fois de suite .");

        // Same work, XML line 1263 (observation 17).
        compare(tagger,
            "A 0ans1mois2jours Laurent crie de faim dans son berceau .",
            "A 0 ans 1 mois 2 jours Laurent crie de faim dans son berceau .");

        // Mémoire et intelligence (1968), XML line 1433.
        compare(tagger,
            "La technique I a été appliquée à 62 sujets de 3ans6mois-6ans6mois .",
            "La technique I a été appliquée à 62 sujets de 3 ans 6 mois à 6 ans 6 mois .");

        // Same work, XML line 1465.
        compare(tagger,
            "Mais cela n' est courant qu' à 3ans et dès 4ans on trouve des sujets dont le dessin de mémoire est de niveau I et le dessin de copie de niveaux III-IV ( corrects ) .",
            "Mais cela n' est courant qu' à 3 ans et dès 4 ans on trouve des sujets dont le dessin de mémoire est de niveau I et le dessin de copie de niveaux III-IV ( corrects ) .");

        // Same work, XML line 1535.
        compare(tagger,
            "Après 8mois , il y a une série correcte mais de 3 éléments ( II E 1 ) .",
            "Après 8 mois , il y a une série correcte mais de 3 éléments ( II E 1 ) .");
    }

    /**
     * Tags and prints the compact and decomposed representations.
     * @param tagger French POS decoder
     * @param compact sentence with canonical age tokens
     * @param expanded sentence with virtual number and unit tokens
     */
    private static void compare(final PosTagger tagger, final String compact,
        final String expanded)
    {
        System.out.println("# Compact");
        print(tagger, compact);
        System.out.println("# Decomposed");
        print(tagger, expanded);
        System.out.println();
    }

    /**
     * Tags a space-separated token sequence and prints its POS assignments.
     * @param tagger French POS decoder
     * @param sentence space-separated tokens
     */
    private static void print(final PosTagger tagger, final String sentence)
    {
        final String[] tokens = sentence.split(" ");
        final Sequence sequence = tagger.tag(tokens);
        if (sequence == null) {
            System.out.println("# No valid POS sequence: " + sentence);
            return;
        }
        final List<String> tags = sequence.getOutcomes();
        for (int i = 0; i < tokens.length; i++) {
            final String token = tokens[i].equals(",") ? "\",\"" : tokens[i];
            System.out.println(token + "," + tags.get(i));
        }
    }
}
