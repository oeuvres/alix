package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests POS metadata on canonical multi-word forms, independently of UPOS. */
public class MweLexiconPosTest
{
    /** Checks that aliases share the canonical ordinal and its declared tag. */
    @Test
    public void aliases()
    {
        final MweLexicon lexicon = new MweLexicon(1);
        lexicon.addExpression(List.of("prend", "conscience"), "prendre conscience");
        lexicon.addExpression(List.of("prendre", "conscience"), "prendre conscience", 7);
        lexicon.addExpression(List.of("pris", "conscience"), "prendre conscience", 7);
        lexicon.addExpression(List.of("prenait", "conscience"), "prendre conscience");
        lexicon.freeze();
        final int ord = accept(lexicon, "prendre", "conscience");
        assertEquals(7, lexicon.pos(ord));
        assertEquals("prendre conscience", lexicon.asString(ord));
        assertEquals(ord, accept(lexicon, "prend", "conscience"));
        assertEquals(ord, accept(lexicon, "pris", "conscience"));
        assertEquals(ord, accept(lexicon, "prenait", "conscience"));
    }

    /** Checks that conflicting tags are rejected without registering the alias. */
    @Test
    public void conflict()
    {
        final MweLexicon lexicon = new MweLexicon(1);
        lexicon.addExpression(List.of("en", "effet"), "en effet", 3);
        assertThrows(IllegalArgumentException.class,
            () -> lexicon.addExpression(List.of("en", "vérité"), "en effet", 4));
        lexicon.freeze();
        assertEquals(3, lexicon.pos(accept(lexicon, "en", "effet")));
        assertEquals(-1, accept(lexicon, "en", "vérité"));
    }

    /** Checks POS array growth and preservation of canonical ordinals after freezing. */
    @Test
    public void growth()
    {
        final MweLexicon lexicon = new MweLexicon(1);
        for (int i = 0; i < 100; i++) {
            lexicon.addExpression(List.of("form", "" + i), "form " + i, i + 1);
        }
        lexicon.freeze();
        lexicon.freeze();
        for (int i = 0; i < 100; i++) {
            final int ord = accept(lexicon, "form", "" + i);
            assertEquals(lexicon.formDic().ord("form " + i), ord);
            assertEquals(i + 1, lexicon.pos(ord));
        }
    }

    /** Checks that ordinary duplicate-key replacement keeps the new canonical tag. */
    @Test
    public void replacement()
    {
        final MweLexicon lexicon = new MweLexicon(1);
        final List<String> key = List.of("a", "b");
        lexicon.addExpression(key, "first form", 3);
        lexicon.addExpression(key, "second form", 4);
        lexicon.freeze();
        final int ord = accept(lexicon, "a", "b");
        assertEquals("second form", lexicon.asString(ord));
        assertEquals(4, lexicon.pos(ord));
    }

    /** Checks that generic expressions have tag zero and retain lifecycle checks. */
    @Test
    public void unspecified()
    {
        final MweLexicon lexicon = new MweLexicon(1);
        lexicon.addExpression(List.of("New", "York"), "New York");
        assertThrows(IllegalArgumentException.class,
            () -> lexicon.addExpression(List.of("a", "b"), "a b", -1));
        assertThrows(IllegalStateException.class, () -> lexicon.pos(0));
        lexicon.freeze();
        assertEquals(0, lexicon.pos(accept(lexicon, "new", "york")));
        assertThrows(IllegalArgumentException.class, () -> lexicon.pos(-1));
        assertThrows(IllegalStateException.class,
            () -> lexicon.addExpression(List.of("a", "b"), "a b", 1));
    }

    /**
     * Walks a frozen automaton to the accepting state for a sequence.
     * @param lexicon frozen expression lexicon
     * @param tokens sequence of component tokens
     * @return canonical-form ordinal, or -1 if not accepted
     */
    private static int accept(final MweLexicon lexicon, final String... tokens)
    {
        int state = lexicon.root();
        for (String token : tokens) {
            final char[] chars = token.toCharArray();
            state = lexicon.step(state, chars, chars.length);
            if (state < 0) return -1;
        }
        return lexicon.accept(state);
    }
}
