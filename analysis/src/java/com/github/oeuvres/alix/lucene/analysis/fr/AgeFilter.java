package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.TokenStateQueue;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Aggregates French age expressions and Piaget's year-month-day notation.
 *
 * <p>Grammar (brackets indicate optional components):</p>
 * <pre>
 * NUMBER   = non-negative cardinal, normalized by NumberFilter
 * ORDINAL  = normalized ordinal
 * UNIT     = an(s) | année(s) | mois | semaine(s) | jour(s)
 * SIMPLE   = NUMBER UNIT [et demi(e)]
 * COMPOUND = SIMPLE [[et] SIMPLE] (descending units)
 * PIAGET   = YEAR ; MONTH [( DAY )] [an(s) | année(s)]
 * AGE      = COMPOUND | PIAGET
 * RANGE    = (NUMBER | AGE) (à | - | –) AGE
 *          | de (NUMBER | AGE) à AGE
 *          | entre (NUMBER | AGE) et AGE
 * PERIOD   = ORDINAL UNIT
 * </pre>
 * <p>YEAR, MONTH and DAY in PIAGET are surface decimal integers, respectively
 * 0–18, 0–11 and 0–31. These bounds cannot disambiguate all numerical lists.
 * Ordinary quantities in time units may also denote durations; this filter is
 * intended for corpora where treating them as ages is useful. Ordinal periods
 * retain NUMord rather than being treated as attained ages.</p>
 * <p>Numbers are read preferentially from the lemma, otherwise from the term.
 * Isolated ambiguous words un, une, neuf, second and seconde are confirmed
 * only when a complete age or period matches. No ordinary token is changed.
 * Matches do not cross structural boundaries or position gaps; punctuation
 * explicitly present in the grammar is allowed. Only the longest accepted
 * expression at the current position is emitted, with the first position
 * increment, the full source offsets, position length one and an empty lemma.</p>
 * <p>The default preserves the numerical precision in a canonical term.
 * The optional analytical mode rounds to weeks below one month, half months
 * below six months, months below twelve months, quarters below two years,
 * half years below four years and years thereafter. Conversion between days
 * and months uses the mean Gregorian month (365.25 / 12 days). Nonzero ages
 * below one month receive at least one week. Ranges round
 * their endpoints independently. No rounding is applied to ordinal periods.</p>
 */
public final class AgeFilter extends TokenFilter
{
    /** Maximum buffered tokens, including a failed lookahead. */
    private static final int LOOKAHEAD = 32;
    /** Mean Gregorian month in days, used only for analytical output. */
    private static final double MONTH_DAYS = 365.25 / 12;

    /** Age units in descending size. */
    private enum Unit
    {
        YEAR("ans", "année", 12), MONTH("mois", "mois", 1),
        WEEK("semaines", "semaine", 7 / MONTH_DAYS), DAY("jours", "jour", 1 / MONTH_DAYS);

        final String suffix;
        final String period;
        final double months;

        /**
         * Creates a unit and its conversion factor.
         * @param suffix canonical cardinal suffix
         * @param period canonical ordinal suffix
         * @param months unit duration in mean months
         */
        Unit(final String suffix, final String period, final double months)
        {
            this.suffix = suffix;
            this.period = period;
            this.months = months;
        }
    }

    /** Parsed endpoint; a null unit denotes a number awaiting a shared unit. */
    private record Age(int end, String text, String number, Unit unit, double months,
                       boolean simple, boolean ordinal) {}

    /** A complete replacement, with an exclusive end index. */
    private record Match(int end, String text, int pos) {}

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute incAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute lenAtt = addAttribute(PositionLengthAttribute.class);
    private final BoundaryAttribute boundaryAtt = addAttribute(BoundaryAttribute.class);
    private final boolean discretize;
    private TokenStateQueue queue;
    private boolean exhausted;

    /**
     * Creates a filter preserving recorded age precision.
     * @param input upstream numeric token stream
     */
    public AgeFilter(final TokenStream input)
    {
        this(input, false);
    }

    /**
     * Creates a filter with optional analytical age discretization.
     * @param input upstream numeric token stream
     * @param discretize true for rounded analytical ages, false for exact forms
     */
    public AgeFilter(final TokenStream input, final boolean discretize)
    {
        super(input);
        this.discretize = discretize;
    }

    /**
     * Emits a complete age or the next unchanged input token.
     * @return true if a token is available
     * @throws IOException if the input cannot be read
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        if (queue == null || queue.isEmpty()) {
            if (exhausted || !input.incrementToken()) {
                exhausted = true;
                return false;
            }
            // No allocations, queue copies or lookahead for ordinary words.
            if (lenAtt.getPositionLength() != 1 || incAtt.getPositionIncrement() == 0
                    || !mayStart(termAtt, lemmaAtt)) return true;
            if (queue == null) queue = new TokenStateQueue(LOOKAHEAD, this);
            queue.addLast(this);
        }
        final AttributeSource first = queue.get(0);
        if (first.getAttribute(PositionLengthAttribute.class).getPositionLength() != 1
                || first.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() == 0
                || !mayStart(first.getAttribute(CharTermAttribute.class), first.getAttribute(LemmaAttribute.class))) {
            queue.removeFirst(this);
            return true;
        }
        final Match match = match();
        if (match == null) {
            queue.removeFirst(this);
            return true;
        }
        final int endOffset = queue.get(match.end - 1).getAttribute(OffsetAttribute.class).endOffset();
        queue.restoreTo(this, 0);
        termAtt.setEmpty().append(match.text);
        lemmaAtt.setEmpty();
        posAtt.setPos(match.pos);
        offsetAtt.setOffset(offsetAtt.startOffset(), endOffset);
        lenAtt.setPositionLength(1);
        for (int i = 0; i < match.end; i++) queue.removeFirst();
        return true;
    }

    /**
     * Clears all buffered input before reuse.
     * @throws IOException if the input cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        if (queue != null) queue.clear();
        exhausted = false;
    }

    /**
     * Returns a buffered token, reading only as far as necessary, without
     * permitting a match to cross a structural boundary or position gap.
     * @param index zero-based index from the current input head
     * @return token state, or null at a boundary or end of input
     * @throws IOException on stream error
     */
    private AttributeSource at(final int index) throws IOException
    {
        if (index >= LOOKAHEAD) return null;
        // The last buffered token may be a barrier from failed lookahead.
        if (queue.size() > 1 && index >= queue.size() - 1
                && barrier(queue.get(queue.size() - 1))) return null;
        while (queue.size() <= index) {
            if (exhausted || !input.incrementToken()) {
                exhausted = true;
                return null;
            }
            queue.addLast(this);
            if (barrier(queue.get(queue.size() - 1))) return null;
        }
        return queue.get(index);
    }

    /**
     * Tests whether a non-initial token blocks a match.
     * @param token buffered state
     * @return true if this token cannot be joined to the preceding token
     */
    private static boolean barrier(final AttributeSource token)
    {
        final int pos = token.getAttribute(PosAttribute.class).getPos();
        return token.getAttribute(BoundaryAttribute.class).getBoundary() != BoundaryAttribute.NONE
            || token.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() != 1
            || token.getAttribute(PositionLengthAttribute.class).getPositionLength() != 1
            || pos == Upos.PUNCTpara.code || pos == Upos.PUNCTsection.code
            || pos == Upos.PUNCTstruct.code || pos == Upos.XML.code;
    }

    /**
     * Tests whether a character sequence begins with a decimal digit.
     * @param text character sequence
     * @return true if the first character is an ASCII digit
     */
    private static boolean digitStart(final CharSequence text)
    {
        return text.length() != 0 && text.charAt(0) >= '0' && text.charAt(0) <= '9';
    }

    /**
     * Compares a character buffer to a literal, ignoring case without allocating.
     * @param text character sequence
     * @param literal lowercase literal
     * @return true if equal ignoring case
     */
    private static boolean eq(final CharSequence text, final String literal)
    {
        if (text.length() != literal.length()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.toLowerCase(text.charAt(i)) != literal.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Tests a literal surface token at a lookahead position.
     * @param index lookahead index
     * @param literal lowercase surface form
     * @return true if the token matches
     * @throws IOException on stream error
     */
    private boolean is(final int index, final String literal) throws IOException
    {
        final AttributeSource token = at(index);
        return token != null && eq(token.getAttribute(CharTermAttribute.class), literal);
    }

    /**
     * Tests the inexpensive necessary conditions for beginning an age.
     * @param term surface form
     * @param lemma lemma annotation
     * @return true if a rule may begin at this token
     */
    private static boolean mayStart(final CharSequence term, final CharSequence lemma)
    {
        if (digitStart(term) || digitStart(lemma)) return true;
        if (term.length() == 0) return false;
        return switch (Character.toLowerCase(term.charAt(0))) {
            case 'u' -> eq(term, "un") || eq(term, "une");
            case 'n' -> eq(term, "neuf");
            case 's' -> eq(term, "second") || eq(term, "seconde");
            case 'd' -> eq(term, "de");
            case 'e' -> eq(term, "entre");
            default -> false;
        };
    }

    /**
     * Reads a bounded unsigned integer from the surface, never the lemma.
     * @param index lookahead index
     * @param max inclusive maximum
     * @return the integer, or -1 if invalid or outside the bound
     * @throws IOException on stream error
     */
    private int bounded(final int index, final int max) throws IOException
    {
        final AttributeSource token = at(index);
        if (token == null) return -1;
        final CharTermAttribute term = token.getAttribute(CharTermAttribute.class);
        if (term.length() == 0 || term.length() > 2) return -1;
        int value = 0;
        for (int i = 0; i < term.length(); i++) {
            final char c = term.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + c - '0';
        }
        return value <= max ? value : -1;
    }

    /**
     * Recognizes one time unit from its surface form.
     * @param index lookahead index
     * @return recognized unit or null
     * @throws IOException on stream error
     */
    private Unit unit(final int index) throws IOException
    {
        final AttributeSource token = at(index);
        if (token == null) return null;
        final CharTermAttribute t = token.getAttribute(CharTermAttribute.class);
        if (eq(t, "an") || eq(t, "ans") || eq(t, "année") || eq(t, "années")) return Unit.YEAR;
        if (eq(t, "mois")) return Unit.MONTH;
        if (eq(t, "semaine") || eq(t, "semaines")) return Unit.WEEK;
        if (eq(t, "jour") || eq(t, "jours")) return Unit.DAY;
        return null;
    }

    /**
     * Recognizes the canonical cardinal representation of a token.
     * The old NumberFilter's surface numbers and new numeric lemmas both work.
     * @param index lookahead index
     * @return normalized number or null
     * @throws IOException on stream error
     */
    private String number(final int index) throws IOException
    {
        final AttributeSource token = at(index);
        if (token == null || token.getAttribute(PosAttribute.class).getPos() == Upos.NUMord.code) return null;
        final LemmaAttribute lemma = token.getAttribute(LemmaAttribute.class);
        final CharTermAttribute term = token.getAttribute(CharTermAttribute.class);
        String n = cardinal(lemma);
        if (n == null) n = cardinal(term);
        if (n != null) return n;
        // Context confirms these only when the entire construction succeeds.
        if (eq(term, "un") || eq(term, "une")) return "1";
        if (eq(term, "neuf")) return "9";
        return null;
    }

    /**
     * Validates a decimal or exact rational cardinal without accepting signs or exponents.
     * @param text candidate number
     * @return normalized number or null
     */
    private static String cardinal(final CharSequence text)
    {
        if (!digitStart(text)) return null;
        boolean separator = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c >= '0' && c <= '9') continue;
            if (separator || i == text.length() - 1 || (c != '.' && c != ',' && c != '/')) return null;
            separator = true;
        }
        final String s = text.toString().replace(',', '.');
        final double v = magnitude(s);
        return Double.isFinite(v) ? s : null;
    }

    /**
     * Converts a validated canonical decimal, rational or half-number to a double.
     * @param n canonical number
     * @return magnitude, or NaN for invalid input
     */
    private static double magnitude(final String n)
    {
        try {
            if (n.endsWith("½")) return Double.parseDouble(n.substring(0, n.length() - 1)) + 0.5;
            final int slash = n.indexOf('/');
            if (slash >= 0) return Double.parseDouble(n.substring(0, slash)) / Double.parseDouble(n.substring(slash + 1));
            return Double.parseDouble(n);
        }
        catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Recognizes a normalized ordinal, including an unresolved second/seconde.
     * @param index lookahead index
     * @return normalized ordinal or null
     * @throws IOException on stream error
     */
    private String ordinal(final int index) throws IOException
    {
        final AttributeSource token = at(index);
        if (token == null) return null;
        final LemmaAttribute lemma = token.getAttribute(LemmaAttribute.class);
        final CharTermAttribute term = token.getAttribute(CharTermAttribute.class);
        final String l = ordinalForm(lemma);
        if (l != null) return l;
        final String t = ordinalForm(term);
        if (t != null) return t;
        return eq(term, "second") || eq(term, "seconde") ? "2e" : null;
    }

    /**
     * Validates the canonical suffixes e and er.
     * @param text candidate ordinal
     * @return canonical ordinal or null
     */
    private static String ordinalForm(final CharSequence text)
    {
        if (!digitStart(text) || text.length() < 2) return null;
        int end = text.length() - 1;
        if (text.charAt(end) == 'r' && end > 0) end--;
        if (text.charAt(end) != 'e' || end == 0) return null;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return null;
        }
        return text.toString();
    }

    /**
     * Recognizes an ordinal period, a Piaget endpoint, or an ordinary age/number.
     * @param start start index
     * @param compoundAnd whether et may join components
     * @return endpoint or null
     * @throws IOException on stream error
     */
    private Age age(final int start, final boolean compoundAnd) throws IOException
    {
        final Age piaget = piaget(start);
        if (piaget != null) return piaget;
        final String ord = ordinal(start);
        if (ord != null) {
            final Unit u = unit(start + 1);
            return u == null ? null : new Age(start + 2, ord + u.period, ord, u, 0, true, true);
        }
        final String n = number(start);
        if (n == null) return null;
        final Unit u = unit(start + 1);
        if (u == null) return new Age(start + 1, n, n, null, magnitude(n), true, false);
        Age a = simple(start, n, u);
        final int firstEnd = a.end;
        final int next = compoundAnd && is(firstEnd, "et") ? firstEnd + 1 : firstEnd;
        final String n2 = number(next);
        if (n2 == null) return a;
        final Unit u2 = unit(next + 1);
        if (u2 == null || u2.ordinal() <= u.ordinal()) return a;
        final Age b = simple(next, n2, u2);
        return new Age(b.end, a.text + b.text, null, u, a.months + b.months, false, false);
    }

    /**
     * Recognizes an ordinary unit and an optional half-unit suffix.
     * @param start number index
     * @param n canonical number
     * @param u unit
     * @return parsed age
     * @throws IOException on stream error
     */
    private Age simple(final int start, final String n, final Unit u) throws IOException
    {
        int end = start + 2;
        String value = n;
        double v = magnitude(n);
        if (is(end, "et") && (is(end + 1, "demi") || is(end + 1, "demie"))
                && n.indexOf('.') < 0 && n.indexOf('/') < 0) {
            end += 2;
            value += "½";
            v += 0.5;
        }
        return new Age(end, value + u.suffix, value, u, v * u.months, true, false);
    }

    /**
     * Recognizes the surface notation YEAR ; MONTH [(DAY)] [ans].
     * @param start year index
     * @return parsed endpoint or null
     * @throws IOException on stream error
     */
    private Age piaget(final int start) throws IOException
    {
        final int y = bounded(start, 18);
        if (y < 0 || !is(start + 1, ";")) return null;
        final int m = bounded(start + 2, 11);
        if (m < 0) return null;
        int end = start + 3;
        int d = -1;
        if (is(end, "(")) {
            final int candidate = bounded(end + 1, 31);
            if (candidate >= 0 && is(end + 2, ")")) {
                d = candidate;
                end += 3;
            }
        }
        if (unit(end) == Unit.YEAR) end++;
        final String text = y + "ans" + m + "mois" + (d < 0 ? "" : d + "jours");
        return new Age(end, text, null, Unit.YEAR, y * 12 + m + Math.max(0, d) / MONTH_DAYS, false, false);
    }

    /**
     * Recognizes a range connector, respecting an explicit de/entre prefix.
     * @param index connector index
     * @param between true after entre
     * @param from true after de
     * @return true if the connector is accepted
     * @throws IOException on stream error
     */
    private boolean connector(final int index, final boolean between, final boolean from) throws IOException
    {
        if (between) return is(index, "et");
        if (from) return is(index, "à");
        return is(index, "à") || is(index, "-") || is(index, "–");
    }

    /**
     * Finds the longest accepted expression from the queue head.
     * @return accepted replacement or null
     * @throws IOException on stream error
     */
    private Match match() throws IOException
    {
        final boolean between = is(0, "entre");
        final boolean from = is(0, "de");
        final int start = between || from ? 1 : 0;
        final Age a = age(start, !between);
        if (a == null) return null;
        if (a.ordinal) return start == 0 ? new Match(a.end, a.text, Upos.NUMord.code) : null;
        if (connector(a.end, between, from)) {
            final Age b = age(a.end + 1, true);
            if (b != null && !b.ordinal && b.unit != null) {
                final Age left = a.unit == null
                    ? new Age(a.end, a.number + b.unit.suffix, a.number, b.unit,
                        a.months * b.unit.months, true, false) : a;
                return new Match(b.end, range(left, b), Upos.NUMage.code);
            }
        }
        if (start != 0 || a.unit == null) return null;
        return new Match(a.end, format(a), Upos.NUMage.code);
    }

    /**
     * Formats a range without changing the order of its endpoints.
     * @param a first endpoint
     * @param b second endpoint
     * @return canonical interval
     */
    private String range(final Age a, final Age b)
    {
        if (discretize) {
            final String left = format(a);
            final String right = format(b);
            for (Unit u : Unit.values()) {
                if (left.endsWith(u.suffix) && right.endsWith(u.suffix)) {
                    return left.substring(0, left.length() - u.suffix.length()) + "-" + right;
                }
            }
            return left + "-" + right;
        }
        if (a.simple && b.simple && a.unit == b.unit) return a.number + "-" + b.text;
        return a.text + "-" + b.text;
    }

    /**
     * Selects exact or rounded output, retaining exact values outside the rounding range.
     * @param a endpoint
     * @return selected representation
     */
    private String format(final Age a)
    {
        if (!discretize || !Double.isFinite(a.months) || a.months >= Long.MAX_VALUE / 24.0) return a.text;
        return rounded(a.months);
    }

    /**
     * Formats a non-negative number of half-units.
     * @param twice twice the quantity
     * @return canonical integer or half-integer
     */
    private static String halves(final long twice)
    {
        return Long.toString(twice / 2) + (twice % 2 == 0 ? "" : "½");
    }

    /**
     * Converts a chronological age into the configured developmental bins.
     * @param months age in mean Gregorian months
     * @return discretized age
     */
    private static String rounded(final double months)
    {
        if (months == 0) return "0jours";
        if (months < 1) return Math.max(1, Math.round(months * MONTH_DAYS / 7)) + "semaines";
        final double step = months < 6 ? 0.5 : months < 12 ? 1 : months < 24 ? 3 : months < 48 ? 6 : 12;
        final double m = Math.round(months / step) * step;
        if (m < 24) return halves(Math.round(m * 2)) + "mois";
        return halves(Math.round(m / 6)) + "ans";
    }
}
