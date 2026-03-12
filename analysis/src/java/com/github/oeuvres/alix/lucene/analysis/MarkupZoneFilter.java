/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ElementAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Streaming zone filter for token streams that contain explicit XML/HTML tag tokens.
 *
 * <h2>Input contract</h2>
 * <ul>
 *   <li>Upstream tokenizer emits XML/HTML tags as tokens (e.g. {@code "<div a='b'>"}, {@code "</p>"}).</li>
 *   <li>Tag tokens are identified by {@link PosAttribute#getPos()} == {@code XML.code}.</li>
 *   <li>Literal tag text (including {@code <} and {@code >}) is carried by {@link CharTermAttribute}.</li>
 * </ul>
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li>Maintains a stack of open elements based on tag tokens.</li>
 *   <li>Maintains an “in-zone” boolean: inside at least one open element whose <b>start tag</b> matched {@code matchExpr}.</li>
 *   <li>Filters tokens according to {@link Mode}:
 *     <ul>
 *       <li>{@link Mode#INCLUDE}: keep tokens only when in-zone</li>
 *       <li>{@link Mode#EXCLUDE}: drop tokens when in-zone</li>
 *     </ul>
 *   </li>
 *   <li>Pop on CLOSE is done <b>after</b> keep/drop decision, so the closing tag of a matched zone is not lost.</li>
 * </ul>
 *
 * <h2>{@code matchExpr} syntax (OR only; spaces allowed)</h2>
 * {@code matchExpr} is a {@code |}-separated list of atoms. Spaces/newlines around {@code |} are allowed.
 * Each atom is either:
 * <ul>
 *   <li>An element name (QName allowed; prefix ignored): {@code teiHeader}, {@code tei:note}</li>
 *   <li>An attribute test with quoted value: {@code @data-tei-type='quote'} or {@code @data-tei-type="quote"}</li>
 *   <li>A boolean attribute presence test: {@code @disabled} (matches if the attribute appears, with or without value)</li>
 * </ul>
 *
 * <p><b>Not supported</b>: XPath, AND, predicates, wildcards.</p>
 *
 * <p><b>Delimiter rule</b>: this implementation splits on {@code |} without quote-awareness.
 * Therefore {@code |} must not appear inside quoted values. (Your RNG can enforce that.)</p>
 *
 * <h2>Tag parsing notes</h2>
 * <ul>
 *   <li>PI/comments/doctype/CDATA-like tokens (prefix {@code <?} or {@code <!}) are tagged as OTHER and ignored for the stack.</li>
 *   <li>Self-closing tags {@code <x/>} are recognized (EMPTY) but do not push/pop (preserves your current behaviour).</li>
 * </ul>
 */
public final class MarkupZoneFilter extends TokenFilter
{
    public enum Mode { INCLUDE, EXCLUDE }

    // Split atoms on "|" with optional surrounding whitespace (tabs/newlines included by \s)
    private static final Pattern OR_SPLIT = Pattern.compile("\\s*\\|\\s*");

    // @name                 (boolean attribute presence test)
    // @name = 'value'       (quoted value)
    // @name = "value"
    //
    // KISS constraints:
    // - no whitespace in attribute name
    // - value must be quoted (', ")
    // - empty value allowed: @a='' or @a=""
    //
    // Group 1: qname, Group 2: quote, Group 3: value (optional if boolean form)
    private static final Pattern ATTR_ATOM =
        Pattern.compile("^@([^\\s=]+)(?:\\s*=\\s*(['\"])(.*?)\\2)?$");

    /** Compiled OR-pattern: element local-names and/or attribute tests. */
    private static final class CompiledMatch
    {
        final char[][] elemNames;    // local-names
        final AttrTest[] attrTests;  // local-names for attribute names

        CompiledMatch(char[][] elemNames, AttrTest[] attrTests) {
            this.elemNames = elemNames;
            this.attrTests = attrTests;
        }

        static CompiledMatch compile(final String expr) {
            if (expr == null) throw new NullPointerException("matchExpr is null");
            final String[] atoms = OR_SPLIT.split(expr.trim(), -1);

            final List<char[]> elems = new ArrayList<>();
            final List<AttrTest> attrs = new ArrayList<>();

            for (String raw : atoms) {
                final String s = raw.trim();
                if (s.isEmpty()) continue;

                if (s.charAt(0) == '@') {
                    attrs.add(parseAttrAtom(s));
                }
                else {
                    final String ln = localNameOfQName(s);
                    if (!ln.isEmpty()) elems.add(ln.toCharArray());
                }
            }

            if (elems.isEmpty() && attrs.isEmpty()) {
                throw new IllegalArgumentException("matchExpr has no usable atoms: " + expr);
            }

            return new CompiledMatch(
                elems.toArray(new char[0][]),
                attrs.toArray(new AttrTest[0])
            );
        }

        boolean matchesStartTag(char[] tag, int n, int elLocalStart, int elLocalEnd) {
            // element-name OR attribute-test
            if (elemNames.length != 0 && matchesAnyElemName(tag, elLocalStart, elLocalEnd, elemNames)) return true;
            if (attrTests.length != 0 && matchesAnyAttrTest(tag, n, attrTests)) return true;
            return false;
        }

        private static boolean matchesAnyElemName(char[] s, int start, int end, char[][] names) {
            for (char[] needle : names) {
                if (regionEquals(s, start, end, needle)) return true;
            }
            return false;
        }

        private static AttrTest parseAttrAtom(String atom) {
            final Matcher m = ATTR_ATOM.matcher(atom);
            if (!m.matches()) {
                throw new IllegalArgumentException("Bad attribute atom: " + atom);
            }

            String qname = localNameOfQName(m.group(1));
            final String value = m.group(3); // null => boolean presence test

            if (value == null) {
                return new AttrTest(qname.toCharArray(), null, true);
            }
            return new AttrTest(qname.toCharArray(), value.toCharArray(), false);
        }
    }

    private static final class AttrTest
    {
        final char[] name;          // local-name
        final char[] value;         // exact (no quotes), null if presenceOnly
        final boolean presenceOnly; // true for "@attr"
        AttrTest(char[] name, char[] value, boolean presenceOnly) {
            this.name = name;
            this.value = value;
            this.presenceOnly = presenceOnly;
        }
    }

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final ElementAttribute elemAtt = addAttribute(ElementAttribute.class);

    private final Mode mode;
    private final CompiledMatch match;

    // For each open element: whether that element matched matchExpr.
    private boolean[] matchStack = new boolean[32];
    private int depth = 0;
    private int matchDepth = 0;

    public MarkupZoneFilter(TokenStream input, String matchExpr, Mode mode) {
        super(input);
        if (mode == null) throw new NullPointerException("mode is null");
        this.mode = mode;
        this.match = CompiledMatch.compile(matchExpr);
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        depth = 0;
        matchDepth = 0;
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        while (input.incrementToken())
        {
            final boolean isXml = (posAtt.getPos() == XML.code);

            if (isXml) {
                final char[] buf = termAtt.buffer();
                final int len = termAtt.length();

                // Set elemAtt and push for OPEN (no pop for CLOSE here).
                processTag(buf, len);

                final byte ev = elemAtt.getEvent();

                // For CLOSE tags, in-zone must be tested BEFORE pop().
                final boolean inZone = (matchDepth > 0);
                final boolean keep = (mode == Mode.INCLUDE) ? inZone : !inZone;

                if (ev == ElementAttribute.CLOSE) {
                    pop();
                }

                if (keep) return true;
                continue;
            }

            // Non-XML token: clear elemAtt to avoid stale values downstream.
            elemAtt.setEmpty();
            elemAtt.setEvent(ElementAttribute.NONE);

            final boolean inZone = (matchDepth > 0);
            final boolean keep = (mode == Mode.INCLUDE) ? inZone : !inZone;

            if (keep) return true;
        }
        return false;
    }

    /**
     * Parse a tag token, set {@link ElementAttribute}, and update the match stack for OPEN tags.
     * CLOSE tags do not pop here; pop is performed in {@link #incrementToken()} after keep/drop decision.
     */
    private void processTag(char[] tag, int n)
    {
        elemAtt.setEmpty();
        elemAtt.setEvent(ElementAttribute.OTHER);

        if (n < 3 || tag[0] != '<') return;

        final char c1 = tag[1];
        if (c1 == '?' || c1 == '!') return; // PI/comment/doctype/...

        if (c1 == '/') {
            // End tag: </name>
            int i = 2;
            while (i < n && isSpace(tag[i])) i++;
            final int nameStart = i;

            int localStart = nameStart;
            while (i < n) {
                final char ch = tag[i];
                if (ch == ':') localStart = i + 1;
                if (isSpace(ch) || ch == '>' || ch == '/') break;
                i++;
            }
            final int nameEnd = i;

            if (nameEnd > localStart) elemAtt.copyBuffer(tag, localStart, nameEnd - localStart);
            elemAtt.setEvent(ElementAttribute.CLOSE);
            return;
        }

        // Start tag: <name ...> or <name .../>
        int i = 1;
        while (i < n && isSpace(tag[i])) i++;
        final int nameStart = i;

        int localStart = nameStart;
        while (i < n) {
            final char ch = tag[i];
            if (ch == ':') localStart = i + 1;
            if (isSpace(ch) || ch == '>' || ch == '/') break;
            i++;
        }
        final int nameEnd = i;

        if (nameEnd > localStart) elemAtt.copyBuffer(tag, localStart, nameEnd - localStart);

        final boolean selfClosing = isSelfClosing(tag, n);
        elemAtt.setEvent(selfClosing ? ElementAttribute.EMPTY : ElementAttribute.OPEN);

        final boolean matched = (nameEnd > localStart) && match.matchesStartTag(tag, n, localStart, nameEnd);

        if (!selfClosing) {
            push(matched);
        }
        // selfClosing: no scope (preserve current behaviour).
    }

    private void push(boolean matched)
    {
        if (depth == matchStack.length) {
            final boolean[] ns = new boolean[depth << 1];
            System.arraycopy(matchStack, 0, ns, 0, depth);
            matchStack = ns;
        }
        matchStack[depth++] = matched;
        if (matched) matchDepth++;
    }

    private void pop()
    {
        if (depth == 0) return;
        final boolean matched = matchStack[--depth];
        if (matched) matchDepth--;
    }

    // -------------------------
    // Attribute scanning / helpers
    // -------------------------

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** Detect "/>" ignoring whitespace before '>' */
    private static boolean isSelfClosing(char[] tag, int n)
    {
        int i = n - 2; // char before last '>'
        while (i > 0) {
            final char ch = tag[i];
            if (isSpace(ch)) { i--; continue; }
            return ch == '/';
        }
        return false;
    }

    private static boolean regionEquals(char[] s, int start, int end, char[] needle) {
        final int len = end - start;
        if (len != needle.length) return false;
        for (int i = 0; i < len; i++) {
            if (s[start + i] != needle[i]) return false;
        }
        return true;
    }

    /**
     * Minimal start-tag attribute scanner.
     *
     * <p>Matches rules of the form:</p>
     * <ul>
     *   <li>@attr        : presence test (matches boolean attributes and valued attributes)</li>
     *   <li>@attr='v'    : exact value match (quotes not included)</li>
     * </ul>
     *
     * <p>Accepts in the tag:</p>
     * <ul>
     *   <li>{@code attr} (boolean attribute)</li>
     *   <li>{@code attr="v"} / {@code attr='v'}</li>
     *   <li>{@code attr=v} (tolerated; useful for sloppy HTML)</li>
     * </ul>
     */
    private static boolean matchesAnyAttrTest(char[] tag, int n, AttrTest[] tests)
    {
        int i = 1; // after '<'

        // skip element qname
        while (i < n) {
            final char ch = tag[i];
            if (isSpace(ch) || ch == '>' || ch == '/') break;
            i++;
        }

        while (i < n)
        {
            while (i < n && isSpace(tag[i])) i++;
            if (i >= n) break;

            char ch = tag[i];
            if (ch == '>' || ch == '/') break;

            // attr qname [nameStart,nameEnd), plus localStart within it
            // final int nameStart = i;
            int localStart = i;

            while (i < n) {
                ch = tag[i];
                if (ch == ':') localStart = i + 1;
                if (isSpace(ch) || ch == '=' || ch == '>' || ch == '/') break;
                i++;
            }
            final int nameEnd = i;

            if (nameEnd <= localStart) {
                // malformed, skip one char to avoid infinite loop
                i++;
                continue;
            }

            while (i < n && isSpace(tag[i])) i++;

            boolean hasValue = false;
            int vStart = 0, vEnd = 0;

            if (i < n && tag[i] == '=') {
                hasValue = true;
                i++; // '='
                while (i < n && isSpace(tag[i])) i++;
                if (i >= n) break;

                final char q = tag[i];
                if (q == '"' || q == '\'') {
                    i++;
                    vStart = i;
                    while (i < n && tag[i] != q) i++;
                    vEnd = i;
                    if (i < n) i++; // consume closing quote
                }
                else {
                    vStart = i;
                    while (i < n) {
                        ch = tag[i];
                        if (isSpace(ch) || ch == '>' || ch == '/') break;
                        i++;
                    }
                    vEnd = i;
                }
            }
            // else: boolean attribute (presence only)

            // Compare against rules (few rules => linear scan is fine)
            for (AttrTest t : tests) {
                if (!regionEquals(tag, localStart, nameEnd, t.name)) continue;

                if (t.presenceOnly) return true;
                if (hasValue && regionEquals(tag, vStart, vEnd, t.value)) return true;
            }

            // continue scanning
        }

        return false;
    }

    private static String localNameOfQName(String qname)
    {
        final int p = qname.lastIndexOf(':');
        return (p >= 0) ? qname.substring(p + 1) : qname;
    }
}