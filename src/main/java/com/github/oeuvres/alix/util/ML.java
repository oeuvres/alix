package com.github.oeuvres.alix.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.json.JSONArray;
import org.json.JSONObject;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;

/**
 * Some useful tools to deal with “Markup Languages” (xml, but also html tag
 * soup)
 */
public class ML
{
    private static int KWIC_MAXCHARS = 500;
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
     * Get the char from an HTML entity like &amp;gt; or &amp;#128;. Will not work
     * on supplementary char like &amp;Afr; 𝔄 &amp;x1d504; (3 bytes).
     * 
     * @param ent
     * @return
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
     * @param ent
     * @return
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
     * See {@link #forChar(String)}, with a custom implementation of lucene
     * {@link CharTermAttribute} sharing the same hash fuction as a String.
     * 
     * @param ent
     * @return
     */
    public static char forChar(final CharsAtt ent)
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
     * @param c
     * @return
     */
    public static boolean isInEnt(char c)
    {
        if (c > 128) {
            return false;
        }
        return Char.isLetterOrDigit(c);
    }

    public static String detag(final String xml)
    {
        if (xml == null || xml.isEmpty()) {
            return "";
        }
        return detag(xml, 0, xml.length());
    }

    /**
     * Return a normalize-space() text version of an xml excerpt (possibly broken).
     */
    public static String detag(final String xml, final int begin, final int end)
    {
        if (xml == null || xml.isEmpty())
            return "";
        Chain dest = new Chain();
        detag(xml, begin, end, dest);
        return dest.toString();
    }

    /**
     * Return a normalize-space() text version of an xml excerpt (possibly with
     * broken tags).
     */
    public static void detag(final String xml, int begin, int end, Chain dest)
    {
        // TODO, keeep tags
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
        // Chain tag = new Chain();
        boolean start = true; // before first tag (avoid broken tag)
        boolean lt = false; // tag is started
        // boolean closing = false; // closing tag </…>
        boolean space = false; // a space have been sent

        char lastPrint = ' ';
        char lastChar = ' ';
        for (int i = begin; i < end; i++) {
            char c = xml.charAt(i);
            switch (c) {
            case '<':
                space = false; // renew space flag
                start = false; // no broken tag at start
                lt = true;
                // pb with bad indent html
                // tique.</p><p class="p">Ains
                if (lastChar == '>' && Char.isPUNsent(lastPrint)) {
                    lastPrint = ' ';
                    dest.append(' ');
                }
                break;
            case '>':
                lt = false;
                // a broken tag at start, erase what was appended
                if (start) {
                    dest.reset();
                    start = false;
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
                space = true; // stop record space
                dest.append(' ');
                lastPrint = ' ';
                break;
            default:
                if (lt)
                    break; // char in tag, skip
                space = false; // renew space flag
                dest.append(c);
                lastPrint = c;
            }
            lastChar = c;
        }
    }

    public static void appendWords(final String xml, int offset, final Chain chain, final int words)
    {
        append(xml, offset, chain, -1, words);
    }

    public static void appendChars(final String xml, int offset, final Chain chain, final int chars)
    {
        append(xml, offset, chain, chars, -1);
    }

    /**
     * From a random point in an xml file, append text (with possibly broken tag),
     * limit to an amount of chars, or words.
     * 
     * @param xml
     */
    public static void append(final String xml, int offset, final Chain chain, int chars, int words)
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

    public static void prependWords(final String xml, int offset, final Chain chain, final int words)
    {
        prepend(xml, offset, chain, -1, words);
    }

    public static void prependChars(final String xml, int offset, final Chain chain, final int chars)
    {
        prepend(xml, offset, chain, chars, -1);
    }

    /**
     * Provide a text version of an xml excerpt (possibly broken).
     * 
     * @param xml
     */
    public static void prepend(final String xml, int offset, final Chain chain, int chars, int words)
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
     * Tool to load entities from a json file
     * 
     * @param path
     * @throws UnsupportedEncodingException
     * @throws IOException                  Lucene errors.
     */
    static void load(final String path) throws UnsupportedEncodingException, IOException
    {
        String cont = new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
        JSONObject json = new JSONObject(cont);
        List<String> keys = new ArrayList<String>(json.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            if (!key.endsWith(";"))
                continue;
            JSONObject entry = json.getJSONObject(key);
            String value = entry.getString("characters");
            if ("\n".equals(value))
                value = "\\n";
            if ("\t".equals(value))
                value = "\\t";
            // if (value.length() == 1) continue;
            System.out.println(key + " " + value);
            JSONArray points = entry.getJSONArray("codepoints");
            for (int i = 0; i < points.length(); i++) {
                int code = points.getInt(i);
                System.out.println("&" + code + ";" + " " + value);
                if (code < 16)
                    System.out.println("&x" + String.format("%01x", code) + ";" + " " + value);
                if (code < 256)
                    System.out.println("&x" + String.format("%02x", code) + ";" + " " + value);
                // if (code < 4096) System.out.println("&x"+String.format("%03x", code)+";"+"
                // "+value);
                if (code < 65536)
                    System.out.println("&x" + String.format("%04x", code) + ";" + " " + value);
                if (code > 65535)
                    System.out.println("&x" + String.format("%x", code) + ";" + " " + value);
            }

        }
    }

}
