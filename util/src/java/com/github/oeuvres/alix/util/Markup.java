package com.github.oeuvres.alix.util;


import java.util.Set;

/**
 * Some useful tools to deal with “Markup Languages” (xml, but also html tag
 * soup)
 */
public class Markup
{

    /**
     * Avoid instantiation, use static methods instead.
     */
    private Markup()
    {

    }
    
    
    /**
     * Locates the start index of a left context window for a concordance line.
     *
     * <p>Walks backward from {@code offset} through {@code xml}, skipping tag markup,
     * and stops once either {@code words} text words or {@code chars} text characters
     * have been crossed — whichever limit is reached first. Set either limit to
     * {@code -1} to disable it; at least one must be positive.</p>
     *
     * <p>The word limit never breaks inside a word: when it triggers, the scan
     * continues until the current word is fully crossed. The char limit is a hard
     * display boundary and may cut a word short — appropriate for a fixed-width KWIC
     * column.</p>
     *
     * <p>Use the returned index as the {@code begin} argument of
     * {@link #detag(String, int, int, Chain, Set)} to append the context forward into
     * a buffer without any intermediate allocation or per-character prepend.</p>
     *
     * @param xml    source markup text
     * @param offset scan start (exclusive: the character at this index is not part of
     *               the context, so pass {@code spanStart - 1})
     * @param words  maximum number of text words to include, or {@code -1} for no word limit
     * @param chars  maximum number of text characters to include, or {@code -1} for no char limit
     * @return inclusive start index of the context window; {@code 0} if the scan reaches
     *         the beginning of the string
     */
    public static int leftBoundary(final String xml, int offset, final int words, final int chars)
    {
        if (offset < 0) return 0;
        if (words == 0 || chars == 0) return offset + 1;
        int wc = 0;
        int cc = 0;
        boolean inTag = false; // scanning backward: '>' opens a tag, '<' closes it
        boolean inWord = false;
        int boundary = offset + 1; // inclusive start; updated each time we accept a text char

        while (offset >= 0) {
            final char c = xml.charAt(offset);
            switch (c) {
                case '>':
                    inTag = true;
                    break;
                case '<':
                    inTag = false;
                    break;
                case ' ':
                case '\n':
                case '\r':
                case '\t':
                    if (!inTag) {
                        if (inWord) {
                            inWord = false;
                            wc++;
                            if (words > 0 && wc >= words) {
                                // stop after the whitespace, not inside the word just crossed
                                return boundary;
                            }
                        }
                    }
                    break;
                default:
                    if (!inTag) {
                        cc++;
                        boundary = offset;
                        if (!inWord) {
                            inWord = true;
                        }
                        if (chars > 0 && cc >= chars) {
                            return boundary;
                        }
                    }
            }
            offset--;
        }
        // count a word that reaches the beginning of the string without trailing whitespace
        if (inWord) wc++;
        return boundary;
    }

    /**
     * Locates the end index of a right context window for a concordance line.
     *
     * <p>Walks forward from {@code offset} through {@code xml}, skipping tag markup,
     * and stops once either {@code words} text words or {@code chars} text characters
     * have been crossed — whichever limit is reached first. Set either limit to
     * {@code -1} to disable it; at least one must be positive.</p>
     *
     * <p>The word limit never breaks inside a word: when it triggers, the scan
     * continues until the current word is fully crossed. The char limit is a hard
     * display boundary and may cut a word short.</p>
     *
     * <p>Use the returned index as the {@code end} argument of
     * {@link #detag(String, int, int, Chain, Set)} to append the context into a
     * buffer without intermediate allocation.</p>
     *
     * @param xml    source markup text
     * @param offset scan start (inclusive: the character at this index is the first
     *               candidate of the context, so pass {@code spanEnd})
     * @param words  maximum number of text words to include, or {@code -1} for no word limit
     * @param chars  maximum number of text characters to include, or {@code -1} for no char limit
     * @return exclusive end index of the context window; {@code xml.length()} if the scan
     *         reaches the end of the string
     */
    public static int rightBoundary(final String xml, int offset, final int words, final int chars)
    {
        if (words == 0 || chars == 0) return offset;
        final int length = xml.length();
        int wc = 0;
        int cc = 0;
        boolean inTag = false;
        boolean inWord = false;
        int boundary = offset; // exclusive end; updated each time we accept a text char

        while (offset < length) {
            final char c = xml.charAt(offset);
            switch (c) {
                case '<':
                    inTag = true;
                    break;
                case '>':
                    inTag = false;
                    break;
                case ' ':
                case '\n':
                case '\r':
                case '\t':
                    if (!inTag) {
                        if (inWord) {
                            inWord = false;
                            wc++;
                            if (words > 0 && wc >= words) {
                                // stop after the whitespace, not inside the word just crossed
                                return boundary;
                            }
                        }
                    }
                    break;
                default:
                    if (!inTag) {
                        cc++;
                        boundary = offset + 1;
                        if (!inWord) {
                            inWord = true;
                        }
                        if (chars > 0 && cc >= chars) {
                            return boundary;
                        }
                    }
            }
            offset++;
        }
        return boundary;
    }
    
    public static String escapeText(final CharSequence cs)
    {
        return escape(cs, false);
    }

    public static String escapeAttribute(final CharSequence cs)
    {
        return escape(cs, true);
    }

    /**
     * Ensure that a String could be included as html text.
     *
     * @param cs chars to escape.
     * @return String escaped for html.
     */
    private static String escape(final CharSequence cs, boolean quotes)
    {
        if (cs == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(Math.max(16, cs.length()));
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (c == '<') {
                out.append("&lt;");
            } else if (c == '>') {
                out.append("&gt;");
            } else if (c == '&') {
                out.append("&amp;");
            } else if (c == '"' && quotes) {
                out.append("&quot;");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }


}
