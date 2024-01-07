/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.util;

/**
 * <p>
 * Efficient character categorizer, faster than Character.is*(), optimized for
 * tokenizer in latin scripts. Idea is to populate a big array of properties for
 * the code points.
 * </p>
 */
public class Char
{
    /** The 2 bytes unicode */
    static final int SIZE = 65536;
    /** Properties of chars by index */
    static final short[] CHARS = new short[SIZE];
    public static final short LETTER =      0b000000000000001;
    public static final short TOKEN =       0b000000000000010;
    public static final short SPACE =       0b000000000000100;
    public static final short PUNCTUATION = 0b000000000001000;
    public static final short LOWERCASE =   0b000000000010000;
    public static final short UPPERCASE =   0b000000000100000;
    public static final short VOWEL =       0b000000001000000;
    public static final short CONSONNANT =  0b000000010000000;
    public static final short DIGIT =       0b000000100000000;
    public static final short PUNsent =     0b000001000000000;
    public static final short PUNcl =       0b000010000000000;
    public static final short MATH =        0b000100000000000;
    public static final short LOWSUR =      0b010000000000000;
    public static final short HIGHSUR =     0b100000000000000;
    public static final short PUNCTUATION_OR_SPACE = SPACE | PUNCTUATION;
    public static final short LETTER_OR_DIGIT = LETTER | DIGIT;
    static {
        int type;
        // infinite loop when size = 65536, a char restart to 0
        int max = SIZE - 1;
        for (char c = 0; c < max; c++) {
            if (Character.isHighSurrogate(c)) {
                CHARS[c] = HIGHSUR;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                CHARS[c] = LOWSUR;
                continue;
            }
            type = Character.getType(c);
            short properties = 0x0;
            // DO NOT modify '<>' values

            // inside a word ?
            if (Character.isISOControl(c)) {
                properties |= SPACE; // \n, \r, \t…
            } else if (Character.isLetter(c)) {
                properties |= LETTER | TOKEN;
                if (Character.isUpperCase(c)) {
                    properties |= UPPERCASE;
                }
                if (Character.isLowerCase(c)) {
                    properties |= LOWERCASE;
                }
            } else if (Character.isDigit(c)) {
                properties |= DIGIT | TOKEN;
            } else if (type == Character.MATH_SYMBOL) {
                properties |= MATH;
            } else if (Character.isSpaceChar(c)) {
                properties |= SPACE; // Unicode classes, with unbreakable
            } else if (Character.isWhitespace(c)) {
                properties |= SPACE; // \n, \r, \t…
            } else {
                type = Character.getType(c);
                // & is considered as Po OTHER_PUNCTUATION by unicode
                if (c == '&') {
                    properties |= LETTER | TOKEN;
                } else if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                        || type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                        || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION
                        || type == Character.START_PUNCTUATION) {
                    properties |= PUNCTUATION;
                }
                // hacky, hyphen maybe part of compound word, or start of a separator like ---
                if (c == '-' || c == 0xAD || c == '\'' || c == '’' || c == '_') {
                    properties |= TOKEN;
                }
                if (c == '�' || c == '°')
                    properties |= LETTER | TOKEN;
                if ('.' == c || '…' == c || '?' == c || '!' == c)
                    properties |= PUNsent;
                else if (',' == c || ';' == c || ':' == c || '(' == c || ')' == c || '—' == c || '–' == c || '⁂' == c
                        || '»' == c || '«' == c)
                    properties |= PUNcl;
            }
            CHARS[c] = properties;
        }

    }
    /*
    private static final String ASCII_C0 = 
        "AAAAAAACEEEEIIII" +
        "DNOOOOO\u00d7\u00d8UUUUYI\u00df" +
        "aaaaaaaceeeeiiii" +
        "\u00f0nooooo\u00f7\u00f8uuuuy\u00fey" +
        "AaAaAaCcCcCcCcDd" +
        "DdEeEeEeEeEeGgGg" +
        "GgGgHhHhIiIiIiIi" +
        "IiJjJjKkkLlLlLlL" +
        "lLlNnNnNnnNnOoOo" +
        "OoOoRrRrRrSsSsSs" +
        "SsTtTtTtUuUuUuUu" +
        "UuUuWwYyYZzZzZzF"
    ; */
    private static final String ASCII_LOW_C0 = 
        "aaaaaaæceeeeiiii" +
        "ðnooooo×Øuuuuybß" +
        "aaaaaaæceeeeiiii" +
        "ðnooooo÷øuuuuyþy" +
        "aaaaaaccccccccdd" +
        "ddeeeeeeeeeegggg" +
        "gggghhhhiiiiiiii" +
        "iijjjjkkklllllll" +
        "lllnnnnnnnnnoooo" +
        "oooorrrrrrssssss" +
        "ssttttttuuuuuuuu" +
        "uuuuwwyyyzzzzzzf"
    ;
    
    /**
     * No constructor, only static methods.
     */
    private Char()
    {
        // Don't
    }

    /**
     * Is Numeric, like {@link Character#isDigit(char)}.
     */
    public static boolean isDigit(final char c)
    {
        return (CHARS[c] & DIGIT) != 0;
    }

    /**
     * Is the first short of a supplemental unicode codepoint.
     */
    public static boolean isHighSurrogate(final char c)
    {
        return (CHARS[c] & HIGHSUR) != 0;
    }

    /**
     * Is a letter {@link Character#isLetter(char)}.
     */
    public static boolean isLetter(final char c)
    {
        return (CHARS[c] & LETTER) != 0;
    }

    /**
     * Is a letter or a digit, like {@link Character#isLetterOrDigit(char)}.
     */
    public static boolean isLetterOrDigit(final char c)
    {
        return (CHARS[c] & LETTER_OR_DIGIT) != 0;
    }

    /**
     * Is a lower case letter, like {@link Character#isLowerCase(char)}.
     */
    public static boolean isLowerCase(final char c)
    {
        return (CHARS[c] & LOWERCASE) != 0;
    }

    /**
     * Is the second short of a supplemental unicode codepoint.
     */
    public static boolean isLowSurrogate(final char c)
    {
        return (CHARS[c] & LOWSUR) != 0;
    }

    /**
     * Is a Mathematic symbol.
     */
    public static boolean isMath(final char c)
    {
        return (CHARS[c] & MATH) != 0;
    }

    /**
     * Is a punctuation mark between words.
     */
    public static boolean isPunctuation(final char c)
    {
        return (CHARS[c] & PUNCTUATION) != 0;
    }

    /**
     * Is punctuation or space (maybe a lexical token)
     */
    public static boolean isPunctuationOrSpace(final char c)
    {
        return (CHARS[c] & PUNCTUATION_OR_SPACE) != 0;
    }

    /**
     * Is a punctuation mark of sentence break level (!?. etc.)
     */
    public static boolean isPUNsent(final char c)
    {
        return (CHARS[c] & PUNsent) != 0;
    }

    /**
     * Is a punctuation mark of clause level (insisde a sentence) (,;: etc.)
     */
    public static boolean isPUNcl(final char c)
    {
        return (CHARS[c] & PUNcl) != 0;
    }

    /**
     * Is a "whitespace" according to ISO (space, tabs, new lines) and also for
     * Unicode (non breakable spoaces), {@link Character#isSpaceChar(char)},
     * {@link Character#isWhitespace(char)}.
     */
    public static boolean isSpace(final char c)
    {
        return (CHARS[c] & SPACE) != 0;
    }

    /**
     * Is a word character, letter, but also, '’-_ and some other tweaks for lexical
     * parsing.
     */
    public static boolean isToken(final char c)
    {
        return (CHARS[c] & TOKEN) != 0;
    }

    /**
     * Is an upper case letter, like {@link Character#isUpperCase(char)}.
     */
    public static boolean isUpperCase(final char c)
    {
        return (CHARS[c] & UPPERCASE) != 0;
    }

    /**
     * Get the internal properties for a char.
     */
    public static short props(final char c)
    {
        return CHARS[c];
    }

    /**
     * Efficient lower casing (test if {@link #isUpperCase(char)} before).
     */
    public static char toLower(char c)
    {
        if (!Char.isUpperCase(c))
            return c;
        return Character.toLowerCase(c);
    }

    /**
     * Lower casing a string builder.
     */
    public static StringBuilder toLower(StringBuilder s)
    {
        int max = s.length();
        char c;
        for (int i = 0; i < max; i++) {
            c = s.charAt(i);
            if (!Char.isUpperCase(c))
                continue;
            // 1003 chars in multiple blocks accept a lower case
            // a hash of chars is not faster
            s.setCharAt(i, Character.toLowerCase(c));
        }
        // x6 new StringBuilder( s.toString().toLowerCase() )
        return s;
    }
    
    /**
     * Return a lower case version of a String, keeping exactly same char size
     * (will keep æ → æ, Œ → œ…)
     * 
     * @param chain A mutable String.
     * @return The same object, modified.
     */
    public static Chain toLowASCII(Chain chain)
    {
        char c;
        for (int i = 0, len = chain.length(); i < len; i++) {
            c = chain.charAt(i);
            if (c >= '\u00c0' && c <= '\u017f') {
                chain.setCharAt(i, ASCII_LOW_C0.charAt((int) c - '\u00c0'));
            }
            // caps
            else if (c >= '\u0041' && c <= '\u005A') {
                chain.setCharAt(i, (char)(c + 32));
            }
        }
        return chain;
    }

    public static String toLowASCII(CharSequence src)
    {
        StringBuilder dst = new StringBuilder();
        char c;
        for (int i = 0, len = src.length(); i < len; i++) {
            c = src.charAt(i);
            if (c >= '\u00c0' && c <= '\u017f') {
                c = ASCII_LOW_C0.charAt((int) c - '\u00c0');
            }
            // caps
            else if (c >= '\u0041' && c <= '\u005A') {
                c = (char)(c + 32);
            }
            dst.append(c);
        }
        return dst.toString();
    }

    /*
    public static String toLowASCII(String source) {
        char[] chars = source.toCharArray();
        toLowASCII(chars, 0, source.length());
        return new String(chars);
    }
    */

    
    /**
     * Deligature 
     * @param source
     * @return
     */
    public static Chain deligat(final Chain source) {
        for (int i = 0; i < source.length(); i++) { // length dynamic
            char c = source.charAt(i);
            switch(c) {
                case 'Æ':
                    source.setCharAt(i, 'A');
                    source.insert(i+1, "E");
                    break;
                case 'æ':
                    source.setCharAt(i, 'a');
                    source.insert(i+1, "e");
                    break;
                case 'Œ':
                    source.setCharAt(i, 'O');
                    source.insert(i+1, "E");
                    break;
                case 'œ':
                    source.setCharAt(i, 'o');
                    source.insert(i+1, "e");
                    break;
            }
        }
        return source;
    }

    /**
     * Efficient upper casing (test if {@link #isLowerCase(char)} before).
     */
    public static char toUpper(char c)
    {
        if (!Char.isLowerCase(c))
            return c;
        return Character.toUpperCase(c);
    }

    /**
     * Give human informations about a char.
     */
    static public String toString(char c)
    {
        short props = CHARS[c];
        StringBuilder sb = new StringBuilder();
        sb.append(c).append("\t");
        if ((props & TOKEN) != 0)
            sb.append("TOKEN ");
        if ((props & LETTER) != 0)
            sb.append("LETTER ");
        if ((props & SPACE) != 0)
            sb.append("SPACE ");
        if ((props & PUNCTUATION) != 0)
            sb.append("PUNCTUATION ");
        if ((props & PUNsent) != 0)
            sb.append("PUNsent ");
        if ((props & PUNcl) != 0)
            sb.append("PUNcl ");
        if ((props & DIGIT) != 0)
            sb.append("DIGIT ");
        if ((props & LOWERCASE) != 0)
            sb.append("LOWERCASE ");
        if ((props & UPPERCASE) != 0)
            sb.append("UPPERCASE ");
        if ((props & MATH) != 0)
            sb.append("MATH ");
        if ((props & HIGHSUR) != 0)
            sb.append("HIGHSUR ");
        if ((props & LOWSUR) != 0)
            sb.append("LOWSUR ");
        sb.append(Character.getName(c).toLowerCase()).append("\t");
        return sb.toString();
    }

}
