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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.github.oeuvres.alix.util.CSVReader.Row;


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
    static {
        BufferedReader buf = new BufferedReader(
                new InputStreamReader(Char.class.getResourceAsStream("htmlent.csv"), StandardCharsets.UTF_8));
        String l;
        String ent;
        Character c;
        int pos;
        try {
            buf.readLine(); // first line is labels
            while ((l = buf.readLine()) != null) {
                l = l.trim();
                if (l.charAt(0) == '#')
                    continue;
                pos = l.indexOf(' ');
                if (pos < 3)
                    continue;
                if (pos + 1 >= l.length())
                    continue;
                ent = l.substring(0, pos);
                c = l.charAt(pos + 1);
                HTMLENT.put(ent, c);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Avoid instantiation, use static methods instead.
     */
    private ML()
    {
        
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
        if (c == null)
            return 0;
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
        if (c == null)
            return 0;
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
     * @param xml span of text with tags.
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
     * Return a normalize-space() text version of an xml excerpt (possibly with
     * broken tags). Chain could be reused here for performances.
     * 
     * @param xml span of text with tags.
     * @param begin start index in xml.
     * @param end end index in xml.
     * @param dest destination {@link CharSequence}.
     * @param include set of tags allowed.
     */
    @SuppressWarnings("unlikely-arg-type")
    public static void detag(final String xml, int begin, int end, Chain dest, Set<String> include)
    {
        // silently fails if bad params
        if (begin < 0 || end < 0) {
            return;
        }
        if (end > xml.length()) {
            end = xml.length();
        }
        if (begin > end) {
            return;
        }
        boolean start = true; // before first tag (avoid broken tag)
        // boolean lt = false; // tag is started
        // tag name to test
        Chain tagName = new Chain();
        boolean tagRecord = false;
        int tagLength = -1; // if we need to erase tag
        boolean space = false; // a space have been sent

        char lastPrint = ' ';
        char lastChar = ' ';
        for (int i = begin; i < end; i++) {
            char c = xml.charAt(i);
            switch (c) {
            case '<':
                space = false; // renew space flag
                start = false; // no more broken tag at start
                // pb with bad indent html
                // tique.</p><p class="p">Ains
                if (lastChar == '>' && Char.isPUNsent(lastPrint)) {
                    lastPrint = ' ';
                    dest.append(' ');
                    space = true;
                }
                tagLength = dest.length(); // record position if we want to go back
                tagName.reset(); // prepare the testing tag
                tagRecord = true;
                dest.append(c);
                break;
            case '>':
                tagRecord = false;
                // a broken tag at start, erase what was appended
                if (start) {
                    dest.reset();
                    start = false;
                    break;
                }
                dest.append(c);
                // no tag tag include, erase recorded tag
                if (include == null || !include.contains(tagName)) {
                    dest.setLength(tagLength);
                }
                tagLength = -1; // reset, nothing more <tag> to strip
                break;
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                // always end of tagName
                tagRecord = false;
                // record space in tag
                if (space)
                    break; // second or more space, skip
                space = true; // stop record space
                dest.append(' ');
                lastPrint = ' ';
                break;
            case '/':
                // closing tag ?
                dest.append(c);
                break;
            default:
                if (tagRecord) { // record tagName
                    tagName.append(c);
                }
                space = false; // renew space flag
                dest.append(c);
                lastPrint = c;
            }
            lastChar = c;
        }
        // last unclose tag, strip
        if (tagLength >= 0) dest.setLength(tagLength);
    }

    /**
     * Used to build a concordance. 
     * From a random point in an xml file, append text to a mutable destination charSequence,
     * limit to an amount of words.
     * 
     * @param xml source markup text.
     * @param offset position in xml from which append words or chars.
     * @param chain destination chain.
     * @param words amount of words to append.
     */
    public static void appendWords(final String xml, int offset, final Chain chain, final int words)
    {
        append(xml, offset, chain, -1, words);
    }

    /**
     * Used to build a concordance. 
     * From a random point in an xml file, append text to a mutable destination charSequence,
     * limit to an amount of chars.
     * 
     * @param xml source markup text.
     * @param offset position in xml from which append words or chars.
     * @param chain destination chain.
     * @param chars amount of chars to append.
     */
    public static void appendChars(final String xml, int offset, final Chain chain, final int chars)
    {
        append(xml, offset, chain, chars, -1);
    }

    /**
     * Used to build a concordance. 
     * From a random point in an xml file, append text to a mutable destination charSequence,
     * limit to an amount of chars, or words.
     * 
     * @param xml source markup text.
     * @param offset position in xml from which append words or chars.
     * @param chain destination chain.
     * @param chars amount of chars append.
     * @param words amount of words to append.
     */
    private static void append(final String xml, int offset, final Chain chain, int chars, int words)
    {
        // silently limit parameters
        if (words <= 0 && chars <= 0)
            chars = 50;
        else if (chars > KWIC_MAXCHARS)
            chars = KWIC_MAXCHARS;
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
                if (lt)
                    break; // space inside tag, skip
                if (space)
                    break; // second or more space, skip
                space = true; // stop record space after this one
                chain.append(' ');
                cc++;
                if (token) { // a token was started, stop it and count it
                    token = false;
                    wc++;
                }
                break;
            default:
                if (lt)
                    break; // char in tag, skip
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
            if (words <= 0)
                ; // no matter about words;
            else if (token)
                continue; // do not break inside a word
            else if (wc >= words)
                break; // we got enough words
            if (chars > 0 && cc >= chars)
                break; // chars limit reached
        }
        // ? delete last char for words ?
    }

    /**
     * Used to build a concordance. 
     * From a random point in an xml file, prepend text to a mutable destination charSequence,
     * limit to an amount of words.
     * 
     * @param xml source markup text.
     * @param offset position in xml from which prepend words or chars.
     * @param chain destination chain.
     * @param words amount of words to prepend.
     */
    public static void prependWords(final String xml, int offset, final Chain chain, final int words)
    {
        prepend(xml, offset, chain, -1, words);
    }

    /**
     * Used to build a concordance. 
     * From a random point in an xml file, prepend text to a mutable destination charSequence,
     * limit to an amount of chars.
     * 
     * @param xml source markup text.
     * @param offset position in xml from which prepend words or chars.
     * @param chain destination chain.
     * @param chars amount of chars prepend.
     */
    public static void prependChars(final String xml, int offset, final Chain chain, final int chars)
    {
        prepend(xml, offset, chain, chars, -1);
    }

    /**
     * Used to build a concordance. 
     * From a random point in an xml file, prepend text to a mutable destination charSequence,
     * limit to an amount of chars, or words.
     * 
     * @param xml source markup text.
     * @param offset position in xml from which prepend words or chars.
     * @param chain destination chain.
     * @param chars amount of chars prepend.
     * @param words amount of words to prepend.
     */
    private static void prepend(final String xml, int offset, final Chain chain, int chars, int words)
    {
        // silently limit parameters
        if (words <= 0 && chars <= 0)
            chars = 50;
        else if (chars > KWIC_MAXCHARS)
            chars = KWIC_MAXCHARS;
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
                if (gt)
                    break; // space inside tag, skip
                if (space)
                    break; // second or more space, skip
                chain.prepend(' ');
                space = true; // stop record space
                cc++;
                if (token) { // a token was started, stop it and count it
                    token = false;
                    wc++;
                }
                break;
            default:
                if (gt)
                    break; // char in tag, skip
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
            if (words <= 0)
                ; // no matter about words;
            else if (token)
                continue; // do not break inside a word
            else if (wc >= words)
                break; // we got enough words
            if (chars > 0 && cc >= chars)
                break; // chars limit reached
        }
        // if word, delete last car prepend?
    }

    /**
     * Tool to load entities from a json file.
     * 
     * @param path file path.
     * @throws UnsupportedEncodingException not an UTF-8 file.
     * @throws IOException file error.
     */
    static void load(final String path) throws UnsupportedEncodingException, IOException
    {
        String cont = new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
        Reader reader = new StringReader(cont);
        CSVReader csv = new CSVReader(reader, 2, '\t');
        csv.readRow(); // skip first line
        CSVReader.Row row;
        while ((row = csv.readRow()) != null) {
            Chain ent = row.get(0);
            if (ent.first() == '#') continue;
            if (ent.last() == ';') continue;
            Chain achar = row.get(1);
            // we should log here, but… where?
            if (achar.length() != 1) {
                System.out.println("Too much chars for, "+ ent+ ":" + achar);
                continue;
            }
            HTMLENT.put(ent.toString(), achar.charAt(0));
        }
    }

}
