package com.github.oeuvres.alix.office;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Converts an inline HTML fragment drawn from a closed, self-generated tag
 * vocabulary into a sequence of styled runs, with a linear scan and a small
 * style stack rather than a DOM. Intended for citation fragments and
 * {@code Detagger}-normalised context, whose deepest nesting is
 * {@code <a><i>title</i></a>}; a tree is unnecessary. Unknown tags are ignored,
 * a handful of entities are decoded, and text is delivered to a {@link RunSink}.
 *
 * <p>Not a general HTML parser: it assumes well-formed, non-hostile input that
 * you produced. That assumption is what lets it stay this small.</p>
 */
public final class Html {
    /** Receiver of decoded, style-flagged text spans. */
    public interface RunSink {
        /**
         * Accepts one contiguous run.
         *
         * @param text   decoded text, no markup
         * @param italic inside {@code i} or {@code em}
         * @param bold   inside {@code b} or {@code strong}
         */
        void run(String text, boolean italic, boolean bold);
    }

    private Html() {}

    /**
     * Scans {@code frag} and emits runs to {@code sink}. Empty spans are
     * skipped.
     *
     * @param frag inline HTML fragment; {@code null} is treated as empty
     * @param sink receiver
     */
    public static void toRuns(final String frag, final RunSink sink) {
        if (frag == null || frag.isEmpty()) return;
        final Deque<String> open = new ArrayDeque<>();
        final int n = frag.length();
        int i = 0;
        while (i < n) {
            final char c = frag.charAt(i);
            if (c == '<') {
                final int gt = frag.indexOf('>', i);
                if (gt < 0) break;
                String tag = frag.substring(i + 1, gt).trim();
                final boolean selfClosing = tag.endsWith("/");
                if (selfClosing) tag = tag.substring(0, tag.length() - 1).trim();
                if (tag.startsWith("/")) {
                    open.pollFirst();
                } else if (!selfClosing) {
                    final int sp = tag.indexOf(' ');
                    open.push((sp < 0 ? tag : tag.substring(0, sp)).toLowerCase());
                }
                i = gt + 1;
            } else {
                final int lt = frag.indexOf('<', i);
                final int end = (lt < 0) ? n : lt;
                final String text = unescape(frag.substring(i, end));
                if (!text.isEmpty()) {
                    sink.run(text, open.contains("i") || open.contains("em"),
                                   open.contains("b") || open.contains("strong"));
                }
                i = end;
            }
        }
    }

    /**
     * Decodes the named and numeric entities that appear in the closed
     * vocabulary. Unknown entities are left verbatim.
     *
     * @param s escaped text
     * @return decoded text
     */
    private static String unescape(final String s) {
        if (s.indexOf('&') < 0) return s;
        final int n = s.length();
        final StringBuilder sb = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            final char c = s.charAt(i);
            if (c != '&') { sb.append(c); i++; continue; }
            final int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue; }
            final String ent = s.substring(i + 1, semi);
            final String rep = switch (ent) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp" -> "\u00a0";
                default -> null;
            };
            if (rep != null) { sb.append(rep); i = semi + 1; }
            else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                try { sb.appendCodePoint(Integer.parseInt(ent.substring(2), 16)); i = semi + 1; }
                catch (NumberFormatException nfe) { sb.append(c); i++; }
            } else if (ent.startsWith("#")) {
                try { sb.appendCodePoint(Integer.parseInt(ent.substring(1))); i = semi + 1; }
                catch (NumberFormatException nfe) { sb.append(c); i++; }
            } else { sb.append(c); i++; }
        }
        return sb.toString();
    }
}
