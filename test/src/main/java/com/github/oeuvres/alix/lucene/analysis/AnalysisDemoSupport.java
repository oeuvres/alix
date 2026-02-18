package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared utilities for Lucene TokenStream demos:
 * <ul>
 * <li>curated {@link Case} inputs</li>
 * <li>token dump to stdout</li>
 * <li>token snapshots and first-diff reporting</li>
 * </ul>
 *
 * <p>
 * Designed for human inspection (manual regression), not for automated JUnit
 * assertions.
 * </p>
 */
public final class AnalysisDemoSupport
{
    private AnalysisDemoSupport()
    {
    }

    /** A curated demo case. */
    public record Case(String id, String title, String input, String notes)
    {
    }

    /**
     * A snapshot of a token for diffing/printing. flags/pos may be null if absent.
     */
    public record Tok(String term, Integer flags, Integer pos, int start, int end)
    {
        @Override
        public String toString()
        {
            return escape(term) + "  " + (flags == null ? "-" : flags.toString()) + "  "
                    + (pos == null ? "-" : pos.toString()) + "  [" + start + "," + end + ")";
        }
    }

    // -------------------------------------------------------------------------
    // Dump helpers (Tokenizer / Analyzer)
    // -------------------------------------------------------------------------

    public static void printTokens(Tokenizer tok, String input) throws IOException
    {
        printTokens(tok, input);
    }

    public static void printTokens(Analyzer analyzer, String field, String input) throws IOException
    {
        Objects.requireNonNull(analyzer, "analyzer");
        try (TokenStream ts = analyzer.tokenStream(field, new StringReader(input))) {
            dumpAndClose(ts, input);
        }
    }

    private static void dumpAndClose(TokenStream ts, String input) throws IOException
    {
        try {
            dump(ts, input);
        } finally {
            ts.close();
        }
    }

    /**
     * Dump a TokenStream already configured with its Reader (if needed). This
     * method calls reset/end and closes only if you call a close-wrapping helper.
     */
    public static void dump(TokenStream ts, String input) throws IOException
    {
        Objects.requireNonNull(ts, "ts");

        if (!ts.hasAttribute(CharTermAttribute.class)) {
            throw new IllegalStateException("TokenStream has no CharTermAttribute");
        }
        if (!ts.hasAttribute(OffsetAttribute.class)) {
            throw new IllegalStateException("TokenStream has no OffsetAttribute");
        }

        final CharTermAttribute term = ts.getAttribute(CharTermAttribute.class);
        final OffsetAttribute off = ts.getAttribute(OffsetAttribute.class);
        final PosAttribute pos = ts.hasAttribute(PosAttribute.class) ? ts.getAttribute(PosAttribute.class) : null;

        ts.reset();
        try {
            int i = 0;
            while (ts.incrementToken()) {
                final int s = off.startOffset();
                final int e = off.endOffset();
                final Integer p = (pos == null) ? null : pos.getPos();

                System.out.printf("%5d  [%d,%d)  %-10s  %-8s  %s |%s|%n", i++, s, e, Upos.name(p),
                        escape(term.toString()), safeSlice(input, s, e));
            }
            ts.end();
        } finally {
            // do not close here; caller decides (Analyzer helper uses try-with-resources)
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot + diff
    // -------------------------------------------------------------------------

    public static ArrayList<Tok> collect(Tokenizer tok, String input) throws IOException
    {
        Objects.requireNonNull(tok, "tok");
        tok.setReader(new StringReader(input));
        return collectAndClose(tok, input);
    }

    public static ArrayList<Tok> collect(Analyzer analyzer, String field, String input) throws IOException
    {
        Objects.requireNonNull(analyzer, "analyzer");
        try (TokenStream ts = analyzer.tokenStream(field, new StringReader(input))) {
            return collect(ts);
        }
    }

    private static ArrayList<Tok> collectAndClose(TokenStream ts, String input) throws IOException
    {
        try {
            return collect(ts);
        } finally {
            ts.close();
        }
    }

    /**
     * Collect a snapshot list from a configured TokenStream (reader already set if
     * needed).
     */
    public static ArrayList<Tok> collect(TokenStream ts) throws IOException
    {
        if (!ts.hasAttribute(CharTermAttribute.class)) {
            throw new IllegalStateException("TokenStream has no CharTermAttribute");
        }
        if (!ts.hasAttribute(OffsetAttribute.class)) {
            throw new IllegalStateException("TokenStream has no OffsetAttribute");
        }

        final CharTermAttribute term = ts.getAttribute(CharTermAttribute.class);
        final OffsetAttribute off = ts.getAttribute(OffsetAttribute.class);
        final FlagsAttribute flags = ts.hasAttribute(FlagsAttribute.class) ? ts.getAttribute(FlagsAttribute.class)
                : null;
        final PosAttribute pos = ts.hasAttribute(PosAttribute.class) ? ts.getAttribute(PosAttribute.class) : null;

        final ArrayList<Tok> out = new ArrayList<>(256);
        ts.reset();
        try {
            while (ts.incrementToken()) {
                final Integer f = (flags == null) ? null : flags.getFlags();
                final Integer p = (pos == null) ? null : pos.getPos();
                out.add(new Tok(term.toString(), f, p, off.startOffset(), off.endOffset()));
            }
            ts.end();
            return out;
        } finally {
            // caller closes
        }
    }

    /**
     * Print the first token-level difference between two snapshot lists.
     */
    public static void firstDiff(String aName, List<Tok> a, String bName, List<Tok> b, String input)
    {
        final int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            final Tok ta = a.get(i), tb = b.get(i);
            if (!ta.equals(tb)) {
                System.out.println("First diff at token #" + i);
                System.out.println("  " + aName + ": " + ta);
                System.out.println("  " + bName + ": " + tb);
                System.out.println("  ctx: " + context(input, Math.min(ta.start, tb.start), Math.max(ta.end, tb.end)));
                return;
            }
        }
        if (a.size() != b.size()) {
            System.out.println("Different token counts: " + aName + "=" + a.size() + " " + bName + "=" + b.size());
        } else {
            System.out.println("No diff (same stream)");
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    public static String escape(String s)
    {
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Safe substring for offset debugging; does not throw. */
    public static String safeSlice(String input, int start, int end)
    {
        if (input == null)
            return "";
        if (start < 0 || end < 0 || start > end)
            return "<bad offsets>";
        if (start > input.length())
            return "<out of range>";
        if (end > input.length())
            end = input.length();
        return input.substring(start, end);
    }

    public static String context(String input, int start, int end)
    {
        int lo = Math.max(0, start - 30);
        int hi = Math.min(input.length(), end + 30);
        String ctx = input.substring(lo, hi).replace("\n", "\\n").replace("\r", "\\r");
        int a = start - lo;
        int b = Math.max(a, end - lo);

        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(ctx).append("\"");
        sb.append("\n       ");
        for (int i = 0; i < a; i++)
            sb.append(' ');
        sb.append('^');
        for (int i = a + 1; i < b; i++)
            sb.append('-');
        sb.append('^');
        return sb.toString();
    }
}
