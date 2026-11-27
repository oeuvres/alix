package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.QueryTokenizerAttribute;

/** Console inspection and token-stream comparison for analysis demonstrations, not JUnit tests. */
public final class AnalysisDemoHelper
{
    /** A curated demonstration with an input and optional explanatory note. */
    public record Case(String title, String input, String notes) {}

    /** Token snapshot for comparison; POS is null when the attribute is absent. */
    public record Tok(String term, Integer pos, int start, int end) {}

    /** Prevents instantiation of this utility class. */
    private AnalysisDemoHelper() {}

    /**
     * Creates an analyzer from a tokenizer factory and a filter chain.
     * The factories are invoked for each new set of reusable components.
     * @param tokenizer factory producing a fresh tokenizer
     * @param filters filter chain, or {@code UnaryOperator.identity()} for tokenizer-only analysis
     * @return analyzer owned by the caller
     */
    public static Analyzer analyzer(final Supplier<? extends Tokenizer> tokenizer,
        final UnaryOperator<TokenStream> filters)
    {
        return new Analyzer() {
            /**
             * Creates reusable components for the field.
             * @param fieldName analyzed field
             * @return tokenizer and its output chain
             */
            @Override
            protected TokenStreamComponents createComponents(final String fieldName)
            {
                final Tokenizer source = tokenizer.get();
                return new TokenStreamComponents(source, filters.apply(source));
            }
        };
    }

    /**
     * Collects tokens from an analyzer without changing its configuration.
     * @param analyzer analyzer to inspect
     * @param field analyzed field
     * @param input source text
     * @return token snapshots
     * @throws IOException if the stream fails
     */
    public static ArrayList<Tok> collect(final Analyzer analyzer, final String field,
        final String input) throws IOException
    {
        try (TokenStream stream = analyzer.tokenStream(field, nz(input))) {
            return collect(stream);
        }
    }

    /**
     * Collects tokens from a configured stream without closing it.
     * @param stream token stream with its reader already assigned
     * @return token snapshots
     * @throws IOException if the stream fails
     */
    public static ArrayList<Tok> collect(final TokenStream stream) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final OffsetAttribute off = stream.addAttribute(OffsetAttribute.class);
        final PosAttribute pos = optional(stream, PosAttribute.class);
        final ArrayList<Tok> result = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            result.add(new Tok(term.toString(), pos == null ? null : pos.getPos(),
                off.startOffset(), off.endOffset()));
        }
        stream.end();
        return result;
    }

    /**
     * Collects tokens directly from a tokenizer and closes its stream.
     * @param tokenizer tokenizer to inspect
     * @param input source text
     * @return token snapshots
     * @throws IOException if the stream fails
     */
    public static ArrayList<Tok> collect(final Tokenizer tokenizer, final String input)
        throws IOException
    {
        tokenizer.setReader(new StringReader(nz(input)));
        try (tokenizer) {
            return collect(tokenizer);
        }
    }

    /**
     * Returns an escaped context excerpt with a marker under the requested offsets.
     * @param input source text
     * @param start start offset
     * @param end end offset
     * @return short context and marker, or an invalid-offset diagnostic
     */
    public static String context(final String input, final int start, final int end)
    {
        final String text = nz(input);
        if (start < 0 || end < start || end > text.length()) return "<bad offsets>";
        final int lo = Math.max(0, start - 30);
        final int hi = Math.min(text.length(), end + 30);
        final int prefix = escape(text.substring(lo, start)).length();
        final int width = escape(text.substring(start, end)).length();
        return "\"" + escape(text.substring(lo, hi)) + "\"\n " + " ".repeat(prefix)
            + "^" + "-".repeat(Math.max(0, width - 2)) + (width > 1 ? "^" : "");
    }

    /**
     * Prints a headed TSV view of a configured stream without closing it.
     * Optional attributes appear only when the stream provides them.
     * @param stream token stream with its reader already assigned
     * @param input source text used to display the original offset slices
     * @throws IOException if the stream fails
     */
    public static void dump(final TokenStream stream, final String input) throws IOException
    {
        final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        final OffsetAttribute off = stream.addAttribute(OffsetAttribute.class);
        final PositionIncrementAttribute inc = stream.addAttribute(PositionIncrementAttribute.class);
        final PosAttribute pos = optional(stream, PosAttribute.class);
        final LemmaAttribute lemma = optional(stream, LemmaAttribute.class);
        final ProbAttribute prob = optional(stream, ProbAttribute.class);
        final TypeAttribute type = optional(stream, TypeAttribute.class);
        final QueryTokenizerAttribute query = optional(stream, QueryTokenizerAttribute.class);

        System.out.println("position\toffsets\tsource\tterm"
            + (pos == null ? "" : "\tpos") + (lemma == null ? "" : "\tlemma")
            + (prob == null ? "" : "\tprob") + (type == null ? "" : "\ttype")
            + (query == null ? "" : "\tquery"));
        int position = -1;
        stream.reset();
        while (stream.incrementToken()) {
            position += inc.getPositionIncrement();
            final int start = off.startOffset();
            final int end = off.endOffset();
            final StringBuilder row = new StringBuilder();
            row.append(position).append('\t').append('[').append(start).append(',').append(end)
                .append(")\t").append(safeSlice(input, start, end)).append('\t')
                .append(escape(term.toString()));
            if (pos != null) row.append('\t').append(posName(pos.getPos()));
            if (lemma != null) row.append('\t').append(escape(lemma.value()));
            if (prob != null) row.append('\t').append(String.format(Locale.ROOT, "%.5f", prob.getProb()));
            if (type != null) row.append('\t').append(escape(type.type()));
            if (query != null) row.append('\t').append(escape(query.toString()));
            System.out.println(row);
        }
        stream.end();
    }

    /**
     * Escapes line breaks and tabs so a token occupies one console row.
     * @param text source text, possibly null
     * @return escaped text
     */
    public static String escape(final String text)
    {
        return text == null ? "null" : text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Prints the first difference between two token snapshots, if any.
     * @param aName first stream label
     * @param a first snapshot
     * @param bName second stream label
     * @param b second snapshot
     * @param input original text for context display
     */
    public static void firstDiff(final String aName, final List<Tok> a,
        final String bName, final List<Tok> b, final String input)
    {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            final Tok ta = a.get(i);
            final Tok tb = b.get(i);
            if (ta.equals(tb)) continue;
            System.out.println("First diff at token #" + i);
            System.out.println("  " + aName + ": " + formatTok(ta));
            System.out.println("  " + bName + ": " + formatTok(tb));
            System.out.println("  ctx: " + context(input, Math.min(ta.start(), tb.start()),
                Math.max(ta.end(), tb.end())));
            return;
        }
        if (a.size() != b.size()) {
            System.out.println("Different token counts: " + aName + "=" + a.size()
                + " " + bName + "=" + b.size());
        } else {
            System.out.println("No diff (same stream)");
        }
    }

    /**
     * Prints the tokens of an analyzer for one input.
     * @param analyzer analyzer to inspect
     * @param field analyzed field
     * @param input source text
     * @throws IOException if the stream fails
     */
    public static void printTokens(final Analyzer analyzer, final String field,
        final String input) throws IOException
    {
        try (TokenStream stream = analyzer.tokenStream(field, nz(input))) {
            dump(stream, input);
        }
    }

    /**
     * Prints the tokens of a tokenizer and closes its stream.
     * @param tokenizer tokenizer to inspect
     * @param input source text
     * @throws IOException if the stream fails
     */
    public static void printTokens(final Tokenizer tokenizer, final String input)
        throws IOException
    {
        tokenizer.setReader(new StringReader(nz(input)));
        try (tokenizer) {
            dump(tokenizer, input);
        }
    }

    /**
     * Runs the selected demonstration cases through one reusable analyzer.
     * @param analyzer analyzer to inspect
     * @param field analyzed field
     * @param cases demonstration cases
     * @throws UncheckedIOException if a token stream fails
     */
    public static void runAll(final Analyzer analyzer, final String field, final List<Case> cases)
    {
        for (Case c : cases) {
            System.out.println("\n== " + c.title());
            System.out.println(c.input());
            if (c.notes() != null && !c.notes().isEmpty()) System.out.println("-- " + c.notes());
            try {
                printTokens(analyzer, field, c.input());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Returns an escaped source slice, diagnosing invalid offsets without throwing.
     * @param input source text
     * @param start start offset
     * @param end end offset
     * @return escaped slice or an offset diagnostic
     */
    public static String safeSlice(final String input, final int start, final int end)
    {
        final String text = nz(input);
        if (start < 0 || end < start) return "<bad offsets>";
        if (end > text.length()) return "<out of range>";
        return escape(text.substring(start, end));
    }

    /**
     * Formats one token snapshot for the difference report.
     * @param token snapshot
     * @return readable token description
     */
    private static String formatTok(final Tok token)
    {
        return escape(token.term()) + " pos=" + posName(token.pos())
            + " [" + token.start() + "," + token.end() + ")";
    }

    /**
     * Converts a nullable source string to a reader-safe string.
     * @param text possibly null string
     * @return original string or empty string
     */
    private static String nz(final String text)
    {
        return Objects.requireNonNullElse(text, "");
    }

    /**
     * Looks up an attribute without adding it to the inspected stream.
     * @param <T> attribute interface
     * @param stream inspected stream
     * @param type attribute class
     * @return existing attribute or null
     */
    private static <T extends org.apache.lucene.util.Attribute> T optional(
        final TokenStream stream, final Class<T> type)
    {
        return stream.hasAttribute(type) ? stream.getAttribute(type) : null;
    }

    /**
     * Formats a UPOS ordinal, including absent or invalid annotations.
     * @param ordinal POS identifier, possibly null
     * @return tag name or a diagnostic
     */
    private static String posName(final Integer ordinal)
    {
        if (ordinal == null) return "";
        final Upos tag = Upos.get(ordinal);
        return tag == null ? "?" + ordinal : tag.name();
    }
}
