package com.github.oeuvres.alix.util;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
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

    /**
     * Return a normalize-space() text version of an xml excerpt (possibly broken).
     *
     * @param xml span of text with tags.
     * @return normalized text without tags.
     */
    public static String detag(final String xml)
    {
        if (xml == null || xml.isEmpty()) {
            return "";
        }
        Chain dest = new Chain();
        detag(xml, 0, xml.length(), dest, null);
        return dest.toString();
    }

    /**
     * Return a normalize-space() text version of an xml excerpt (possibly broken),
     * with selected tags allowed
     *
     * @param xml     span of text with tags.
     * @param include set of tags allowed.
     * @return normalized text without tags.
     */
    public static String detag(final String xml, Set<String> include)
    {
        if (xml == null || xml.isEmpty()) {
            return "";
        }
        Chain dest = new Chain();
        detag(xml, 0, xml.length(), dest, include);
        return dest.toString();
    }

    /**
     * Append a normalize-space() text view of an XML/HTML excerpt (tag soup).
     *
     * <p>
     * Behavior:
     * </p>
     * <ul>
     * <li>Collapses ASCII whitespace (space, tab, CR, LF) to a single space;
     * removes leading and trailing space (for the content appended by this
     * call).</li>
     * <li>Removes all element markup by default. If {@code include} is non-null and
     * contains the element <em>local</em> name (case-sensitive), the tag markup
     * {@code <...>} is preserved (text content is always kept).</li>
     * <li>Tolerates broken excerpts:
     * <ul>
     * <li>If the slice begins inside a tag, the initial broken markup is
     * discarded.</li>
     * <li>If the slice ends inside a tag, the unterminated markup is
     * discarded.</li>
     * </ul>
     * </li>
     * <li>Does not decode entities, does not interpret comments/PI/CDATA specially;
     * they are treated as tags and stripped unless explicitly included by
     * name.</li>
     * </ul>
     *
     * <p>
     * Destination: content is <em>appended</em> to {@code dest}. Nothing is
     * cleared.
     * </p>
     *
     * @param xml     source text (possibly ill-formed)
     * @param begin   start index (inclusive); clamped to {@code [0, xml.length()]}
     * @param end     end index (exclusive); clamped to {@code [0, xml.length()]}
     * @param dest    output buffer; receives normalized text and any preserved tags
     * @param include set of tag names to preserve (case-sensitive, local-name
     *                match). If {@code null} or empty, all tags are stripped.
     */
    public static void detag(final String xml, int begin, int end, final Appendable dest, final Set<String> include)
    {
        if (xml == null || dest == null) {
            return;
        }
        // Clamp range
        if (begin < 0) {
            begin = 0;
        }
        if (end < begin) {
            return;
        }
        if (end > xml.length()) {
            end = xml.length();
        }
        if (begin >= end) {
            return;
        }

        boolean inTag = false;
        // tag buffers
        final StringBuilder tagBuf = new StringBuilder(64); // whole "<...>"
        final StringBuilder nameBuf = new StringBuilder(32); // qname without leading '/'
        boolean recordName = false;

        final int destInitialLenght = dest.length();
        for (int i = begin; i < end; i++) {
            final char c = xml.charAt(i);
            final char lastPrinted = (dest.length() == destInitialLenght) ? 0 : dest.last();

            if (!inTag) {
                switch (c) {
                    case ' ':
                    case '\t':
                    case '\r':
                    case '\n':
                        if (lastPrinted != ' ') {
                            dest.append(' ');
                        }
                        break;

                    case '<': {
                        inTag = true;
                        recordName = true;
                        tagBuf.setLength(0);
                        nameBuf.setLength(0);
                        tagBuf.append(c);
                        break;
                    }

                    case '>': {
                        // '>' before any '<' => slice probably began inside a tag; discard everything
                        dest.setLength(destInitialLenght);
                        break;
                    }

                    default: {
                        dest.append(c);
                        break;
                    }
                }
            } else {
                // inside tag: collect tag markup and (loosely) record element name
                tagBuf.append(c);

                if (recordName) {
                    switch (c) {
                        case ' ':
                        case '\t':
                        case '\r':
                        case '\n':
                        case '>':
                            recordName = false;
                            break;
                        case '/':
                            // Leading '/' (</tag>) is not part of the name; keep recording.
                            // A '/' after a name (<br/>) ends the name.
                            if (nameBuf.length() > 0) {
                                recordName = false;
                            }
                            break;
                        case '?':
                        case '!':
                            // reject special markup like <! ...> or <? ...>
                            recordName = false;
                            break;
                        default:
                            nameBuf.append(c);
                    }
                }

                if (c == '>') {
                    inTag = false;
                    recordName = false;

                    boolean keep = false;
                    if (include != null && include.size() != 0 && nameBuf.length() != 0) {
                        // local-name semantics: compare after the last ':' (case-sensitive)
                        String qname = nameBuf.toString();
                        int colon = qname.lastIndexOf(':');
                        String local = (colon >= 0) ? qname.substring(colon + 1) : qname;
                        keep = include.contains(local);
                    }

                    if (keep) {
                        dest.append(tagBuf);
                    }
                }
            }
        }
    }

    /**
     * Ensure that a String could be included as html text.
     *
     * @param cs chars to escape.
     * @return String escaped for html.
     */
    public static String escape(final CharSequence cs)
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
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }


}
