package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LemmaLexicon}.
 *
 * <p>The suite uses English examples only to keep expectations readable. POS ids
 * are local test constants and do not depend on a project POS enum.</p>
 */
public class LemmaLexiconTest {
    /** Test adjective POS id. */
    private static final int ADJ = 3;

    /** Test noun POS id. */
    private static final int NOUN = 1;

    /** Test verb POS id. */
    private static final int VERB = 2;

    /**
     * Verifies copying interned forms through the generic {@code char[]} API.
     */
    @Test
    public void copyCopiesInternedForm() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        final int lemmaId = lex.put("children", "child");
        final char[] dst = new char[16];

        final int copied = lex.copy(lemmaId, dst, 3);

        assertEquals(5, copied);
        assertEquals("child", new String(dst, 3, copied));
    }

    /**
     * Verifies that duplicate policy {@link LemmaLexicon.OnDuplicate#ERROR}
     * accepts identical mappings and rejects conflicting mappings.
     */
    @Test
    public void duplicatePolicyErrorRejectsConflicts() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.onDuplicate(LemmaLexicon.OnDuplicate.ERROR);

        assertDoesNotThrow(() -> lex.put("children", NOUN, "child"));
        assertDoesNotThrow(() -> lex.put("children", NOUN, "child"));

        final IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> lex.put("children", NOUN, "children"));

        assertTrue(ex.getMessage().contains("Duplicate"));
    }

    /**
     * Verifies that duplicate policy {@link LemmaLexicon.OnDuplicate#IGNORE}
     * keeps the first mapping.
     */
    @Test
    public void duplicatePolicyIgnoreKeepsFirstMapping() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.onDuplicate(LemmaLexicon.OnDuplicate.IGNORE);
        lex.put("axes", NOUN, "axe");
        lex.put("axes", NOUN, "axis");

        assertEquals("axe", lex.asString(lex.lemmaId("axes", NOUN)));
    }

    /**
     * Verifies that duplicate policy {@link LemmaLexicon.OnDuplicate#REPLACE}
     * overwrites the previous mapping.
     */
    @Test
    public void duplicatePolicyReplaceOverwritesMapping() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.onDuplicate(LemmaLexicon.OnDuplicate.IGNORE);
        lex.put("axes", NOUN, "axe");

        lex.onDuplicate(LemmaLexicon.OnDuplicate.REPLACE);
        lex.put("axes", NOUN, "axis");

        assertEquals("axis", lex.asString(lex.lemmaId("axes", NOUN)));
    }

    /**
     * Verifies id lookup for full forms and slices.
     */
    @Test
    public void idFindsExistingFormsOnly() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("running", VERB, "run");
        lex.put("children", NOUN, "child");

        final char[] running = "__running__".toCharArray();
        final String children = "xxchildrenyy";

        assertTrue(lex.ord("running") >= 0);
        assertEquals(lex.ord("running"), lex.ord(running, 2, 7));
        assertEquals(lex.ord("children"), lex.ord(children, 2, 8));
        assertEquals(-1, lex.ord("unknown"));
    }

    /**
     * Verifies that {@link LemmaLexicon#ANY_POS} is the POS-agnostic mapping
     * key.
     */
    @Test
    public void lemmaIdAnyPosUsesPosAgnosticMapping() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("went", "go");

        final int lemmaId1 = lex.lemmaId("went");
        final int lemmaId2 = lex.lemmaId("went", LemmaLexicon.ANY_POS);

        assertTrue(lemmaId1 >= 0);
        assertEquals(lemmaId1, lemmaId2);
        assertEquals("go", lex.asString(lemmaId1));
    }

    /**
     * Verifies lemma lookup from a character-array slice.
     */
    @Test
    public void lemmaIdFromCharArraySlice() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("went", "go");

        final char[] form = "__went__".toCharArray();
        final int lemmaId = lex.lemmaId(form, 2, 4);

        assertTrue(lemmaId >= 0);
        assertEquals("go", lex.asString(lemmaId));
    }

    /**
     * Verifies lemma lookup from a character-sequence slice.
     */
    @Test
    public void lemmaIdFromCharSequenceSlice() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("mice", "mouse");

        final String form = "xxmiceyy";
        final int lemmaId = lex.lemmaId(form, 2, 4);

        assertTrue(lemmaId >= 0);
        assertEquals("mouse", lex.asString(lemmaId));
    }

    /**
     * Verifies that POS-specific lookup is strict and does not fallback
     * automatically to {@link LemmaLexicon#ANY_POS}.
     */
    @Test
    public void lemmaIdIsStrictNoImplicitFallback() {
        final LemmaLexicon lex = new LemmaLexicon(32);

        lex.put("saw", "see");
        lex.put("saw", VERB, "see");
        lex.put("saw", NOUN, "saw");

        assertEquals("see", lex.asString(lex.lemmaId("saw")));
        assertEquals("see", lex.asString(lex.lemmaId("saw", VERB)));
        assertEquals("saw", lex.asString(lex.lemmaId("saw", NOUN)));
        assertEquals(-1, lex.lemmaId("saw", ADJ));
    }

    /**
     * Verifies lookup from an already resolved form id.
     */
    @Test
    public void lemmaIdUsesExistingFormId() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("teeth", NOUN, "tooth");

        final int formId = lex.ord("teeth");
        final int lemmaId = lex.lemmaId(formId, NOUN);

        assertTrue(formId >= 0);
        assertTrue(lemmaId >= 0);
        assertEquals("tooth", lex.asString(lemmaId));
    }

    /**
     * Verifies length, size, and maximum length accessors.
     */
    @Test
    public void lengthSizeAndMaxLengthReflectStoredForms() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        final int childId = lex.put("children", "child");
        final int mouseId = lex.put("mice", "mouse");

        assertEquals(5, lex.length(childId));
        assertEquals(5, lex.length(mouseId));
        assertTrue(lex.size() >= 4);
        assertTrue(lex.maxLength() >= 8);
    }

    /**
     * Verifies insertion from character arrays.
     */
    @Test
    public void putAcceptsCharArraySlices() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        final char[] form = "__geese__".toCharArray();
        final char[] lemma = "--goose--".toCharArray();

        final int lemmaId = lex.put(form, 2, 5, NOUN, lemma, 2, 5);

        assertTrue(lemmaId >= 0);
        assertEquals("goose", lex.asString(lemmaId));
        assertEquals("goose", lex.asString(lex.lemmaId("geese", NOUN)));
    }

    /**
     * Verifies insertion from already assigned ids.
     */
    @Test
    public void putByIdsAcceptsAssignedIds() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("geese", NOUN, "goose");

        final int formId = lex.ord("geese");
        final int lemmaId = lex.ord("goose");

        assertDoesNotThrow(() -> lex.put(formId, VERB, lemmaId, LemmaLexicon.OnDuplicate.ERROR));
        assertEquals("goose", lex.asString(lex.lemmaId("geese", VERB)));
    }

    /**
     * Verifies that insertion from ids rejects unknown ids.
     */
    @Test
    public void putByIdsRejectsUnknownIds() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        lex.put("geese", NOUN, "goose");

        final int formId = lex.ord("geese");
        final int lemmaId = lex.ord("goose");

        final IllegalArgumentException ex1 = assertThrows(
                IllegalArgumentException.class,
                () -> lex.put(-1, VERB, lemmaId, LemmaLexicon.OnDuplicate.REPLACE));

        final IllegalArgumentException ex2 = assertThrows(
                IllegalArgumentException.class,
                () -> lex.put(formId, VERB, 999_999, LemmaLexicon.OnDuplicate.REPLACE));

        assertTrue(ex1.getMessage().contains("formId"));
        assertTrue(ex2.getMessage().contains("lemmaId"));
    }

    /**
     * Verifies that POS-agnostic mappings work for basic irregular forms.
     */
    @Test
    public void putCreatesPosAgnosticMappings() {
        final LemmaLexicon lex = new LemmaLexicon(32);

        lex.put("children", "child");
        lex.put("mice", "mouse");
        lex.put("went", "go");

        assertEquals("child", lex.asString(lex.lemmaId("children")));
        assertEquals("mouse", lex.asString(lex.lemmaId("mice")));
        assertEquals("go", lex.asString(lex.lemmaId("went")));
        assertEquals(-1, lex.lemmaId("aardvarks"));
    }

    /**
     * Verifies that POS-specific homographs can map to different lemmas.
     */
    @Test
    public void putCreatesPosSpecificHomographs() {
        final LemmaLexicon lex = new LemmaLexicon(32);

        lex.put("left", VERB, "leave");
        lex.put("left", ADJ, "left");
        lex.put("saw", VERB, "see");
        lex.put("saw", NOUN, "saw");

        assertEquals("leave", lex.asString(lex.lemmaId("left", VERB)));
        assertEquals("left", lex.asString(lex.lemmaId("left", ADJ)));
        assertEquals("see", lex.asString(lex.lemmaId("saw", VERB)));
        assertEquals("saw", lex.asString(lex.lemmaId("saw", NOUN)));
    }

    /**
     * Verifies string extraction for ids returned by {@link LemmaLexicon#put}.
     */
    @Test
    public void stringReturnsInternedForm() {
        final LemmaLexicon lex = new LemmaLexicon(16);

        final int lemmaId = lex.put("running", VERB, "run");

        assertEquals("run", lex.asString(lemmaId));
    }

    /**
     * Verifies that {@link LemmaLexicon#trimToSize()} preserves lookups.
     */
    @Test
    public void trimToSizePreservesLookups() {
        final LemmaLexicon lex = new LemmaLexicon(32);

        lex.put("teeth", "tooth");
        lex.put("saw", VERB, "see");
        lex.put("saw", NOUN, "saw");

        final int teethLemma = lex.lemmaId("teeth");
        final int sawVerbLemma = lex.lemmaId("saw", VERB);
        final int sawNounLemma = lex.lemmaId("saw", NOUN);

        lex.trimToSize();

        assertEquals(teethLemma, lex.lemmaId("teeth"));
        assertEquals(sawVerbLemma, lex.lemmaId("saw", VERB));
        assertEquals(sawNounLemma, lex.lemmaId("saw", NOUN));
        assertEquals("tooth", lex.asString(teethLemma));
        assertEquals("see", lex.asString(sawVerbLemma));
        assertEquals("saw", lex.asString(sawNounLemma));
    }
}