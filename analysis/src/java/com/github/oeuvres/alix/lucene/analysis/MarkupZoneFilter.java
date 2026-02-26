package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ElementAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * <p>
 * A streaming zone filter driven by minimal XSLT-like {@code match=} expressions.
 * The tokenizer is assumed to emit XML tags as normal tokens whose term text is
 * the literal tag (between {@code <...>}), and whose {@link PosAttribute} is
 * {@code XML.code}.
 * </p>
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li><b>Preserves all XML tag tokens</b> (does not modify their {@link CharTermAttribute}).</li>
 *   <li>Maintains an <b>open-element stack</b> by parsing start/end tags.</li>
 *   <li>Sets {@link ElementAttribute} on <b>XML tag tokens only</b>:
 *       local-name + event (OPEN/CLOSE/EMPTY/OTHER).</li>
 *   <li>Filters <b>non-XML tokens only</b> according to {@link Mode} and the compiled match expression.</li>
 * </ul>
 *
 * <h2>Minimal match syntax (OR only)</h2>
 * <p>
 * Expression is a {@code |}-separated list of "atoms". Each atom is either:
 * </p>
 * <ul>
 *   <li>An element name (local-name only): {@code teiHeader} or {@code teiHeader|note}</li>
 *   <li>An attribute test: {@code @data-tei-type='quote'} (also local-name only for the attribute name)</li>
 * </ul>
 *
 * <p>
 * There is no descendant axis, no predicates, no AND, no wildcards; this is intentionally limited.
 * Mixing element atoms and attribute atoms is supported because matching is defined as OR:
 * a start-tag matches if its element local-name matches <i>any</i> element atom OR it contains
 * <i>any</i> attribute pair atom.
 * </p>
 *
 * <h2>Filtering semantics</h2>
 * <ul>
 *   <li>{@link Mode#INCLUDE}: keep non-XML tokens only when inside at least one matched open element.</li>
 *   <li>{@link Mode#EXCLUDE}: drop non-XML tokens whenever inside at least one matched open element.</li>
 * </ul>
 *
 * <h2>Suppression strategies for dropped non-XML tokens</h2>
 * <ul>
 *   <li>{@link Suppress#DROP}: simply drop tokens (position increments collapse).</li>
 *   <li>{@link Suppress#GAPS}: accumulate dropped tokens' {@link PositionIncrementAttribute} and add it to the
 *       next emitted <b>non-XML</b> token. Offsets of emitted tokens are unchanged.</li>
 *   <li>{@link Suppress#PLACEHOLDER}: emit one placeholder token for each contiguous run of dropped non-XML tokens.
 *       The placeholder offsets span the dropped run. (This is often useful for debugging or downstream heuristics.)</li>
 * </ul>
 *
 * <p>
 * Note: emitting empty terms (term length 0) as a "suppression" technique is deliberately not implemented here;
 * depending on your Lucene version and indexing pipeline, zero-length terms can be unsafe.
 * </p>
 *
 * <h2>Usage example</h2>
 *
 * <pre>{@code
 * TokenStream ts = new YourXmlTokenizer(reader); // emits XML tags as tokens: pos==XML.code, term is "<...>"
 *
 * // Keep only quoted passages and observations (non-XML tokens),
 * // but keep ALL XML tag tokens unchanged in the stream.
 * ts = new MarkupZoneFilter(
 *         ts,
 *         "@data-tei-type='quote'|other-attribute='observation'",
 *         MarkupZoneFilter.Mode.INCLUDE,
 *         MarkupZoneFilter.Suppress.GAPS
 * );
 *
 * // Later, if you do not want markup indexed, strip tag tokens in a final filter.
 * // ts = new StripTagsFilter(ts);
 * }</pre>
 */
public final class MarkupZoneFilter extends TokenFilter
{
    public enum Mode { INCLUDE, EXCLUDE }

    public enum Suppress
    {
        DROP,
        GAPS,
        PLACEHOLDER
    }

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
                    // element atom (local-name only)
                    String ln = localNameOfQName(s);
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
            if (elemNames.length != 0 && matchesAnyElemName(tag, elLocalStart, elLocalEnd, elemNames)) {
                return true;
            }
            if (attrPairs.length != 0 && matchesAnyAttrPair(tag, n, attrPairs)) {
                return true;
            }
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
    private final OffsetAttribute offAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final ElementAttribute elemAtt = addAttribute(ElementAttribute.class);

    private final Mode mode;
    private final Suppress suppress;
    private final CompiledMatch match;

    // Match stack: for each open element, whether that element matched the compiled pattern.
    private boolean[] matchStack = new boolean[32];
    private int depth = 0;
    private int matchDepth = 0;

    // For Suppress.GAPS
    private int pendingPosInc = 0;

    // For Suppress.PLACEHOLDER
    private final String placeholderTerm;
    private State deferredState = null; // token to emit after a placeholder
    private State emitState = null;     // placeholder to emit now (highest priority)
    private State runFirstState = null;
    private int runStartOffset = 0;
    private int runEndOffset = 0;
    private int runPosInc = 0;

    public MarkupZoneFilter(TokenStream input, String matchExpr, Mode mode) {
        this(input, matchExpr, mode, Suppress.GAPS, "__Z__");
    }

    public MarkupZoneFilter(TokenStream input, String matchExpr, Mode mode, Suppress suppress) {
        this(input, matchExpr, mode, suppress, "__Z__");
    }

    public MarkupZoneFilter(TokenStream input, String matchExpr, Mode mode, Suppress suppress, String placeholderTerm) {
        super(input);
        if (mode == null) throw new NullPointerException("mode is null");
        if (suppress == null) throw new NullPointerException("suppress is null");
        this.mode = mode;
        this.suppress = suppress;
        this.placeholderTerm = (placeholderTerm == null || placeholderTerm.isEmpty()) ? "__Z__" : placeholderTerm;
        this.match = CompiledMatch.compile(matchExpr);
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        depth = 0;
        matchDepth = 0;
        pendingPosInc = 0;

        deferredState = null;
        emitState = null;
        runFirstState = null;
        runStartOffset = runEndOffset = runPosInc = 0;
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        // Emit placeholder first if scheduled.
        if (emitState != null) {
            restoreState(emitState);
            emitState = null;
            return true;
        }
        // Emit deferred token (captured before placeholder emission).
        if (deferredState != null) {
            restoreState(deferredState);
            deferredState = null;
            return true;
        }

        while (input.incrementToken())
        {
            final boolean isXml = (posAtt.getPos() == XML.code);

            if (isXml) {
                final char[] buf = termAtt.buffer();
                final int len = termAtt.length();

                // processTag MUST set elemAtt.event to OPEN/CLOSE/EMPTY/OTHER,
                // and MUST NOT pop() yet for CLOSE (see next patch).
                processTag(buf, len);

                final byte ev = elemAtt.getEvent();
                // For end tags, "inside zone" must be tested *before* pop()
                final boolean inZone = (matchDepth > 0);
                final boolean keepTag = (mode == Mode.INCLUDE)?inZone:!inZone;
                // Now that keepTag was decided with matchDepth BEFORE pop,
                // we can pop for closing tags.
                if (ev == ElementAttribute.CLOSE) {
                    pop();
                }

                if (keepTag) return true;
                continue;
            }
            
            // Non-XML token: clear element-tag attribute to avoid stale values downstream.
            elemAtt.setEmpty();
            elemAtt.setEvent(ElementAttribute.NONE);
            
            // matchDepth > 0 means we are inside at least one matched open element.
            final boolean inZone = (matchDepth > 0);
            final boolean keep = (mode == Mode.INCLUDE)?inZone:!inZone;

            if (!keep)
            {
                switch (suppress)
                {
                    case DROP:
                        // drop token, do not preserve positions
                        break;

                    case GAPS:
                        // preserve positions for phrase queries by adding gaps to next emitted NON-XML token
                        pendingPosInc += Math.max(0, posIncAtt.getPositionIncrement());
                        break;

                    case PLACEHOLDER:
                        // Build/extend a contiguous "dropped run"
                        startOrExtendDroppedRun();
                        break;
                }
                continue;
            }

            // We are about to emit a kept non-XML token. If we have a dropped run placeholder pending,
            // emit placeholder BEFORE this token.
            if (suppress == Suppress.PLACEHOLDER && runFirstState != null) {
                deferredState = captureState();          // kept token as-is
                emitState = buildPlaceholderState();
                restoreState(emitState);
                emitState = null;
                return true;
            }

            // Apply pending gaps (only to NON-XML tokens; do not touch markup tokens).
            if (suppress == Suppress.GAPS && pendingPosInc != 0) {
                posIncAtt.setPositionIncrement(posIncAtt.getPositionIncrement() + pendingPosInc);
                pendingPosInc = 0;
            }

            // Ensure sane position increment for emitted tokens.
            if (posIncAtt.getPositionIncrement() <= 0) {
                posIncAtt.setPositionIncrement(1);
            }
            return true;
        }

        // End of stream: flush trailing placeholder if needed.
        if (suppress == Suppress.PLACEHOLDER && runFirstState != null) {
            emitState = buildPlaceholderState();
            restoreState(emitState);
            emitState = null;
            return true;
        }
        return false;
    }



    private void processTag(char[] tag, int n)
    {
        // Default for "weird" markup: clear element info.
        elemAtt.setEmpty();
        elemAtt.setEvent(ElementAttribute.OTHER);

        if (n < 3 || tag[0] != '<') return;

        // PI / comments / doctype / CDATA markers
        final char c1 = tag[1];
        if (c1 == '?' || c1 == '!') {
            // keep OTHER, no stack changes
            return;
        }

        if (c1 == '/')
        {
            // End tag: </name>
            final int[] name = readElementLocalName(tag, n, 2);
            if (name[1] > name[0]) {
                elemAtt.copyBuffer(tag, name[0], name[1] - name[0]);
            }
            elemAtt.setEvent(ElementAttribute.CLOSE);
            pop();
            return;
        }

        // Start tag: <name ...> or <name .../>
        final int[] name = readElementLocalName(tag, n, 1);
        if (name[1] > name[0]) {
            elemAtt.copyBuffer(tag, name[0], name[1] - name[0]);
        }

        final boolean selfClosing = isSelfClosing(tag, n);
        elemAtt.setEvent(selfClosing ? ElementAttribute.EMPTY : ElementAttribute.OPEN);

        // Only start-tags affect matching (end-tags just pop).
        final boolean m = (name[1] > name[0]) && match.matchesStartTag(tag, n, name[0], name[1]);

        if (!selfClosing) {
            push(m);
        }
        // If self-closing: no scope; we deliberately do not push/pop.
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

    private void startOrExtendDroppedRun()
    {
        final int inc = Math.max(0, posIncAtt.getPositionIncrement());

        if (runFirstState == null) {
            runFirstState = captureState(); // state of first dropped token
            runStartOffset = offAtt.startOffset();
            runEndOffset = offAtt.endOffset();
            runPosInc = inc;
        }
        else {
            // Extend run span and position gap.
            final int end = offAtt.endOffset();
            if (end > runEndOffset) runEndOffset = end;
            runPosInc += inc;
        }
    }

    private State buildPlaceholderState()
    {
        // Restore first dropped token, then overwrite term/offsets/posInc.
        restoreState(runFirstState);

        // Placeholder term: emitted as a normal token.
        // (Alternative strategies you mentioned but NOT implemented here:
        //  - empty term token (risky),
        //  - fixed gap without any placeholder term.)
        termAtt.setEmpty().append(placeholderTerm);

        // Offsets span the dropped run.
        offAtt.setOffset(runStartOffset, runEndOffset);

        // Position increment: represent the dropped run as a single step.
        posIncAtt.setPositionIncrement(runPosInc > 0 ? runPosInc : 1);

        // This is not an XML tag token.
        elemAtt.setEmpty();
        elemAtt.setEvent(ElementAttribute.NONE);

        final State st = captureState();

        // Reset run.
        runFirstState = null;
        runStartOffset = runEndOffset = runPosInc = 0;

        return st;
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
     * Reads element local-name from an XML tag token.
     * @param from index right after '<' (1) or '</' (2)
     * @return int[]{start,end} in the same char buffer, end exclusive.
     */
    private static int[] readElementLocalName(char[] tag, int n, int from)
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
        return new int[]{ localStart, nameEnd };
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