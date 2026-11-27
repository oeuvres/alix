/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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

import java.text.Normalizer;
import java.util.Objects;

/**
 * Precomputed character properties for UTF-16 tokenization.
 *
 * <p>The table covers all 65,536 UTF-16 code units, not supplementary Unicode
 * code points. Surrogate halves are classified separately. Unicode properties
 * and Alix-specific tokenization policies occupy independent bits.</p>
 *
 * <p>Each hot-path test is an array read and a bit test. Property ordinals
 * determine bit positions; masks are internal and must not be persisted.</p>
 */
public final class Char
{
    /** Independent character properties. */
    public enum Prop
    {
        /** Unicode letter. */
        LETTER,
        /** Unicode decimal digit. */
        DIGIT,
        /** Unicode lowercase character. */
        LOWERCASE,
        /** Unicode uppercase character. */
        UPPERCASE,
        /** Unicode titlecase character. */
        TITLECASE,
        /** Letter of Latin script. */
        LATIN,
        /** Unicode combining mark, including enclosing marks. */
        MARK,
        /** Unicode punctuation. */
        PUNCTUATION,
        /** Unicode mathematical symbol (Sm). */
        MATH,
        /** Low UTF-16 surrogate. */
        LOW_SURROGATE,
        /** High UTF-16 surrogate. */
        HIGH_SURROGATE,
        /** Whitespace or ISO control treated as a separator. */
        SPACE,
        /** Character admitted inside a word-like token. */
        TOKEN,
        /** Sentence punctuation in Alix. */
        PUNCT_SENTENCE,
        /** Clause punctuation in Alix. */
        PUNCT_CLAUSE,
        /** Mathematical symbol emitted separately by MarkupTokenizer. */
        MATH_TOKEN,
        /** Currency, percentage or degree symbol. */
        UNIT_SYMBOL,
        /** Unicode vulgar fraction accepted by the numerical tokenizer. */
        VULGAR_FRACTION,
        /** Hyphen or potential minus sign; interpretation is contextual. */
        MINUS_SIGN,
        /** Horizontal spacing used around guillemets. */
        HORIZONTAL_SPACE,
        /** Potential numeric grouping space. */
        THOUSANDS_SEPARATOR,
        /** CR or LF. */
        LINE_BREAK;

        /** Bit assigned to this property. */
        public final int mask = 1 << ordinal();
    }

    /** All UTF-16 code units, including U+FFFF. */
    private static final int[] CHARS = new int[Character.MAX_VALUE + 1];
    /** Cached property vocabulary for diagnostic output. */
    private static final Prop[] PROPS = Prop.values();
    /** Combined Unicode letter and decimal digit mask. */
    private static final int LETTER_OR_DIGIT = Prop.LETTER.mask | Prop.DIGIT.mask;
    /** Combined uppercase and titlecase mask. */
    private static final int UPPER_OR_TITLE = Prop.UPPERCASE.mask | Prop.TITLECASE.mask;
    /** Combined punctuation and separator mask. */
    private static final int PUNCTUATION_OR_SPACE = Prop.PUNCTUATION.mask | Prop.SPACE.mask;
    /** Combined tokenizer symbol mask. */
    private static final int NUMERIC_SYMBOL = Prop.MATH_TOKEN.mask | Prop.UNIT_SYMBOL.mask;

    static {
        if (PROPS.length > Integer.SIZE) {
            throw new IllegalStateException("Too many character properties for int masks");
        }
        for (int i = 0; i < CHARS.length; i++) {
            final char c = (char) i;
            final int type = Character.getType(c);
            int flags = 0;
            if (Character.isLetter(c)) {
                flags |= Prop.LETTER.mask | Prop.TOKEN.mask;
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) {
                    flags |= Prop.LATIN.mask;
                }
            }
            if (Character.isDigit(c)) flags |= Prop.DIGIT.mask | Prop.TOKEN.mask;
            if (Character.isLowerCase(c)) flags |= Prop.LOWERCASE.mask;
            if (Character.isUpperCase(c)) flags |= Prop.UPPERCASE.mask;
            if (Character.isTitleCase(c)) flags |= Prop.TITLECASE.mask;
            if (Character.isHighSurrogate(c)) flags |= Prop.HIGH_SURROGATE.mask;
            if (Character.isLowSurrogate(c)) flags |= Prop.LOW_SURROGATE.mask;
            if (Character.isISOControl(c) || Character.isSpaceChar(c)
                    || Character.isWhitespace(c)) flags |= Prop.SPACE.mask;

            switch (type) {
                case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                     Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                     Character.INITIAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                     Character.START_PUNCTUATION -> flags |= Prop.PUNCTUATION.mask;
                case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK ->
                    flags |= Prop.MARK.mask | Prop.TOKEN.mask;
                case Character.ENCLOSING_MARK -> flags |= Prop.MARK.mask;
                case Character.MATH_SYMBOL -> flags |= Prop.MATH.mask;
                case Character.CURRENCY_SYMBOL -> flags |= Prop.UNIT_SYMBOL.mask;
                case Character.LETTER_NUMBER, Character.OTHER_NUMBER -> flags |= Prop.TOKEN.mask;
                default -> { }
            }

            // Alix lexical policy, independent of Unicode general categories.
            if ("&-\u00AD'’_\u2010\u2011�".indexOf(c) >= 0) flags |= Prop.TOKEN.mask;
            if (".…?!".indexOf(c) >= 0) flags |= Prop.PUNCT_SENTENCE.mask;
            if (",;:()—–\"«»".indexOf(c) >= 0) flags |= Prop.PUNCT_CLAUSE.mask;
            if ("+×÷=≠≤≥±∓√∑∏∞∫*/⁄<>^".indexOf(c) >= 0) flags |= Prop.MATH_TOKEN.mask;
            if ("%‰‱°".indexOf(c) >= 0) flags |= Prop.UNIT_SYMBOL.mask;
            if ("-−\u2010\u2011\u00AD".indexOf(c) >= 0) flags |= Prop.MINUS_SIGN.mask;
            if (" \t\u00A0\u202F".indexOf(c) >= 0) flags |= Prop.HORIZONTAL_SPACE.mask;
            if ("\u00A0\u202F\u2009".indexOf(c) >= 0) flags |= Prop.THOUSANDS_SEPARATOR.mask;
            if (c == '\n' || c == '\r') flags |= Prop.LINE_BREAK.mask;
            if (c == 0x00BC || c == 0x00BD || c == 0x00BE
                    || (c >= 0x2150 && c <= 0x215E) || c == 0x2189) {
                flags |= Prop.VULGAR_FRACTION.mask;
            }
            if ((flags & (NUMERIC_SYMBOL | Prop.VULGAR_FRACTION.mask)) != 0) {
                flags &= ~Prop.TOKEN.mask;
            }
            CHARS[i] = flags;
        }
    }

    /** Prevents construction of this static utility class. */
    private Char() { }

    /**
     * Tests for a Unicode decimal digit.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isDigit(final char c)
    {
        return (CHARS[c] & Prop.DIGIT.mask) != 0;
    }

    /**
     * Tests for a Unicode decimal digit, accepting a UTF-16 reader result.
     * @param c UTF-16 code unit or EOF (-1)
     * @return true if the property is present; false outside the BMP
     */
    public static boolean isDigit(final int c)
    {
        return (c >>> 16) == 0 && isDigit((char) c);
    }

    /**
     * Tests for a high surrogate.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isHighSurrogate(final char c)
    {
        return (CHARS[c] & Prop.HIGH_SURROGATE.mask) != 0;
    }

    /**
     * Tests for horizontal quotation spacing.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isHorizontalSpace(final char c)
    {
        return (CHARS[c] & Prop.HORIZONTAL_SPACE.mask) != 0;
    }

    /**
     * Tests for a letter of Latin script.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isLatin(final char c)
    {
        return (CHARS[c] & Prop.LATIN.mask) != 0;
    }

    /**
     * Tests for a Unicode letter.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isLetter(final char c)
    {
        return (CHARS[c] & Prop.LETTER.mask) != 0;
    }

    /**
     * Tests for a Unicode letter or decimal digit.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isLetterOrDigit(final char c)
    {
        return (CHARS[c] & LETTER_OR_DIGIT) != 0;
    }

    /**
     * Tests for a Unicode letter or decimal digit, accepting a UTF-16 reader result.
     * @param c UTF-16 code unit or EOF (-1)
     * @return true if the property is present; false outside the BMP
     */
    public static boolean isLetterOrDigit(final int c)
    {
        return (c >>> 16) == 0 && isLetterOrDigit((char) c);
    }

    /**
     * Tests for CR or LF.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isLineBreak(final char c)
    {
        return (CHARS[c] & Prop.LINE_BREAK.mask) != 0;
    }

    /**
     * Tests for CR or LF, accepting a UTF-16 reader result.
     * @param c UTF-16 code unit or EOF (-1)
     * @return true if the property is present; false outside the BMP
     */
    public static boolean isLineBreak(final int c)
    {
        return (c >>> 16) == 0 && isLineBreak((char) c);
    }

    /**
     * Tests for a low surrogate.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isLowSurrogate(final char c)
    {
        return (CHARS[c] & Prop.LOW_SURROGATE.mask) != 0;
    }

    /**
     * Tests for a Unicode lowercase character.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isLowerCase(final char c)
    {
        return (CHARS[c] & Prop.LOWERCASE.mask) != 0;
    }

    /**
     * Tests for a Unicode combining mark.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isMark(final char c)
    {
        return (CHARS[c] & Prop.MARK.mask) != 0;
    }

    /**
     * Tests for a Unicode mathematical symbol (Sm).
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isMath(final char c)
    {
        return (CHARS[c] & Prop.MATH.mask) != 0;
    }

    /**
     * Tests for a mathematical symbol emitted separately by the tokenizer.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isMathToken(final char c)
    {
        return (CHARS[c] & Prop.MATH_TOKEN.mask) != 0;
    }

    /**
     * Tests for a potential minus or hyphen.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isMinusSign(final char c)
    {
        return (CHARS[c] & Prop.MINUS_SIGN.mask) != 0;
    }

    /**
     * Tests for a separate mathematical or unit symbol.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isNumericSymbol(final char c)
    {
        return (CHARS[c] & NUMERIC_SYMBOL) != 0;
    }

    /**
     * Tests for Alix clause punctuation.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isPUNcl(final char c)
    {
        return (CHARS[c] & Prop.PUNCT_CLAUSE.mask) != 0;
    }

    /**
     * Tests for Alix sentence punctuation.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isPUNsent(final char c)
    {
        return (CHARS[c] & Prop.PUNCT_SENTENCE.mask) != 0;
    }

    /**
     * Tests for a Unicode punctuation character.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isPunctuation(final char c)
    {
        return (CHARS[c] & Prop.PUNCTUATION.mask) != 0;
    }

    /**
     * Tests for Unicode punctuation or an Alix separator.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isPunctuationOrSpace(final char c)
    {
        return (CHARS[c] & PUNCTUATION_OR_SPACE) != 0;
    }

    /**
     * Tests for whitespace or an ISO control separator.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isSpace(final char c)
    {
        return (CHARS[c] & Prop.SPACE.mask) != 0;
    }

    /**
     * Tests for a potential numeric grouping space.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isThousandsSeparator(final char c)
    {
        return (CHARS[c] & Prop.THOUSANDS_SEPARATOR.mask) != 0;
    }

    /**
     * Tests for a Unicode titlecase character.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isTitleCase(final char c)
    {
        return (CHARS[c] & Prop.TITLECASE.mask) != 0;
    }

    /**
     * Tests for admissible inside an Alix word-like token.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isToken(final char c)
    {
        return (CHARS[c] & Prop.TOKEN.mask) != 0;
    }

    /**
     * Tests for a currency, percentage or degree symbol.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isUnitSymbol(final char c)
    {
        return (CHARS[c] & Prop.UNIT_SYMBOL.mask) != 0;
    }

    /**
     * Tests for a Unicode uppercase character.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isUpperCase(final char c)
    {
        return (CHARS[c] & Prop.UPPERCASE.mask) != 0;
    }

    /**
     * Tests for a Unicode uppercase or titlecase character.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isUpperOrTitleCase(final char c)
    {
        return (CHARS[c] & UPPER_OR_TITLE) != 0;
    }

    /**
     * Tests for a supported vulgar fraction.
     * @param c UTF-16 code unit
     * @return true if the property is present
     */
    public static boolean isVulgarFraction(final char c)
    {
        return (CHARS[c] & Prop.VULGAR_FRACTION.mask) != 0;
    }

    /**
     * Tests for a supported vulgar fraction, accepting a UTF-16 reader result.
     * @param c UTF-16 code unit or EOF (-1)
     * @return true if the property is present; false outside the BMP
     */
    public static boolean isVulgarFraction(final int c)
    {
        return (c >>> 16) == 0 && isVulgarFraction((char) c);
    }

    /**
     * Returns all properties in one lookup for callers testing several masks.
     * @param c UTF-16 code unit
     * @return internal bit mask of properties
     */
    public static int props(final char c)
    {
        return CHARS[c];
    }

    /**
     * Trim characters from the right of a mutable string.
     *
     * @param sb         mutable text to trim (modified in place)
     * @param stripChars characters to remove from the right edge; if null or
     *                   empty, nothing is done
     * @throws NullPointerException if {@code sb} is null
     */
    public static void rtrim(final StringBuilder sb, final String stripChars)
    {
        Objects.requireNonNull(sb, "sb");
        if (stripChars == null || stripChars.isEmpty())
            return;
        
        int len = sb.length();
        while (len > 0) {
            final char c = sb.charAt(len - 1);
            if (stripChars.indexOf(c) < 0)
                break;
            len--;
        }
        sb.setLength(len);
    }

    /**
     * Applies compatibility decomposition, removes combining marks and lowercases.
     * <p>
     * Unicode NFKD decomposition, combining-mark removal, lower-casing.
     * Handles diacritics ({@code é → e}) and fullwidth forms ({@code Ａ → a}).
     * This is not a general ASCII transliterator: characters such as œ remain.
     * </p>
     *
     * @param s input string
     * @return folded lower-case string, may be longer than input
     */
    public static String toAscii(final String s)
    {
        final String nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD);
        final StringBuilder sb = new StringBuilder(nfkd.length());
        for (int i = 0; i < nfkd.length(); i++) {
            final char c = nfkd.charAt(i);
            if (isMark(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
    
    /**
     * Lowercases an uppercase or titlecase UTF-16 code unit.
     * 
     * @param c char to transform.
     * @return char to lower case.
     */
    public static char toLower(char c)
    {
        if (!Char.isUpperOrTitleCase(c))
            return c;
        return Character.toLowerCase(c);
    }

    /**
     * Lower casing a mutable string.
     * 
     * @param s the char sequence.
     * @return the modified char sequence.
     */
    public static StringBuilder toLower(StringBuilder s)
    {
        int max = s.length();
        char c;
        for (int i = 0; i < max; i++) {
            c = s.charAt(i);
            if (!Char.isUpperOrTitleCase(c))
                continue;
            s.setCharAt(i, Character.toLowerCase(c));
        }
        return s;
    }


    /**
     * Returns the character, its property names and its Unicode name.
     * @param c UTF-16 code unit
     * @return diagnostic description, including unassigned characters
     */
    public static String toString(final char c)
    {
        final int flags = CHARS[c];
        final StringBuilder sb = new StringBuilder();
        sb.append(c).append('\t');
        for (final Prop prop : PROPS) {
            if ((flags & prop.mask) != 0) sb.append(prop.name()).append(' ');
        }
        return sb.append(Character.getName(c)).append('\t').toString();
    }

    /**
     * Uppercases a lowercase or titlecase UTF-16 code unit.
     * 
     * @param c char to convert.
     * @return converted char.
     */
    public static char toUpper(char c)
    {
        if (!Char.isLowerCase(c) && !Char.isTitleCase(c))
            return c;
        return Character.toUpperCase(c);
    }
    
    /** Translation sentinel: keep the original character. */
    private static final int KEEP = -2;
    /** Translation sentinel: delete the original character. */
    private static final int DELETE = -1;
    
    /**
     * In-place translate on StringBuilder (char-based).
     * XSLT translate/tr semantics:
     * - map by position in 'from' -> 'to'
     * - if source index >= to.length(), delete char
     * - duplicate chars in 'from': first occurrence wins
     * @param sb mutable text
     * @param from source characters
     * @param to replacement characters
     */
    public static void translate(StringBuilder sb, String from, String to) {
        if (sb == null) throw new NullPointerException("sb");
        if (from == null) throw new NullPointerException("from");
        if (to == null) throw new NullPointerException("to");

        int write = 0;
        final int length = sb.length();
        for (int read = 0; read < length; read++) {
            final char c = sb.charAt(read);
            final int action = mapChar(c, from, to);
            if (action != DELETE) sb.setCharAt(write++, action == KEEP ? c : (char) action);
        }
        sb.setLength(write);
    }
    

    /**
     * In-place translate on a subrange of char[].
     *
     * @param chars buffer
     * @param off   start offset (inclusive)
     * @param len   logical length of range to translate
     * @param from source characters
     * @param to replacement characters
     * @return new logical length of translated range (because deletions may occur)
     *
     * Result is stored in chars[off .. off+newLen).
     * Trailing chars beyond off+newLen are left unchanged/garbage.
     */
    public static int translate(char[] chars, int off, int len, String from, String to) {
        if (chars == null) throw new NullPointerException("chars");
        if (from == null) throw new NullPointerException("from");
        if (to == null) throw new NullPointerException("to");
        if (off < 0 || len < 0 || off > chars.length - len) {
            throw new IndexOutOfBoundsException(
                "off=" + off + ", len=" + len + ", chars.length=" + chars.length);
        }

        int r = off;         // read
        int end = off + len;
        int w = off;         // write

        while (r < end) {
            char c = chars[r++];
            int action = mapChar(c, from, to);

            if (action == KEEP) {
                chars[w++] = c;
            } else if (action == DELETE) {
                // skip (compact)
            } else {
                chars[w++] = (char) action;
            }
        }

        return w - off;
    }
    
    /**
     * Trim a default set of whitespace characters from both edges.
     *
     * @param sb mutable text to trim (modified in place)
     * @throws NullPointerException if {@code sb} is null
     */
    public static void trim(final StringBuilder sb)
    {
        trim(sb, "  \n\r\t");
    }

    /**
     * Trim characters from left and right of a mutable string.
     *
     * @param sb         mutable text to trim (modified in place)
     * @param stripChars characters to remove from left and right edge; if null or
     *                   empty, nothing is done
     * @throws NullPointerException if {@code sb} is null
     */
    public static void trim(final StringBuilder sb, final String stripChars)
    {
        Objects.requireNonNull(sb, "sb");
        if (stripChars == null || stripChars.isEmpty()) {
            return;
        }
        int end = sb.length();
        while (end > 0 && stripChars.indexOf(sb.charAt(end - 1)) >= 0) {
            end--;
        }
        int start = 0;
        while (start < end && stripChars.indexOf(sb.charAt(start)) >= 0) {
            start++;
        }
        if (end < sb.length()) {
            sb.setLength(end);
        }
        if (start > 0) {
            sb.delete(0, start);
        }
    }
    /**
     * Resolves the first occurrence of a character in a translation alphabet.
     * @param c character to translate
     * @param from source alphabet
     * @param to target alphabet
     * @return replacement character or KEEP or DELETE sentinel
     */
    private static int mapChar(char c, String from, String to) {
        int k = from.indexOf(c);   // first occurrence wins
        if (k < 0) return KEEP;
        if (k >= to.length()) return DELETE;
        return to.charAt(k);
    }
    

}
