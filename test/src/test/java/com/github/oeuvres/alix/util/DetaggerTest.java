package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Detagger}.
 *
 * <p>Each test method covers one documented behaviour. A fresh {@link StringBuilder}
 * is used as the {@link java.lang.Appendable} destination so that output can be read
 * back with {@link StringBuilder#toString()}.</p>
 */
class DetaggerTest {

    private StringBuilder out;

    @BeforeEach
    void setUp() {
        out = new StringBuilder();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String detag(Detagger d, String xml) throws Exception {
        StringBuilder sb = new StringBuilder();
        d.detag(sb, xml, 0, xml.length());
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Plain text
    // -------------------------------------------------------------------------

    @Test
    void plainTextPassesThrough() throws Exception {
        assertEquals("hello world", detag(new Detagger(), "hello world"));
    }

    @Test
    void emptyStringProducesNothing() throws Exception {
        assertEquals("", detag(new Detagger(), ""));
    }

    // -------------------------------------------------------------------------
    // Whitespace normalisation
    // -------------------------------------------------------------------------

    @Test
    void multipleSpacesCollapsedToOne() throws Exception {
        assertEquals("a b", detag(new Detagger(), "a   b"));
    }

    @Test
    void tabCollapsedToSpace() throws Exception {
        assertEquals("a b", detag(new Detagger(), "a\tb"));
    }

    @Test
    void crlfCollapsedToSpace() throws Exception {
        assertEquals("a b", detag(new Detagger(), "a\r\nb"));
    }

    @Test
    void whitespaceBetweenTagsProducesOneSpace() throws Exception {
        // <p>foo</p> <p>bar</p> — the space between the tags should survive as one space
        assertEquals("foo bar", detag(new Detagger(), "<p>foo</p> \n\t <p>bar</p>"));
    }

    @Test
    void openAndCloseTagStripped() throws Exception {
        assertEquals("text", detag(new Detagger(), "<p>text</p>"));
    }

    @Test
    void nestedTagsStripped() throws Exception {
        assertEquals("bold italic", detag(new Detagger(), "<p><b>bold</b> <i>italic</i></p>"));
    }

    @Test
    void selfClosingTagStripped() throws Exception {
        assertEquals("before after", detag(new Detagger(), "before<br/> after"));
    }

    @Test
    void attributesStripped() throws Exception {
        assertEquals("text", detag(new Detagger(), "<p class=\"lead\" id=\"p1\">text</p>"));
    }

    // -------------------------------------------------------------------------
    // Include set — preserving named tags
    // -------------------------------------------------------------------------

    @Test
    void includedOpenTagPreserved() throws Exception {
        assertEquals("<em>text</em>", detag(new Detagger("em"), "<em>text</em>"));
    }

    @Test
    void includedTagPreservedNonIncludedStripped() throws Exception {
        // <p> stripped, <em> preserved
        assertEquals("<em>text</em>", detag(new Detagger("em"), "<p><em>text</em></p>"));
    }

    @Test
    void includedSelfClosingTagPreserved() throws Exception {
        assertEquals("before<br/>after", detag(new Detagger("br"), "before<br/>after"));
    }

    @Test
    void includeIsCaseSensitive() throws Exception {
        // include "em" does NOT match <EM>
        assertEquals("text", detag(new Detagger("em"), "<EM>text</EM>"));
    }

    @Test
    void tagWithAttributesPreservedWhenIncluded() throws Exception {
        // the full tag markup, including attributes, is kept
        assertEquals("<em class=\"x\">text</em>", detag(new Detagger("em"), "<em class=\"x\">text</em>"));
    }

    // -------------------------------------------------------------------------
    // Namespace prefix
    // -------------------------------------------------------------------------

    /*
    @Test
    void namespacePrefixStripped_localNameMatchedForInclude() throws Exception {
        // <tei:lb/> has local name "lb"; the include set uses "lb"
        assertEquals("<tei:lb/>", detag(new Detagger("lb"), "before<tei:lb/>after"));
    }
    */

    @Test
    void namespacePrefixStripped_localNameNotIncluded() throws Exception {
        assertEquals("beforeafter", detag(new Detagger("p"), "before<tei:lb/>after"));
    }

    // -------------------------------------------------------------------------
    // Broken excerpts
    // -------------------------------------------------------------------------

    @Test
    void brokenStart_strayCloseAngle_skippedTextKept() throws Exception {
        // The slice starts with " attr>", which looks like the tail of a tag.
        // The stray '>' is skipped; the text after it is kept.
        assertEquals("text", detag(new Detagger(), " attr>text"));
    }

    @Test
    void brokenEnd_sliceEndsInsideTag() throws Exception {
        // The slice ends before the closing '>' of a tag; the unclosed tag is silently dropped.
        assertEquals("text", detag(new Detagger(), "text<unterminated"));
    }

    @Test
    void brokenEnd_onlyOpenAngle() throws Exception {
        // Nothing after '<'; the incomplete tag disappears.
        assertEquals("text", detag(new Detagger(), "text<"));
    }

    // -------------------------------------------------------------------------
    // Processing instructions and comments
    // -------------------------------------------------------------------------

    @Test
    void processingInstructionStripped() throws Exception {
        assertEquals("text", detag(new Detagger(), "<?xml version=\"1.0\"?>text"));
    }

    @Test
    void commentStripped() throws Exception {
        assertEquals("text", detag(new Detagger(), "<!-- a comment -->text"));
    }

    @Test
    void doctypeStripped() throws Exception {
        assertEquals("text", detag(new Detagger(), "<!DOCTYPE html>text"));
    }

    // -------------------------------------------------------------------------
    // begin / end range
    // -------------------------------------------------------------------------

    @Test
    void subRangeExtracted() throws Exception {
        final String xml = "abc<p>def</p> ghi";
        // offsets 3..13 = "<p>def</p>g"
        final StringBuilder sb = new StringBuilder();
        new Detagger().detag(sb, xml, 3, 15);
        assertEquals("def g", sb.toString());
    }

    @Test
    void negativeBeginClampedToZero() throws Exception {
        final StringBuilder sb = new StringBuilder();
        new Detagger().detag(sb, "text", -5, 4);
        assertEquals("text", sb.toString());
    }

    @Test
    void endBeyondLengthClampedToLength() throws Exception {
        final StringBuilder sb = new StringBuilder();
        new Detagger().detag(sb, "text", 0, 999);
        assertEquals("text", sb.toString());
    }

    @Test
    void emptyRange_nothingWritten() throws Exception {
        final StringBuilder sb = new StringBuilder();
        new Detagger().detag(sb, "text", 2, 2);
        assertEquals("", sb.toString());
    }

    @Test
    void beginAfterEnd_nothingWritten() throws Exception {
        final StringBuilder sb = new StringBuilder();
        new Detagger().detag(sb, "text", 3, 1);
        assertEquals("", sb.toString());
    }

    // -------------------------------------------------------------------------
    // Null safety
    // -------------------------------------------------------------------------

    @Test
    void nullXml_nothingWrittenNoException() throws Exception {
        final StringBuilder sb = new StringBuilder();
        assertDoesNotThrow(() -> new Detagger().detag(sb, null, 0, 0));
        assertEquals("", sb.toString());
    }

    @Test
    void nullDest_noException() {
        assertDoesNotThrow(() -> new Detagger().detag(null, "text", 0, 4));
    }

    // -------------------------------------------------------------------------
    // Buffer reuse across consecutive calls
    // -------------------------------------------------------------------------

    @Test
    void consecutiveCallsOnSameInstance_buffersReset() throws Exception {
        final Detagger d = new Detagger("em");

        final StringBuilder first = new StringBuilder();
        d.detag(first, "<em>one</em>", 0, 12);
        assertEquals("<em>one</em>", first.toString());

        final StringBuilder second = new StringBuilder();
        d.detag(second, "<em>two</em>", 0, 12);
        assertEquals("<em>two</em>", second.toString());
    }

    @Test
    void appendableIsAppendedTo_notReplaced() throws Exception {
        final StringBuilder sb = new StringBuilder("prefix ");
        new Detagger().detag(sb, "<p>text</p>", 0, 11);
        assertEquals("prefix text", sb.toString());
    }
}
