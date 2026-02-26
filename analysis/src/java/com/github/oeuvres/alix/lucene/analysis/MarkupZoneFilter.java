/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
 *   <li>The upstream tokenizer emits XML/HTML tags as tokens (e.g. {@code "<div a='b'>"}, {@code "</p>"}).</li>
 *   <li>Tag tokens are identified by {@link PosAttribute#getPos()} == {@code XML.code}.</li>
 *   <li>The literal tag text is carried by {@link CharTermAttribute} (including {@code <} and {@code >}).</li>
 * </ul>
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li>Parses tag tokens just enough to maintain an <b>open-element stack</b>.</li>
 *   <li>Computes a boolean <b>zone membership</b> state: “inside at least one open element whose <i>start tag</i>
 *       matched the configured {@code matchExpr}”.</li>
 *   <li>Sets {@link ElementAttribute} on <b>tag tokens only</b>:
 *     <ul>
 *       <li>buffer = element local-name (namespace prefix removed)</li>
 *       <li>event = {@link ElementAttribute#OPEN}, {@link ElementAttribute#CLOSE}, {@link ElementAttribute#EMPTY},
 *           or {@link ElementAttribute#OTHER}</li>
 *     </ul>
 *   </li>
 *   <li>Filters <b>both tag tokens and non-tag tokens</b> according to {@link Mode}:
 *     <ul>
 *       <li>{@link Mode#INCLUDE}: keep tokens only when “in zone”.</li>
 *       <li>{@link Mode#EXCLUDE}: drop tokens when “in zone”.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Minimal {@code matchExpr} syntax (OR only)</h2>
 * {@code matchExpr} is a {@code |}-separated list of atoms. Each atom is either:
 * <ul>
 *   <li>An element name (local-name only): {@code teiHeader} or {@code teiHeader|note}</li>
 *   <li>An attribute test: {@code @data-tei-type='quote'} (attribute local-name only)</li>
 * </ul>
 *
 * <p>Matching is OR:</p>
 * <ul>
 *   <li>A start-tag matches if its element local-name matches <i>any</i> element atom, OR</li>
 *   <li>the start-tag contains <i>any</i> attribute pair atom.</li>
 * </ul>
 *
 * <p>Not supported: XPath axes, predicates, wildcards, AND, descendant tests.</p>
 *
 * <h2>Tag parsing limits</h2>
 * <ul>
 *   <li>End-tags pop the open-element stack; start-tags push it.</li>
 *   <li>Self-closing tags {@code <x/>} are recognized (event {@link ElementAttribute#EMPTY}) but
 *       <b>do not open a new stack frame</b> in this implementation (no push/pop). This preserves your current behaviour.</li>
 *   <li>PI/comments/doctype/CDATA-like tokens (prefix {@code <?} or {@code <!}) are tagged as {@link ElementAttribute#OTHER}
 *       and do not affect the stack.</li>
 * </ul>
 *
 * <h2>Correctness note</h2>
 * Closing tags must be tested for “in zone” <b>before</b> popping; otherwise the closing tag of an included zone is lost.
 * This filter implements that ordering: {@link #processTag(char[], int)} sets the CLOSE event but does not pop;
 * {@link #incrementToken()} pops after the keep/drop decision for CLOSE tokens.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * TokenStream ts = new YourXmlTokenizer(reader); // tag tokens have pos==XML.code and term "<...>"
 *
 * // Keep only the observation zones (tags + content inside).
 * ts = new MarkupZoneFilter(
 *     ts,
 *     "@other-attribute='observation'",
 *     MarkupZoneFilter.Mode.INCLUDE
 * );
 * }</pre>
 */
public final class MarkupZoneFilter extends TokenFilter
{
    public enum Mode { INCLUDE, EXCLUDE }

    /** Compiled OR-pattern: element local-names and/or attribute pairs. */
    private static final class CompiledMatch
    {
        final char[][] elemNames;   // local-names
        final AttrPair[] attrPairs; // local-names for attribute names

        CompiledMatch(char[][] elemNames, AttrPair[] attrPairs) {
            this.elemNames = elemNames;
            this.attrPairs = attrPairs;
        }

        static CompiledMatch compile(final String expr) {
            if (expr == null) throw new NullPointerException("match expression is null");
            final List<String> atoms = splitOr(expr);
            final List<char[]> elems = new ArrayList<>();
            final List<AttrPair> attrs = new ArrayList<>();

            for (String raw : atoms) {
                String s = raw.trim();
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
                throw new IllegalArgumentException("match expression has no usable atoms: " + expr);
            }

            return new CompiledMatch(
                elems.toArray(new char[0][]),
                attrs.toArray(new AttrPair[0])
            );
        }

        boolean matchesStartTag(char[] tag, int n, int elLocalStart, int elLocalEnd) {
            // element-name OR attribute-pair
            if (elemNames.length != 0 && matchesAnyElemName(tag, elLocalStart, elLocalEnd, elemNames)) return true;
            if (attrPairs.length != 0 && matchesAnyAttrPair(tag, n, attrPairs)) return true;
            return false;
        }

        private static boolean matchesAnyElemName(char[] s, int start, int end, char[][] names) {
            for (char[] needle : names) {
                if (regionEquals(s, start, end, needle)) return true;
            }
            return false;
        }
    }

    private static final class AttrPair
    {
        final char[] name;  // local-name
        final char[] value; // exact
        AttrPair(char[] name, char[] value) { this.name = name; this.value = value; }
    }

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final ElementAttribute elemAtt = addAttribute(ElementAttribute.class);

    private final Mode mode;
    private final CompiledMatch match;

    // Match stack: for each open element, whether that element matched the compiled pattern.
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

                // Sets elemAtt (local-name + event) and pushes for OPEN tags.
                // Does NOT pop for CLOSE tags (pop after keep decision).
                processTag(buf, len);

                final byte ev = elemAtt.getEvent();

                // For end tags, "inside zone" must be tested *before* pop()
                final boolean inZone = (matchDepth > 0);
                final boolean keep = (mode == Mode.INCLUDE) ? inZone : !inZone;

                if (ev == ElementAttribute.CLOSE) {
                    pop();
                }

                if (keep) return true;
                continue;
            }

            // Non-XML token: clear element-tag attribute to avoid stale values downstream.
            elemAtt.setEmpty();
            elemAtt.setEvent(ElementAttribute.NONE);

            final boolean inZone = (matchDepth > 0);
            final boolean keep = (mode == Mode.INCLUDE) ? inZone : !inZone;

            if (!keep) continue;
            return true;
        }
        return false;
    }

    /**
     * Parse a tag token, set {@link ElementAttribute}, and update the open-element stack for OPEN tags.
     * CLOSE tags do not pop here; pop is performed in {@link #incrementToken()} after keep/drop decision.
     */
    private void processTag(char[] tag, int n)
    {
        // Default for "weird" markup: clear element info.
        elemAtt.setEmpty();
        elemAtt.setEvent(ElementAttribute.OTHER);

        if (n < 3 || tag[0] != '<') return;

        // PI / comments / doctype / CDATA markers
        final char c1 = tag[1];
        if (c1 == '?' || c1 == '!') {
            return;
        }

        if (c1 == '/')
        {
            // End tag: </name>
            final long span = readElementLocalNameSpan(tag, n, 2);
            final int start = (int)(span >>> 32);
            final int end = (int)span;

            if (end > start) elemAtt.copyBuffer(tag, start, end - start);
            elemAtt.setEvent(ElementAttribute.CLOSE);
            return;
        }

        // Start tag: <name ...> or <name .../>
        final long span = readElementLocalNameSpan(tag, n, 1);
        final int start = (int)(span >>> 32);
        final int end = (int)span;

        if (end > start) elemAtt.copyBuffer(tag, start, end - start);

        final boolean selfClosing = isSelfClosing(tag, n);
        elemAtt.setEvent(selfClosing ? ElementAttribute.EMPTY : ElementAttribute.OPEN);

        // Only start-tags affect matching.
        final boolean matched = (end > start) && match.matchesStartTag(tag, n, start, end);

        if (!selfClosing) {
            push(matched);
        }
        // selfClosing: no scope (current behaviour preserved).
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
    // Parsing / matching helpers
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

    /**
     * Reads element local-name span from an XML tag token.
     * @param from index right after '<' (1) or '</' (2)
     * @return packed long: (start<<32) | end, end exclusive, in the same char buffer.
     */
    private static long readElementLocalNameSpan(char[] tag, int n, int from)
    {
        int i = from;
        while (i < n && isSpace(tag[i])) i++;

        final int nameStart = i;
        while (i < n) {
            final char ch = tag[i];
            if (isSpace(ch) || ch == '>' || ch == '/') break;
            i++;
        }
        final int nameEnd = i;

        // local-name: after last ':'
        int localStart = nameStart;
        for (int k = nameStart; k < nameEnd; k++) {
            if (tag[k] == ':') localStart = k + 1;
        }
        return (((long)localStart) << 32) | (nameEnd & 0xFFFFFFFFL);
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
     * Minimal attribute scanner: checks whether tag contains any attribute local-name/value pair from rules.
     * Accepts values quoted with ' or " or unquoted.
     */
    private static boolean matchesAnyAttrPair(char[] tag, int n, AttrPair[] rules)
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
            if (i >= n) return false;

            char ch = tag[i];
            if (ch == '>' || ch == '/') return false;

            // attr qname [aStart,aEnd)
            final int aStart = i;
            while (i < n) {
                ch = tag[i];
                if (isSpace(ch) || ch == '=' || ch == '>' || ch == '/') break;
                i++;
            }
            final int aEnd = i;

            while (i < n && isSpace(tag[i])) i++;
            if (i >= n || tag[i] != '=') {
                // boolean attribute or malformed; ignore
                continue;
            }
            i++; // '='
            while (i < n && isSpace(tag[i])) i++;
            if (i >= n) return false;

            // value [vStart,vEnd)
            int vStart, vEnd;
            final char q = tag[i];
            if (q == '"' || q == '\'') {
                i++;
                vStart = i;
                while (i < n && tag[i] != q) i++;
                vEnd = i;
                if (i < n) i++; // consume quote
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

            // compare against rules (few rules => linear scan)
            for (AttrPair r : rules) {
                if (regionLocalEquals(tag, aStart, aEnd, r.name) && regionEquals(tag, vStart, vEnd, r.value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Compare a qname region to a local-name needle (ignore prefix). */
    private static boolean regionLocalEquals(char[] s, int start, int end, char[] localNeedle)
    {
        int ls = start;
        for (int i = start; i < end; i++) {
            if (s[i] == ':') ls = i + 1;
        }
        return regionEquals(s, ls, end, localNeedle);
    }

    // -------------------------
    // Expression compilation
    // -------------------------

    private static List<String> splitOr(String expr)
    {
        final ArrayList<String> out = new ArrayList<>();
        final StringBuilder sb = new StringBuilder(expr.length());
        boolean inQuote = false;
        char quote = 0;

        for (int i = 0; i < expr.length(); i++) {
            final char ch = expr.charAt(i);
            if (inQuote) {
                sb.append(ch);
                if (ch == quote) inQuote = false;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inQuote = true;
                quote = ch;
                sb.append(ch);
                continue;
            }
            if (ch == '|') {
                out.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(ch);
        }
        out.add(sb.toString());
        return out;
    }

    private static AttrPair parseAttrAtom(String atom)
    {
        // atom: @name='value'  (whitespace tolerated)
        int i = 1; // skip '@'
        final int n = atom.length();

        while (i < n && isWs(atom.charAt(i))) i++;
        final int nameStart = i;
        while (i < n) {
            final char ch = atom.charAt(i);
            if (ch == '=' || isWs(ch)) break;
            i++;
        }
        final int nameEnd = i;

        while (i < n && isWs(atom.charAt(i))) i++;
        if (i >= n || atom.charAt(i) != '=') {
            throw new IllegalArgumentException("Bad attribute atom (missing '='): " + atom);
        }
        i++; // '='
        while (i < n && isWs(atom.charAt(i))) i++;
        if (i >= n) throw new IllegalArgumentException("Bad attribute atom (missing value): " + atom);

        final char q = atom.charAt(i);
        String value;
        if (q == '\'' || q == '"') {
            i++;
            final int vStart = i;
            while (i < n && atom.charAt(i) != q) i++;
            if (i >= n) throw new IllegalArgumentException("Bad attribute atom (unclosed quote): " + atom);
            value = atom.substring(vStart, i);
        }
        else {
            value = atom.substring(i).trim();
        }

        String qname = atom.substring(nameStart, nameEnd).trim();
        qname = localNameOfQName(qname);

        return new AttrPair(qname.toCharArray(), value.toCharArray());
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static String localNameOfQName(String qname)
    {
        final int p = qname.lastIndexOf(':');
        return (p >= 0) ? qname.substring(p + 1) : qname;
    }
}