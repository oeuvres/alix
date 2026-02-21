package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.lucene.analysis.tokenattributes.CharTermAttributeImpl;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 tests for {@link LemmaLexicon}, illustrated with English form -> lemma examples.
 *
 * <p>These tests exercise:
 * <ul>
 *   <li>form interning and form dictionary accessors</li>
 *   <li>POS-agnostic mappings (default POS)</li>
 *   <li>POS-specific homographs and fallback behavior</li>
 *   <li>duplicate policies (IGNORE / REPLACE / ERROR)</li>
 *   <li>Lucene {@code CharTermAttribute} helper methods</li>
 * </ul>
 */
public class LemmaLexiconTest
{
    // Simple test POS ids (project-specific POS enum/ids are not required here)
    private static final int NOUN = 1;
    private static final int VERB = 2;
    private static final int ADJ  = 3;

    @Test
    void internFormAndFormAccessors_workAcrossOverloads()
    {
        final LemmaLexicon lex = new LemmaLexicon(16);

        final int runId1 = lex.internForm("running");
        final int runId2 = lex.internForm("running"); // duplicate, same id expected
        assertEquals(runId1, runId2);

        final char[] buf = "__running__".toCharArray();
        final int runId3 = lex.internForm(buf, 2, 7);
        assertEquals(runId1, runId3);

        final String seq = "xxchildrenyy";
        final int childrenId = lex.internForm(seq, 2, 8);

        assertTrue(lex.containsForm("running"));
        assertTrue(lex.containsForm("children"));
        assertFalse(lex.containsForm("unknown"));

        assertEquals(runId1, lex.findFormId("running"));
        assertEquals(childrenId, lex.findFormId(seq, 2, 8));
        assertEquals(runId1, lex.findFormId(buf, 2, 7));

        assertEquals("running", lex.formAsString(runId1));
        assertEquals(7, lex.formLength(runId1));
        assertTrue(lex.formOffset(runId1) >= 0);
        assertNotNull(lex.formSlab());
        assertTrue(lex.formCount() >= 2);
        assertTrue(lex.maxFormLength() >= 8); // "children"

        final char[] dst = new char[32];
        final int copied = lex.copyForm(childrenId, dst, 5);
        assertEquals(8, copied);
        assertEquals("children", new String(dst, 5, copied));

        final CharTermAttributeImpl termAtt = new CharTermAttributeImpl();
        assertTrue(lex.copyForm(runId1, termAtt));
        assertEquals("running", termAtt.toString());

        assertFalse(lex.copyForm(-1, new CharTermAttributeImpl()));
    }

    @Test
    void posAgnosticEnglishLemmas_lookupFromMultipleInputs()
    {
        final LemmaLexicon lex = new LemmaLexicon(32);

        putDefault(lex, "children", "child");
        putDefault(lex, "mice", "mouse");
        putDefault(lex, "went", "go");

        // String lookup
        final int childrenLemmaId = lex.findLemmaId("children");
        assertTrue(childrenLemmaId >= 0);
        assertEquals("child", lex.formAsString(childrenLemmaId));

        // CharSequence slice lookup
        final String seq = "xxmiceyy";
        final int miceLemmaId = lex.findLemmaId(seq, 2, 4);
        assertTrue(miceLemmaId >= 0);
        assertEquals("mouse", lex.formAsString(miceLemmaId));

        // char[] slice lookup
        final char[] formBuf = "__went__".toCharArray();
        final int wentLemmaId = lex.findLemmaId(formBuf, 2, 4);
        assertTrue(wentLemmaId >= 0);
        assertEquals("go", lex.formAsString(wentLemmaId));

        // Unknown form
        assertEquals(-1, lex.findLemmaId("aardvarks"));
        assertEquals(-1, lex.findLemmaId("aardvarks", NOUN));
    }

    @Test
    void posSpecificHomographs_andFallback()
    {
        final LemmaLexicon lex = new LemmaLexicon(64);

        // Homograph: "saw"
        put(lex, "saw", VERB, "see");
        put(lex, "saw", NOUN, "saw");
        putDefault(lex, "saw", "see"); // fallback if POS is missing/wrong

        // Homograph: "left"
        put(lex, "left", VERB, "leave");
        put(lex, "left", ADJ, "left");
        putDefault(lex, "left", "leave");

        final int sawFormId = lex.findFormId("saw");
        assertTrue(sawFormId >= 0);

        final int sawVerbLemma = lex.findLemmaId(sawFormId, VERB);
        final int sawNounLemma = lex.findLemmaId(sawFormId, NOUN);

        assertEquals("see", lex.formAsString(sawVerbLemma));
        assertEquals("saw", lex.formAsString(sawNounLemma));

        // Missing POS-specific mapping (e.g., ADJ) -> no direct lemma
        assertEquals(-1, lex.findLemmaId("saw", ADJ));

        // But fallback should resolve to default POS mapping
        final int sawFallback = lex.findLemmaIdOrDefaultPos(sawFormId, ADJ);
        assertEquals("see", lex.formAsString(sawFallback));

        // Another homograph check
        assertEquals("leave", lex.formAsString(lex.findLemmaId("left", VERB)));
        assertEquals("left", lex.formAsString(lex.findLemmaId("left", ADJ)));
        assertEquals("leave", lex.formAsString(lex.findLemmaIdOrDefaultPos(lex.findFormId("left"), NOUN)));
    }

    @Test
    void duplicatePolicies_ignore_replace_error()
    {
        final LemmaLexicon lex = new LemmaLexicon(32);

        // IGNORE keeps existing mapping
        put(lex, "axes", NOUN, "axe", LemmaLexicon.OnDuplicate.IGNORE);
        put(lex, "axes", NOUN, "axis", LemmaLexicon.OnDuplicate.IGNORE);
        assertEquals("axe", lex.formAsString(lex.findLemmaId("axes", NOUN)));

        // REPLACE overwrites existing mapping
        put(lex, "axes", NOUN, "axis", LemmaLexicon.OnDuplicate.REPLACE);
        assertEquals("axis", lex.formAsString(lex.findLemmaId("axes", NOUN)));

        // ERROR accepts exact same mapping reinserted
        assertDoesNotThrow(() -> put(lex, "children", NOUN, "child", LemmaLexicon.OnDuplicate.ERROR));
        assertDoesNotThrow(() -> put(lex, "children", NOUN, "child", LemmaLexicon.OnDuplicate.ERROR));

        // ERROR rejects conflicting mapping
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> put(lex, "children", NOUN, "children", LemmaLexicon.OnDuplicate.ERROR)
        );
        assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    void charTermAttributeHelpers_copyAndLookup()
    {
        final LemmaLexicon lex = new LemmaLexicon(32);
        putDefault(lex, "went", "go");
        put(lex, "saw", VERB, "see");
        putDefault(lex, "saw", "see");

        final CharTermAttributeImpl srcFormAtt = new CharTermAttributeImpl();
        final CharTermAttributeImpl dstLemmaAtt = new CharTermAttributeImpl();

        // POS-agnostic lemmaToBuffer
        srcFormAtt.setEmpty().append("went");
        final int wentLemmaId = lex.lemmaToBuffer(srcFormAtt, dstLemmaAtt);
        assertTrue(wentLemmaId >= 0);
        assertEquals("go", dstLemmaAtt.toString());
        assertEquals("go", lex.formAsString(wentLemmaId));

        // POS-specific lemmaToBuffer
        srcFormAtt.setEmpty().append("saw");
        dstLemmaAtt.setEmpty();
        final int sawVerbLemmaId = lex.lemmaToBuffer(srcFormAtt, VERB, dstLemmaAtt);
        assertTrue(sawVerbLemmaId >= 0);
        assertEquals("see", dstLemmaAtt.toString());

        // findFormId(CharTermAttribute)
        srcFormAtt.setEmpty().append("went");
        final int wentFormId = lex.findFormId(srcFormAtt);
        assertTrue(wentFormId >= 0);
        assertEquals("went", lex.formAsString(wentFormId));
    }

    @Test
    void freeze_preservesLookups()
    {
        final LemmaLexicon lex = new LemmaLexicon(32);
        putDefault(lex, "teeth", "tooth");
        put(lex, "saw", VERB, "see");
        put(lex, "saw", NOUN, "saw");

        final int before1 = lex.findLemmaId("teeth");
        final int before2 = lex.findLemmaId("saw", VERB);
        final int before3 = lex.findLemmaId("saw", NOUN);

        lex.trimToSize();

        assertEquals(before1, lex.findLemmaId("teeth"));
        assertEquals(before2, lex.findLemmaId("saw", VERB));
        assertEquals(before3, lex.findLemmaId("saw", NOUN));
        assertEquals("tooth", lex.formAsString(before1));
        assertEquals("see", lex.formAsString(before2));
        assertEquals("saw", lex.formAsString(before3));
    }

    @Test
    void putEntryByIds_validatesAssignedFormIds()
    {
        final LemmaLexicon lex = new LemmaLexicon(16);

        final int formId = lex.internForm("geese");
        final int lemmaId = lex.internForm("goose");

        assertDoesNotThrow(() -> lex.putEntry(formId, NOUN, lemmaId, LemmaLexicon.OnDuplicate.ERROR));

        final IllegalArgumentException ex1 = assertThrows(
            IllegalArgumentException.class,
            () -> lex.putEntry(-1, NOUN, lemmaId, LemmaLexicon.OnDuplicate.REPLACE)
        );
        assertTrue(ex1.getMessage().contains("formId"));

        final IllegalArgumentException ex2 = assertThrows(
            IllegalArgumentException.class,
            () -> lex.putEntry(formId, NOUN, 999_999, LemmaLexicon.OnDuplicate.REPLACE)
        );
        assertTrue(ex2.getMessage().contains("lemmaId"));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static int putDefault(final LemmaLexicon lex, final String form, final String lemma)
    {
        return put(lex, form, LemmaLexicon.DEFAULT_POS_ID, lemma, LemmaLexicon.OnDuplicate.ERROR);
    }

    private static int put(final LemmaLexicon lex, final String form, final int posId, final String lemma)
    {
        return put(lex, form, posId, lemma, LemmaLexicon.OnDuplicate.ERROR);
    }

    private static int put(
        final LemmaLexicon lex,
        final String form,
        final int posId,
        final String lemma,
        final LemmaLexicon.OnDuplicate policy
    ) {
        return lex.putEntry(form, posId, lemma, policy);
    }
}
