package com.github.oeuvres.alix.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Some useful tools to deal with “Markup Languages” (xml, but also html tag
 * soup)
 */
public class ML
{
    /** limit kwic width */
    private static int KWIC_MAXCHARS = 500;
    /** HTML entities */
    public static final HashMap<String, Character> HTMLENT = new HashMap<String, Character>();
    /*
     * // shall we load entities ? static { BufferedReader buf = new BufferedReader(
     * new InputStreamReader(Char.class.getResourceAsStream("htmlent.csv"),
     * StandardCharsets.UTF_8)); String l; String ent; Character c; int pos; try {
     * buf.readLine(); // first line is labels while ((l = buf.readLine()) != null)
     * { l = l.trim(); if (l.charAt(0) == '#') continue; pos = l.indexOf(' '); if
     * (pos < 3) continue; if (pos + 1 >= l.length()) continue; ent = l.substring(0,
     * pos); c = l.charAt(pos + 1); HTMLENT.put(ent, c); } } catch (IOException e) {
     * e.printStackTrace(); } }
     */

    /**
     * Avoid instantiation, use static methods instead.
     */
    private ML()
    {

    }

    /**
     * Used to build a concordance. From a random point in an xml file, append text
     * to a mutable destination charSequence, limit to an amount of chars.
     * 
     * @param xml    source markup text.
     * @param offset position in xml from which append words or chars.
     * @param chain  destination chain.
     * @param chars  amount of chars to append.
     */
    public static void appendChars(final String xml, int offset, final Chain chain, final int chars)
    {
        append(xml, offset, chain, chars, -1);
    }

    /**
     * Used to build a concordance. From a random point in an xml file, append text
     * to a mutable destination charSequence, limit to an amount of chars, or words.
     * 
     * @param xml    source markup text.
     * @param offset position in xml from which append words or chars.
     * @param chain  destination chain.
     * @param chars  amount of chars append.
     * @param words  amount of words to append.
     */
    private static void append(final String xml, int offset, final Chain chain, int chars, int words)
    {
        // silently limit parameters
        if (words <= 0 && chars <= 0) chars = 50;
        else if (chars > KWIC_MAXCHARS) chars = KWIC_MAXCHARS;
        int cc = 0; // char count
        int wc = 0; // word count

        int length = xml.length();
        boolean lt = false, first = true, space = false, token = false;
        // word count and spacing will bug for non indented tags like
        // <p>word</p><p>word</p>
        while (offset < length) {
            char c = xml.charAt(offset);
            switch (c) {
            case '<':
                space = false; // renew space flag
                first = false; // no broken tag at start
                lt = true;
                break;
            case '>':
                lt = false;
                // a broken tag at start, erase what was appended
                if (first) {
                    chain.lastDel(cc);
                    cc = 0;
                    wc = 0;
                    first = false;
                    break;
                }
                break;
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                if (lt) break; // space inside tag, skip
                if (space) break; // second or more space, skip
                space = true; // stop record space after this one
                chain.append(' ');
                cc++;
                if (token) { // a token was started, stop it and count it
                    token = false;
                    wc++;
                }
                break;
            default:
                if (lt) break; // char in tag, skip
                space = false; // renew space flag
                chain.append(c);
                cc++;
                // word boundary?
                final boolean charIsTok = Char.isToken(c);
                if (!token && charIsTok) { // open a word
                    token = true;
                } else if (token && !charIsTok) { // close a word
                    token = false;
                    wc++;
                }
            }
            offset++;
            if (words <= 0); // no matter about words;
            else if (token) continue; // do not break inside a word
            else if (wc >= words) break; // we got enough words
            if (chars > 0 && cc >= chars) break; // chars limit reached
        }
        // ? delete last char for words ?
    }

    /**
     * Used to build a concordance. From a random point in an xml file, append text
     * to a mutable destination charSequence, limit to an amount of words.
     * 
     * @param xml    source markup text.
     * @param offset position in xml from which append words or chars.
     * @param chain  destination chain.
     * @param words  amount of words to append.
     */
    public static void appendWords(final String xml, int offset, final Chain chain, final int words)
    {
        append(xml, offset, chain, -1, words);
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
    public static String detag(final String xml, String[] include)
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
     * removes leading and trailing space.</li>
     * <li>Removes all element markup by default. If {@code include} is non-null and
     * contains the element <em>local</em> name (case-insensitive), the tag markup
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
     * @param include set of tag names to preserve (case-insensitive). If
     *                {@code null} or empty, all tags are stripped.
     */
    public static void detag(final String xml, int begin, int end, final Chain dest, final String[] include)
    {
        if (xml == null || dest == null) return;

        // Clamp range
        if (begin < 0) begin = 0;
        if (end < begin) return;
        if (end > xml.length()) end = xml.length();
        if (begin >= end) return;

        
        // Method-level mark: lets us discard a broken leading tag without touching
        // prior dest content.
        final int baseDepth = dest.getMarkDepth();
        dest.pushMark();

        boolean inTag = false; // currently copying tag markup
        boolean recordName = false; // still scanning the element name
        boolean spacePending = false; // one collapsed space to emit before next text char
        boolean sawLtSinceBegin = false; // have we seen any '<' yet (to identify a broken-leading '>')
        char lastInput = 0; // previous input char
        char lastPrinted = 0; // last character we actually committed to dest (for punctuation heuristic)

        final StringBuilder tagName = new StringBuilder(); // collects element name only (no '/', '!', '?', attributes)

        for (int i = begin; i < end; i++) {
            final char c = xml.charAt(i);

            if (!inTag) {
                switch (c) {
                case '<': {
                    // Punctuation heuristic: if previous input was '>' and last printed is sentence
                    // punctuation, add a space.
                    if (lastInput == '>' && Char.isPUNsent(lastPrinted) && !dest.endsWith(' ')) {
                        dest.append(' ');
                        spacePending = false;
                        lastPrinted = ' ';
                    }
                    // Begin a tag: record markup and remember position in case we strip it.
                    dest.pushMark();
                    inTag = true;
                    recordName = true;
                    tagName.setLength(0);
                    sawLtSinceBegin = true;
                    dest.append('<');
                    break;
                }
                case '>': {
                    // We hit a '>' before any '<' → we started inside a broken tag; discard
                    // whatever we appended in this call.
                    if (!sawLtSinceBegin) {
                        // roll back to the method-level mark
                        while (dest.getMarkDepth() > baseDepth + 1) dest.rollbackMark(); // close any accidental inner
                                                                                         // marks
                        dest.rollbackMark(); // method-level mark
                        dest.pushMark(); // re-establish method-level mark for the remainder
                        // do not output this '>'
                        break;
                    }
                    // Otherwise, a stray '>' in text; treat as text.
                    if (spacePending && !dest.endsWith(' ')) {
                        dest.append(' ');
                        lastPrinted = ' ';
                    }
                    dest.append('>');
                    spacePending = false;
                    lastPrinted = '>';
                    break;
                }
                case ' ':
                case '\n':
                case '\r':
                case '\t': {
                    // collapse runs
                    spacePending = true;
                    break;
                }
                default: {
                    if (spacePending && !dest.endsWith(' ')) {
                        dest.append(' ');
                        lastPrinted = ' ';
                    }
                    dest.append(c);
                    spacePending = false;
                    lastPrinted = c;
                    break;
                }
                }
            } else {
                // inTag == true : we are copying markup and maybe recording the element name
                // prefix
                switch (c) {
                case '>': {
                    inTag = false;
                    recordName = false;
                    dest.append('>');
                    // Decide whether to keep or strip this tag's markup
                    boolean keep = false;
                    if (include != null && tagName.length() > 0) {
                        for (String tag: include) {
                            if (tag.contentEquals(tagName)) {
                                keep=true;
                                break;
                            }
                        }
                    }
                    if (keep) {
                        dest.commitMark();
                        lastPrinted = '>'; // last committed char is '>'
                    } else {
                        dest.rollbackMark(); // erase the markup we just appended
                        // after stripping a tag, we do not force a space; normalization is handled by
                        // text flow
                    }
                    break;
                }
                case ' ':
                case '\n':
                case '\r':
                case '\t': {
                    recordName = false; // element name ended before attributes
                    dest.append(c);
                    break;
                }
                case '/': {
                    // closing tag or self-closing tail; '/' is part of markup
                    dest.append('/');
                    // if '/' appears before any name char, it's a close token; do not record into
                    // tagName
                    recordName &= (tagName.length() > 0);
                    break;
                }
                default: {
                    if (recordName) {
                        // First character of name may not be '/', '!' or '?'
                        if (tagName.length() == 0 && (c == '/' || c == '!' || c == '?')) {
                            recordName = false; // special or closing; we won't keep such tags unless included
                                                // explicitly by that symbol (rare)
                        } else {
                            // Accept a conservative ASCII name charset: A-Z a-z 0-9 _ - :
                            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'
                                    || c == '-' || c == ':') {
                                tagName.append(c);
                            } else {
                                recordName = false; // name ended; remaining chars are attributes or other markup
                            }
                        }
                    }
                    dest.append(c);
                    break;
                }
                }
            }

            lastInput = c;
        }

        // If we ended while still in a tag, drop the unterminated markup.
        while (dest.getMarkDepth() > baseDepth + 1) {
            // there may be nested marks from nested tags; roll them back
            dest.rollbackMark();
        }
        if (dest.getMarkDepth() == baseDepth + 1) {
            // drop the last unfinished tag (if any)
            dest.rollbackMark();
        }

        // Commit the method-level mark.
        if (dest.getMarkDepth() != baseDepth) {
            dest.commitMark();
        }

        // Trim a trailing single space (normalize-space semantics).
        if (dest.length() > 0 && dest.lastIs(' ')) {
            dest.lastDel();
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
        if (cs == null) return "";
        final StringBuilder out = new StringBuilder(Math.max(16, cs.length()));
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '&') out.append("&amp;");
            else out.append(c);
        }
        return out.toString();
    }

    /**
     * Get the char from an HTML entity like &amp;gt; or &amp;#128;. Will not work
     * on supplementary char like &amp;Afr; 𝔄 &amp;x1d504; (3 bytes).
     * 
     * @param ent html entity.
     * @return unicode char.
     */
    public static char forChar(final String ent)
    {
        Character c = HTMLENT.get(ent);
        if (c == null) return 0;
        return c;
    }

    /**
     * See {@link #forChar(String)}, with a custom mutable String.
     * 
     * @param ent html entity.
     * @return unicode char.
     */
    public static char forChar(final Chain ent)
    {
        @SuppressWarnings("unlikely-arg-type")
        Character c = HTMLENT.get(ent);
        if (c == null) return 0;
        return c;
    }

    /**
     * Is it a char allowed in an entity code ?
     * 
     * @param c char to test.
     * @return true if ASCII and letter or digit, false otherwise.
     */
    public static boolean isInEnt(char c)
    {
        if (c > 128) {
            return false;
        }
        return Char.isLetterOrDigit(c);
    }

    /**
     * Tool to load entities from a json file.
     * 
     * @param path file path.
     * @throws UnsupportedEncodingException not an UTF-8 file.
     * @throws IOException                  file error.
     */
    static void load(final String path) throws UnsupportedEncodingException, IOException
    {
        String cont = new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
        Reader reader = new StringReader(cont);
        CSVReader csv = new CSVReader(reader, '\t', 2);
        csv.readRow(); // skip first line
        while (csv.readRow()) {
        		String key = csv.getCellAsString(0);
        		if (key.length() == 0) continue;
            if (key.charAt(0) == '#') continue; // comment
            StringBuilder value = csv.getCell(1);
            // Bad line, not a char, we should log here, but… where?
            if (value.length() != 1) continue;
            HTMLENT.put(key, value.charAt(0));
        }
    }

    /**
     * Used to build a concordance. From a random point in an xml file, prepend text
     * to a mutable destination charSequence, limit to an amount of words.
     * 
     * @param xml    source markup text.
     * @param offset position in xml from which prepend words or chars.
     * @param chain  destination chain.
     * @param words  amount of words to prepend.
     */
    public static void prependWords(final String xml, int offset, final Chain chain, final int words)
    {
        prepend(xml, offset, chain, -1, words);
    }

    /**
     * Used to build a concordance. From a random point in an xml file, prepend text
     * to a mutable destination charSequence, limit to an amount of chars.
     * 
     * @param xml    source markup text.
     * @param offset position in xml from which prepend words or chars.
     * @param chain  destination chain.
     * @param chars  amount of chars prepend.
     */
    public static void prependChars(final String xml, int offset, final Chain chain, final int chars)
    {
        prepend(xml, offset, chain, chars, -1);
    }

    /**
     * Used to build a concordance. From a random point in an xml file, prepend text
     * to a mutable destination charSequence, limit to an amount of chars, or words.
     * 
     * @param xml    source markup text.
     * @param offset position in xml from which prepend words or chars.
     * @param chain  destination chain.
     * @param chars  amount of chars prepend.
     * @param words  amount of words to prepend.
     */
    private static void prepend(final String xml, int offset, final Chain chain, int chars, int words)
    {
        // silently limit parameters
        if (words <= 0 && chars <= 0) chars = 50;
        else if (chars > KWIC_MAXCHARS) chars = KWIC_MAXCHARS;
        int cc = 0; // char count
        int wc = 0; // word count

        boolean gt = false, first = true, space = false, token = false;
        while (offset >= 0) {
            char c = xml.charAt(offset);
            switch (c) {
            case '>':
                space = false; // renew space flag
                first = false; // no broken tag at start
                gt = true;
                break;
            case '<':
                gt = false;
                // a broken tag, erase what was appended
                if (first) {
                    chain.firstDel(cc);
                    cc = 0;
                    wc = 0;
                    first = false;
                    break;
                }
                break;
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                if (gt) break; // space inside tag, skip
                if (space) break; // second or more space, skip
                chain.prepend(' ');
                space = true; // stop record space
                cc++;
                if (token) { // a token was started, stop it and count it
                    token = false;
                    wc++;
                }
                break;
            default:
                if (gt) break; // char in tag, skip
                space = false; // renew space flag
                chain.prepend(c);
                cc++;
                // word boundary?
                final boolean charIsTok = Char.isToken(c);
                if (!token && charIsTok) { // open a word
                    token = true;
                } else if (token && !charIsTok) { // close a word
                    token = false;
                    wc++;
                }
            }
            offset--;
            if (words <= 0); // no matter about words;
            else if (token) continue; // do not break inside a word
            else if (wc >= words) break; // we got enough words
            if (chars > 0 && cc >= chars) break; // chars limit reached
        }
        // if word, delete last car prepend?
    }

}
