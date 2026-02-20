package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.Attribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared utilities for Lucene analysis demos (Tokenizer / TokenFilter / Analyzer).
 *
 * <p>Scope: human inspection and manual regression (console dump + token snapshots + first-diff).
 * This is intentionally not a JUnit helper.</p>
 *
 * <p>Supported attributes:</p>
 * <ul>
 *   <li>Required: {@link CharTermAttribute}, {@link OffsetAttribute}</li>
 *   <li>Optional: {@link PosAttribute} (printed/collected if present)</li>
 * </ul>
 */
public final class AnalysisDemoSupport {

    private AnalysisDemoSupport() { }

    /** A curated demo case. */
    public record Case(String title, String input, String notes) { }

    /**
     * Token snapshot for diffing/printing.
     * {@code pos} is null if {@link PosAttribute} is absent from the stream.
     */
    public record Tok(String term, Integer pos, int start, int end) { }

    // -------------------------------------------------------------------------
    // Public API: printing
    // -------------------------------------------------------------------------

    public static void printTokens(final Tokenizer tokenizer, final String input) throws IOException {
        Objects.requireNonNull(tokenizer, "tokenizer");
        tokenizer.setReader(new StringReader(nz(input)));
        try {
            dump(tokenizer, input);
        } finally {
            tokenizer.close();
        }
    }

    public static void printTokens(final Analyzer analyzer, final String field, final String input) throws IOException {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(field, "field");
        try (TokenStream ts = analyzer.tokenStream(field, new StringReader(nz(input)))) {
            dump(ts, input);
        }
    }

    /**
     * Dump a TokenStream already configured with its Reader (if needed).
     *
     * <p>Calls {@code reset()} and {@code end()} but does not close the stream.</p>
     */
    public static void dump(final TokenStream ts, final String input) throws IOException {
        Objects.requireNonNull(ts, "ts");

        final CharTermAttribute termAtt = ts.getAttribute(CharTermAttribute.class);
        final OffsetAttribute offAtt = ts.getAttribute(OffsetAttribute.class);
        final PosAttribute posAtt = ts.hasAttribute(PosAttribute.class) ? ts.getAttribute(PosAttribute.class) : null;
        final ProbAttribute probAtt = ts.getAttribute(ProbAttribute.class);

        ts.reset();
        try {
            int i = 0;
            while (ts.incrementToken()) {
                final int start = offAtt.startOffset();
                final int end = offAtt.endOffset();
                final int pos = (posAtt != null)?posAtt.getPos():0;
                final double prob = (probAtt != null)?probAtt.getProb():-1;

                System.out.printf(
                    "%5d  [%d,%d) |%s| %s  %-6s  %.5f%n",
                    i++,
                    start, end,
                    safeSlice(input, start, end),
                    escape(termAtt.toString()),
                    Upos.get(pos).name(),
                    prob
                );
            }
            ts.end();
        } finally {
            // caller closes
        }
    }

    // -------------------------------------------------------------------------
    // Public API: snapshot + diff
    // -------------------------------------------------------------------------

    public static ArrayList<Tok> collect(final Tokenizer tokenizer, final String input) throws IOException {
        Objects.requireNonNull(tokenizer, "tokenizer");
        tokenizer.setReader(new StringReader(nz(input)));
        try {
            return collect(tokenizer);
        } finally {
            tokenizer.close();
        }
    }

    public static ArrayList<Tok> collect(final Analyzer analyzer, final String field, final String input) throws IOException {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(field, "field");
        try (TokenStream ts = analyzer.tokenStream(field, new StringReader(nz(input)))) {
            return collect(ts);
        }
    }

    /**
     * Collect a token snapshot list from a configured TokenStream.
     *
     * <p>Calls {@code reset()} and {@code end()} but does not close the stream.</p>
     */
    public static ArrayList<Tok> collect(final TokenStream ts) throws IOException {
        Objects.requireNonNull(ts, "ts");

        final CharTermAttribute term = ts.getAttribute(CharTermAttribute.class);
        final OffsetAttribute off = ts.getAttribute(OffsetAttribute.class);
        final PosAttribute pos = ts.hasAttribute(PosAttribute.class) ? ts.getAttribute(PosAttribute.class) : null;

        final ArrayList<Tok> out = new ArrayList<>(256);

        ts.reset();
        try {
            while (ts.incrementToken()) {
                final Integer p = (pos == null) ? null : pos.getPos();
                out.add(new Tok(term.toString(), p, off.startOffset(), off.endOffset()));
            }
            ts.end();
            return out;
        } finally {
            // caller closes
        }
    }

    /**
     * Print the first token-level difference between two snapshots.
     * Differences include term, pos, and offsets.
     */
    public static void firstDiff(
        final String aName, final List<Tok> a,
        final String bName, final List<Tok> b,
        final String input
    ) {
        Objects.requireNonNull(aName, "aName");
        Objects.requireNonNull(bName, "bName");
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");

        final int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            final Tok ta = a.get(i);
            final Tok tb = b.get(i);
            if (!ta.equals(tb)) {
                System.out.println("First diff at token #" + i);
                System.out.println("  " + aName + ": " + formatTok(ta));
                System.out.println("  " + bName + ": " + formatTok(tb));
                System.out.println("  ctx: " + context(
                    nz(input),
                    Math.min(ta.start(), tb.start()),
                    Math.max(ta.end(), tb.end())
                ));
                return;
            }
        }
        if (a.size() != b.size()) {
            System.out.println("Different token counts: " + aName + "=" + a.size() + " " + bName + "=" + b.size());
        } else {
            System.out.println("No diff (same stream)");
        }
    }


    /** Avoid null inputs in Readers / slicing. */
    private static String nz(final String s) {
        return (s == null) ? "" : s;
    }


    private static String formatTok(final Tok t) {
        return escape(t.term())
            + "  pos=" + Upos.get(t.pos()).name()
            + "  [" + t.start() + "," + t.end() + ")";
    }

    public static String escape(final String s) {
        if (s == null) return "null";
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Safe substring for offset debugging; does not throw. */
    public static String safeSlice(final String input, int start, int end) {
        final String in = nz(input);
        if (start < 0 || end < 0 || start > end) return "<bad offsets>";
        if (start > in.length()) return "<out of range>";
        if (end > in.length()) end = in.length();
        return in.substring(start, end);
    }

    /**
     * Return a short context snippet around [start,end) with a caret marker.
     */
    public static String context(final String input, final int start, final int end) {
        final String in = nz(input);
        final int s = Math.max(0, start);
        final int e = Math.min(in.length(), Math.max(s, end));

        final int lo = Math.max(0, s - 30);
        final int hi = Math.min(in.length(), e + 30);

        final String ctx = in.substring(lo, hi).replace("\n", "\\n").replace("\r", "\\r");
        final int a = s - lo;
        final int b = Math.max(a, e - lo);

        final StringBuilder sb = new StringBuilder();
        sb.append("\"").append(ctx).append("\"");
        sb.append("\n       ");
        for (int i = 0; i < a; i++) sb.append(' ');
        sb.append('^');
        for (int i = a + 1; i < b; i++) sb.append('-');
        sb.append('^');
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Optional convenience: run cases
    // -------------------------------------------------------------------------

    /**
     * Convenience runner for a list of cases using an Analyzer.
     * Keeps demo classes small.
     */
    public static void runAll(final Analyzer analyzer, final String field, final List<Case> cases) {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(cases, "cases");
        for (Case c : cases) {
            System.out.println();
            System.out.println("== " + c.title());
            System.out.println(c.input());
            if (c.notes() != null && !c.notes().isEmpty()) {
                System.out.println("-- " + c.notes());
            }
            try {
                printTokens(analyzer, field, c.input());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
