package com.github.oeuvres.alix.util;

/** Standalone exhaustive property and tokenizer-policy regression tests. */
public final class CharTest
{
    /** Prevents construction. */
    private CharTest() { }

    /**
     * Tests every UTF-16 code unit and representative lexical exceptions.
     * @param args unused
     */
    public static void main(final String[] args)
    {
        for (int i = 0; i <= Character.MAX_VALUE; i++) {
            final char c = (char) i;
            final int type = Character.getType(c);
            check(Char.isLetter(c) == Character.isLetter(c), c, "letter");
            check(Char.isDigit(c) == Character.isDigit(c), c, "digit");
            check(Char.isDigit(i) == Character.isDigit(c), c, "digit(int)");
            check(Char.isLetterOrDigit(c) == Character.isLetterOrDigit(c), c, "letterOrDigit");
            check(Char.isLowerCase(c) == Character.isLowerCase(c), c, "lowercase");
            check(Char.isUpperCase(c) == Character.isUpperCase(c), c, "uppercase");
            check(Char.isTitleCase(c) == Character.isTitleCase(c), c, "titlecase");
            check(Char.isUpperOrTitleCase(c) == (Character.isUpperCase(c) || Character.isTitleCase(c)), c, "upper/title");
            check(Char.isHighSurrogate(c) == Character.isHighSurrogate(c), c, "high surrogate");
            check(Char.isLowSurrogate(c) == Character.isLowSurrogate(c), c, "low surrogate");
            check(Char.isLatin(c) == (Character.isLetter(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN), c, "Latin letter");
            check(Char.isSpace(c) == (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)), c, "space");
            check(Char.isMath(c) == (type == Character.MATH_SYMBOL), c, "math");
            check(Char.isMark(c) == (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK), c, "mark");
            check(Char.isPunctuation(c) == isUnicodePunctuation(type), c, "punctuation");
            check(Char.isPUNsent(c) == (".…?!".indexOf(c) >= 0), c, "sentence punctuation");
            check(Char.isPUNcl(c) == (",;:()—–\"«»".indexOf(c) >= 0), c, "clause punctuation");
            check(Char.isUnitSymbol(c) == ("%‰‱°".indexOf(c) >= 0 || type == Character.CURRENCY_SYMBOL), c, "unit symbol");
            check(Char.isMathToken(c) == ("+×÷=≠≤≥±∓√∑∏∞∫*/⁄<>^".indexOf(c) >= 0), c, "math token");
            check(Char.isToken(c) == referenceToken(c), c, "word token");
            check(Char.isVulgarFraction(c) == fraction(c), c, "vulgar fraction");
            check(Char.isMinusSign(c) == ("-−\u2010\u2011\u00AD".indexOf(c) >= 0), c, "minus sign");
            check(Char.isThousandsSeparator(c) == ("\u00A0\u202F\u2009".indexOf(c) >= 0), c, "grouping space");
            check(Char.isHorizontalSpace(c) == (" \t\u00A0\u202F".indexOf(c) >= 0), c, "horizontal space");
            check(Char.isLineBreak(i) == (c == '\r' || c == '\n'), c, "line break");
            check(Char.toLower(c) == Character.toLowerCase(c), c, "lowercase conversion");
            check(Char.toUpper(c) == Character.toUpperCase(c), c, "uppercase conversion");
        }
        check(!Char.isDigit(-1) && !Char.isVulgarFraction(-1) && !Char.isLetterOrDigit(-1)
            && !Char.isLineBreak(-1), '\uFFFF', "EOF");
        check(!Char.isDigit(0x10030) && !Char.isLetterOrDigit(0x10041), '\uFFFF', "no truncation");
        check(Char.isToken('&') && !Char.isLetter('&'), '&', "entity start is not a letter");
        check(Char.isToken('\uFFFD') && !Char.isLetter('\uFFFD'), '\uFFFD', "replacement character");
        check(Char.isUnitSymbol('°') && !Char.isToken('°'), '°', "degree symbol");
        check(Char.isMathToken('×') && !Char.isToken('×'), '×', "multiplication");
        check(Char.isVulgarFraction('½') && !Char.isToken('½'), '½', "fraction");
        check(Char.isToken('\u0301') && !Char.isLetter('\u0301'), '\u0301', "combining accent");
        final StringBuilder translated = new StringBuilder("abcabcabc");
        Char.translate(translated, "abc", "x");
        check(translated.toString().equals("xxx"), 'a', "linear translation");
        System.out.println("PASS: all 65536 UTF-16 code units and focused regression tests");
    }

    /**
     * Checks a classification or conversion against its reference.
     * @param condition expected condition
     * @param c character being tested
     * @param property test label
     */
    private static void check(final boolean condition, final char c, final String property)
    {
        if (!condition) throw new AssertionError(String.format("U+%04X: %s", (int) c, property));
    }

    /**
     * Reference predicate for the tokenizer's supported fractions.
     * @param c UTF-16 code unit
     * @return true for a supported fraction
     */
    private static boolean fraction(final char c)
    {
        return c == 0x00BC || c == 0x00BD || c == 0x00BE
            || (c >= 0x2150 && c <= 0x215E) || c == 0x2189;
    }

    /**
     * Reference predicate for the Unicode punctuation categories.
     * @param type Unicode general category
     * @return true for punctuation
     */
    private static boolean isUnicodePunctuation(final int type)
    {
        return switch (type) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                Character.START_PUNCTUATION -> true;
            default -> false;
        };
    }

    /**
     * Reference word-character predicate using JDK properties and literal searches.
     * @param c UTF-16 code unit
     * @return whether the character can occur inside an Alix word token
     */
    static boolean referenceToken(final char c)
    {
        final int type = Character.getType(c);
        if (type == Character.CURRENCY_SYMBOL || "%‰‱°+×÷=≠≤≥±∓√∑∏∞∫*/⁄<>^".indexOf(c) >= 0 || fraction(c)) return false;
        return Character.isLetterOrDigit(c) || "&-\u00AD'’_\u2010\u2011�".indexOf(c) >= 0
            || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
            || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }
}
