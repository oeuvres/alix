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

package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.Objects;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Annotates numbers before contextual POS tagging, without a lemmatizer or
 * a locale-specific runtime parser. Numerical lemmas use ASCII digits, decimal
 * points and no grouping spaces. Resolved cardinals receive NUMcard; ordinals
 * retain their suffix and receive ADJord. Raw NUM tokens come from the tokenizer.
 *
 * <p>Lexical expressions are resolved by longest match against a shared lookup
 * table. Each scale multiplies the preceding block; successive scales must
 * decrease. Ambiguous isolated dictionary forms receive a numerical lemma
 * candidate without changing their POS. Punctuation, markup, structural
 * boundaries and position gaps interrupt matching.</p>
 *
 * <p>Replace-only semantics mirror MweFilter: the first position increment is
 * retained, the consumed positions collapse, and offsets cover the whole match.
 * The term retains the upstream surface form; aggregated terms are reconstructed
 * from component terms, inserting a space for a gap in source offsets. Exact
 * nonterminating vulgar fractions use a rational string (e.g. 1/3). A later
 * contextual filter must accept or reject ambiguous lemma candidates.</p>
 */
public final class NumberFilter extends TokenFilter
{
    /** Shared, read-only numeric vocabulary, with prefixes for bounded lookahead. */
    public static final class Lexicon
    {
        private final CharArrayMap<char[]> forms;
        private final CharArraySet prefixes = new CharArraySet(256, true);
        private final CharArraySet romanCues = new CharArraySet(16, true);

        /**
         * Compiles the lookup table once. Keys are lowercase-insensitive words or
         * expressions with hyphens represented by spaces. Values are canonical
         * numbers, optionally prefixed by '?' (ambiguous) or '*' (scale).
         * The values '-', '+' and '.' designate sign and decimal words.
         * Keys starting @suffix: and @roman: supply suffixes and Roman cues.
         * @param map loaded numerical dictionary, not modified by this method
         */
        public Lexicon(final CharArrayMap<char[]> map)
        {
            final CharArrayMap<char[]> copy = new CharArrayMap<>(map.size(), true);
            for (var entry : map.entrySet()) {
                final Object key = entry.getKey();
                final String form = key instanceof char[] chars ? new String(chars) : key.toString();
                final String normalized = form.startsWith("@") ? form : wordKey(form);
                copy.put(normalized, entry.getValue().clone());
                if (normalized.startsWith("@roman:")) romanCues.add(normalized.substring(7));
                if (normalized.startsWith("@")) continue;
                for (int p = normalized.indexOf(' '); p >= 0; p = normalized.indexOf(' ', p + 1)) {
                    prefixes.add(normalized.substring(0, p));
                }
            }
            forms = CharArrayMap.unmodifiableMap(copy);
        }

        /**
         * Looks up a numeric dictionary entry.
         * @param key normalized lookup key
         * @return dictionary value, or null
         */
        private String get(final CharSequence key)
        {
            final char[] value = forms.get(key);
            return value == null ? null : new String(value);
        }
    }

    /** Upper bound on buffered input tokens per number. */
    private static final int LOOKAHEAD = 32;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private final Lexicon lexicon;
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    @SuppressWarnings("unused")
    private final PositionIncrementAttribute incAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute lenAtt = addAttribute(PositionLengthAttribute.class);
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);
    @SuppressWarnings("unused")
    private final BoundaryAttribute boundaryAtt = addAttribute(BoundaryAttribute.class);
    private TokenStateQueue queue;
    private boolean exhausted;
    private boolean romanCue;

    /** Accepted term and exclusive end index in the lookahead queue. */
    private record Match(String value, int end) {}

    /**
     * Creates a number filter with a shared lexical resource.
     * @param input upstream tokenizer or markup-boundary stream
     * @param lexicon shared numerical dictionary
     */
    public NumberFilter(final TokenStream input, final Lexicon lexicon)
    {
        super(input);
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
    }

    /**
     * Emits a numerical annotation or the head of the buffered stream.
     * @return true if a token is available
     * @throws IOException if the input cannot be read
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        // Most lexical tokens are not numbers. Do not copy their states into a queue.
        if (queue == null || queue.isEmpty()) {
            if (exhausted || !input.incrementToken()) {
                exhausted = true;
                return false;
            }
            if (!mayStartNumber()) {
                rememberRomanCue();
                return true;
            }
            if (queue == null) queue = new TokenStateQueue(LOOKAHEAD, this);
            queue.addLast(this);
        }
        final AttributeSource first = queue.get(0);
        if (first.getAttribute(BoundaryAttribute.class).getBoundary() != BoundaryAttribute.NONE
                || first.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() != 1) romanCue = false;
        final Match match = parseNumber();
        if (match == null) {
            queue.removeFirst(this);
            if (!blocksNumber(posAtt.getPos())) annotateAmbiguous();
            rememberRomanCue();
            return true;
        }
        final int endOffset = queue.get(match.end - 1).getAttribute(OffsetAttribute.class).endOffset();
        queue.restoreTo(this, 0);
        if (match.end > 1) {
            // Single-token forms are already restored unchanged. Reconstruct only
            // aggregated forms; the original Reader is not available here.
            int previousEnd = queue.get(0).getAttribute(OffsetAttribute.class).endOffset();
            for (int i = 1; i < match.end; i++) {
                final AttributeSource slot = queue.get(i);
                final OffsetAttribute offsets = slot.getAttribute(OffsetAttribute.class);
                if (offsets.startOffset() > previousEnd) termAtt.append(' ');
                termAtt.append(slot.getAttribute(CharTermAttribute.class));
                previousEnd = offsets.endOffset();
            }
        }
        posAtt.setPos(isOrdinal(match.value) ? ADJord.ordinal() : NUMcard.ordinal());
        lemmaAtt.setEmpty();
        lemmaAtt.append(match.value);
        offsetAtt.setOffset(offsetAtt.startOffset(), endOffset);
        lenAtt.setPositionLength(1);
        for (int i = 0; i < match.end; i++) queue.removeFirst();
        romanCue = false;
        return true;
    }

    /**
     * Clears all buffered input and contextual state before stream reuse.
     * @throws IOException if the input cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        if (queue != null) queue.clear();
        exhausted = false;
        romanCue = false;
    }

    /**
     * Publishes an isolated ambiguous dictionary entry as a lemma candidate,
     * preserving the surface term and the upstream POS. Called only after
     * the numerical parser has found no confirmed match.
     */
    private void annotateAmbiguous()
    {
        final char[] value = lexicon.forms.get(termAtt.buffer(), 0, termAtt.length());
        if (value != null && value.length > 1 && value[0] == '?') {
            lemmaAtt.copyBuffer(value, 1, value.length - 1);
        }
    }

    /**
     * Tests the live token for a possible numeric start without allocating a token state.
     * @return true for literal numbers, dictionary forms, prefixes or possible Roman notation
     */
    private boolean mayStartNumber()
    {
        final int pos = posAtt.getPos();
        if (blocksNumber(pos) || termAtt.length() == 0) return false;
        if (pos == NUM.ordinal() || pos == NUMcard.ordinal() || pos == ADJord.ordinal()) return true;
        final char[] chars = termAtt.buffer();
        final int length = termAtt.length();
        if (lexicon.forms.containsKey(chars, 0, length) || lexicon.prefixes.contains(chars, 0, length)) return true;
        final char first = chars[0];
        if ("IVXLCDM".indexOf(first) >= 0 || (first >= '\u2160' && first <= '\u216F')) return true;
        for (int i = 0; i < length; i++) {
            if (chars[i] == '-' || chars[i] == '‐' || chars[i] == '‑') {
                final String key = wordKey(termAtt.toString());
                return lexicon.forms.containsKey(key) || lexicon.prefixes.contains(key);
            }
        }
        return false;
    }

    /**
     * Tests for structural or symbolic tokens that cannot join a number.
     * @param pos upstream UPOS ordinal
     * @return true for invalid codes, markup, punctuation or symbols
     */
    private static boolean blocksNumber(final int pos)
    {
        final Upos tag = get(pos);
        return tag == null || tag == XML || tag.base() == PUNCT || tag.base() == SYM;
    }

    /** Records whether the emitted token licenses a following single Roman numeral. */
    private void rememberRomanCue()
    {
        romanCue = !blocksNumber(posAtt.getPos())
            && lexicon.romanCues.contains(termAtt.buffer(), 0, termAtt.length());
    }

    /**
     * Buffers tokens up to an index, preserving failed lookahead for replay.
     * @param index zero-based queue index
     * @return whether that token exists within the lookahead bound
     * @throws IOException if the input cannot be read
     */
    private boolean ensure(final int index) throws IOException
    {
        if (index >= LOOKAHEAD) return false;
        while (queue.size() <= index && !exhausted) {
            if (!input.incrementToken()) exhausted = true;
            else queue.addLast(this);
        }
        return queue.size() > index;
    }

    /**
     * Tests whether a slot may participate in a number.
     * @param index buffered token index
     * @return true for a lexical token without an intervening boundary
     * @throws IOException if the input cannot be read
     */
    private boolean eligible(final int index) throws IOException
    {
        if (!ensure(index)) return false;
        final AttributeSource slot = queue.get(index);
        final int pos = slot.getAttribute(PosAttribute.class).getPos();
        if (blocksNumber(pos)
                || slot.getAttribute(PositionLengthAttribute.class).getPositionLength() != 1
                || slot.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() == 0) return false;
        return index == 0 || (slot.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() == 1
            && slot.getAttribute(BoundaryAttribute.class).getBoundary() == BoundaryAttribute.NONE);
    }

    /**
     * Returns the longest matching lexical block or a normalized numerical literal.
     * @param start starting queue index
     * @return the longest accepted block, or null
     * @throws IOException if the input cannot be read
     */
    private Match block(final int start) throws IOException
    {
        if (!eligible(start)) return null;
        final AttributeSource slot = queue.get(start);
        final CharTermAttribute term = slot.getAttribute(CharTermAttribute.class);
        final int pos = slot.getAttribute(PosAttribute.class).getPos();
        final String raw = term.toString();
        if (pos == NUM.ordinal() || pos == NUMcard.ordinal() || pos == ADJord.ordinal()) {
            final String literal = normalizeLiteral(raw);
            if (literal == null) return null;
            if (isUnsignedInteger(literal) && eligible(start + 1)) {
                final String suffix = queue.get(start + 1).getAttribute(CharTermAttribute.class).toString();
                final String canonical = lexicon.get("@suffix:" + suffix);
                // A separated plain 'e' may be a mathematical variable.
                if (canonical != null && (!suffix.equalsIgnoreCase("e") || adjacent(start, start + 1))) {
                    return new Match(literal + canonical, start + 2);
                }
            }
            return new Match(literal, start + 1);
        }
        final String key = wordKey(raw);
        String value = lexicon.get(key);
        Match best = value == null ? null : new Match(value, start + 1);
        final StringBuilder candidate = new StringBuilder(key);
        int index = start + 1;
        while (lexicon.prefixes.contains(candidate) && eligible(index)) {
            candidate.append(' ').append(wordKey(queue.get(index).getAttribute(CharTermAttribute.class).toString()));
            value = lexicon.get(candidate);
            if (value != null) best = new Match(value, index + 1);
            index++;
        }
        if (best != null) return best;
        if (start == 0) {
            final String roman = normalizeRoman(raw);
            if (roman != null) return new Match(roman, start + 1);
        }
        return null;
    }

    /**
     * Tests source adjacency for potentially ambiguous suffixes.
     * @param left first buffered token
     * @param right following buffered token
     * @return true when their original offsets touch
     */
    private boolean adjacent(final int left, final int right)
    {
        return queue.get(left).getAttribute(OffsetAttribute.class).endOffset()
            == queue.get(right).getAttribute(OffsetAttribute.class).startOffset();
    }

    /**
     * Composes decreasing magnitude blocks, a possible sign and decimal part.
     * Independent adjacent numerical literals are never combined.
     * @return accepted number, or null to replay the first input token unchanged
     * @throws IOException if the input cannot be read
     */
    private Match parseNumber() throws IOException
    {
        Match atom = block(0);
        if (atom == null) return null;
        String sign = "";
        boolean hasSign = false;
        if (atom.value.equals("-") || atom.value.equals("+")) {
            hasSign = true;
            sign = atom.value.equals("-") ? "-" : "";
            atom = block(atom.end);
            if (atom == null) return null;
        }
        BigInteger total = ZERO;
        BigInteger lastScale = null;
        Match best = null;
        int index = atom.end;
        while (true) {
            final boolean ambiguous = atom.value.startsWith("?");
            final String value = ambiguous ? atom.value.substring(1) : atom.value;
            if (value.startsWith("*")) {
                // Only 'one' is implicit before a scale, as in 'mille'.
                final String scaleText = value.substring(1);
                final BigInteger scale = integerPart(scaleText);
                if (scale == null || (!scale.equals(BigInteger.valueOf(1000)) && !isOrdinal(scaleText))
                        || (lastScale != null && scale.compareTo(lastScale) >= 0)) break;
                total = total.add(scale);
                lastScale = scale;
                best = new Match(sign + total + ordinalSuffix(scaleText), index);
                if (isOrdinal(scaleText)) return best;
            }
            else if (isUnsignedInteger(value)) {
                final BigInteger number = new BigInteger(value);
                if (lastScale != null && number.compareTo(lastScale) >= 0) break;
                final Match next = block(index);
                if (next != null && next.value.startsWith("*")) {
                    final String scaleText = next.value.substring(1);
                    final BigInteger scale = integerPart(scaleText);
                    if (scale != null && number.signum() > 0 && number.compareTo(scale) < 0
                            && (lastScale == null || scale.compareTo(lastScale) < 0)
                            && (lastScale == null || number.multiply(scale).compareTo(lastScale) < 0)) {
                        total = total.add(number.multiply(scale));
                        lastScale = scale;
                        index = next.end;
                        best = new Match(sign + total + ordinalSuffix(scaleText), index);
                        if (isOrdinal(scaleText)) return best;
                    }
                    else break;
                }
                else {
                    if (!ambiguous || lastScale != null || hasSign) {
                        best = new Match(sign + total.add(number), index);
                    }
                    if (next != null && next.value.equals(".")) {
                        final Match fraction = decimalTail(next.end);
                        if (fraction != null) return new Match(canonicalDecimal(sign + total.add(number) + "." + fraction.value), fraction.end);
                    }
                    return best;
                }
            }
            else if (isOrdinal(value)) {
                if (ambiguous && lastScale == null && !hasSign) return best;
                final BigInteger number = integerPart(value);
                if (number == null || (lastScale != null && number.compareTo(lastScale) >= 0)) return best;
                return new Match(sign + total.add(number) + ordinalSuffix(value), index);
            }
            else {
                if (lastScale != null || value.equals(".") || value.equals("-") || value.equals("+")) return best;
                // Already canonical decimal, scientific notation, signed literal or exact fraction.
                if (hasSign && value.startsWith("-")) return null;
                return new Match(sign + value, index);
            }
            final Match next = block(index);
            if (next == null) return best;
            if (next.value.equals(".")) {
                final Match fraction = decimalTail(next.end);
                return fraction == null ? best : new Match(canonicalDecimal(sign + total + "." + fraction.value), fraction.end);
            }
            atom = next;
            index = atom.end;
        }
        return best;
    }

    /**
     * Reads one decimal block or a sequence of individual digit words.
     * @param start first fractional token
     * @return fractional digits and end index, or null
     * @throws IOException if the input cannot be read
     */
    private Match decimalTail(final int start) throws IOException
    {
        Match atom = block(start);
        if (atom == null) return null;
        String digits = decimalDigits(atom, start);
        if (!isUnsignedInteger(digits)) return null;
        int index = atom.end;
        // Digit-by-digit forms preserve leading zeros after the decimal separator.
        if (digits.length() == 1) {
            final StringBuilder buf = new StringBuilder(digits);
            while ((atom = block(index)) != null) {
                final String digit = decimalDigits(atom, index);
                if (!isUnsignedInteger(digit) || digit.length() != 1) break;
                buf.append(digit);
                index = atom.end;
            }
            digits = buf.toString();
        }
        return new Match(digits, index);
    }

    /**
     * Reads a decimal block, retaining leading zeros of literal fractional digits.
     * @param atom matched decimal block
     * @param start index of its first token
     * @return canonical digits, without removing leading zeros in the fraction
     */
    private String decimalDigits(final Match atom, final int start)
    {
        if (atom.end == start + 1) {
            final CharTermAttribute raw = queue.get(start).getAttribute(CharTermAttribute.class);
            final StringBuilder digits = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                final int digit = Character.digit(raw.charAt(i), 10);
                if (digit < 0) break;
                digits.append((char) ('0' + digit));
            }
            if (digits.length() > 0 && digits.length() == raw.length()) return digits.toString();
        }
        return atom.value.startsWith("?") ? atom.value.substring(1) : atom.value;
    }

    /**
     * Formats a finite decimal in the same canonical form as a numerical literal.
     * @param value ASCII decimal representation
     * @return plain decimal with redundant trailing zeros removed
     */
    private static String canonicalDecimal(final String value)
    {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Normalizes a tokenizer-identified number without locale-sensitive output.
     * @param raw original numerical term
     * @return canonical numerical term, or null for unsupported syntax
     */
    private String normalizeLiteral(final String raw)
    {
        final StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            final int digit = Character.digit(c, 10);
            if (digit >= 0) b.append((char) ('0' + digit));
            else if (c == ',' || c == '.') b.append('.');
            else if (c == '−') b.append('-');
            else if (c != '\u00A0' && c != '\u202F' && c != '\u2009') b.append(c);
        }
        final String s = b.toString();
        if (s.isEmpty()) return null;
        final char last = s.charAt(s.length() - 1);
        // Compatibility normalization is safe here only for an isolated vulgar fraction.
        final String frac = (last == 0x00BC || last == 0x00BD || last == 0x00BE
            || (last >= 0x2150 && last <= 0x215E) || last == 0x2189)
            ? Normalizer.normalize(String.valueOf(last), Normalizer.Form.NFKC) : "";
        final int slash = frac.indexOf('⁄');
        if (slash > 0 && slash < frac.length() - 1) {
            try {
                final BigInteger numerator = new BigInteger(frac.substring(0, slash));
                final BigInteger denominator = new BigInteger(frac.substring(slash + 1));
                String whole = s.substring(0, s.length() - 1);
                final boolean negative = whole.startsWith("-");
                if (negative || whole.startsWith("+")) whole = whole.substring(1);
                if (!whole.isEmpty() && !isUnsignedInteger(whole)) return null;
                final BigInteger integer = whole.isEmpty() ? ZERO : new BigInteger(whole);
                BigInteger n = integer.multiply(denominator).add(numerator);
                if (negative) n = n.negate();
                final BigInteger gcd = n.gcd(denominator);
                try {
                    return new BigDecimal(n).divide(new BigDecimal(denominator)).stripTrailingZeros().toPlainString();
                } catch (ArithmeticException nonterminating) {
                    return n.divide(gcd) + "/" + denominator.divide(gcd);
                }
            } catch (NumberFormatException invalid) { return null; }
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i > 0 && i < s.length()) {
            final String suffix = lexicon.get("@suffix:" + s.substring(i));
            if (suffix != null) return new BigInteger(s.substring(0, i)) + suffix;
        }
        // Ordinary integers are the hot path. No BigDecimal allocation is needed.
        final boolean negative = s.startsWith("-");
        final int first = negative || s.startsWith("+") ? 1 : 0;
        if (isUnsignedInteger(s.substring(first))) {
            int significant = first;
            while (significant + 1 < s.length() && s.charAt(significant) == '0') significant++;
            final String digits = s.substring(significant);
            return negative && !digits.equals("0") ? "-" + digits : digits;
        }
        try {
            final BigDecimal number = new BigDecimal(s);
            // Keep explicit scientific notation compact, even for extreme exponents.
            final int exponent = Math.max(s.indexOf('e'), s.indexOf('E'));
            if (exponent >= 0) {
                final String mantissa = new BigDecimal(s.substring(0, exponent)).stripTrailingZeros().toPlainString();
                return mantissa + "e" + new BigInteger(s.substring(exponent + 1));
            }
            return number.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }

    /**
     * Parses uppercase, canonical Roman notation and optional localized suffixes.
     * Single-letter ASCII Romans require a configured contextual cue unless
     * their ordinal suffix consists entirely of Unicode modifier letters.
     * Plain alphabetic suffixes alone are ambiguous (e.g. Le, Ces, Des).
     * @param raw original token, including case
     * @return canonical cardinal or ordinal, or null
     */
    private String normalizeRoman(final String raw)
    {
        if (raw.isEmpty() || ("IVXLCDM".indexOf(raw.charAt(0)) < 0
                && (raw.charAt(0) < '\u2160' || raw.charAt(0) > '\u216F'))) return null;
        final StringBuilder roman = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            final char c = raw.charAt(i);
            if ("IVXLCDM".indexOf(c) >= 0) roman.append(c);
            else if (c >= '\u2160' && c <= '\u216F') roman.append(Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC));
            else break;
            i++;
        }
        if (roman.length() == 0 || roman.length() > 15) return null;
        final String suffix = i == raw.length() ? "" : lexicon.get("@suffix:" + raw.substring(i));
        if (suffix == null) return null;
        if (roman.length() == 1 && raw.charAt(0) < '\u2160' && !romanCue) {
            // A plain suffix is not evidence that a capitalized word is a number.
            if (i == raw.length() || !raw.substring(i).codePoints()
                    .allMatch(c -> Character.getType(c) == Character.MODIFIER_LETTER)) return null;
        }
        int value = 0;
        int previous = 0;
        for (int p = roman.length() - 1; p >= 0; p--) {
            final int current = switch (roman.charAt(p)) {
                case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50;
                case 'C' -> 100; case 'D' -> 500; case 'M' -> 1000; default -> 0;
            };
            value += current < previous ? -current : current;
            previous = Math.max(previous, current);
        }
        if (value < 1 || value > 3999) return null;
        final String[] thousands = {"", "M", "MM", "MMM"};
        final String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        final String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        final String[] units = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        final String canonical = thousands[value / 1000] + hundreds[value / 100 % 10]
            + tens[value / 10 % 10] + units[value % 10];
        return canonical.contentEquals(roman) ? value + suffix : null;
    }

    /**
     * Canonicalizes lexical hyphenation for dictionary lookup only.
     * @param form original word or expression
     * @return space-separated lookup form
     */
    private static String wordKey(final String form)
    {
        return form.replace('-', ' ').replace('‐', ' ').replace('‑', ' ');
    }

    /**
     * Tests for a nonempty unsigned ASCII integer.
     * @param s candidate string
     * @return whether all characters are digits
     */
    private static boolean isUnsignedInteger(final String s)
    {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    /**
     * Extracts the integer part of an unsigned cardinal or ordinal.
     * @param s canonical block
     * @return numeric value, or null
     */
    private static BigInteger integerPart(final String s)
    {
        final String digits = s.substring(0, s.length() - ordinalSuffix(s).length());
        return isUnsignedInteger(digits) ? new BigInteger(digits) : null;
    }

    /**
     * Returns the suffix of a canonical ordinal (not a scientific exponent).
     * @param s canonical number
     * @return ordinal suffix or empty string
     */
    private static String ordinalSuffix(final String s)
    {
        if (s.endsWith("er") && isUnsignedInteger(s.substring(0, s.length() - 2))) return "er";
        if (s.endsWith("e") && isUnsignedInteger(s.substring(0, s.length() - 1))) return "e";
        return "";
    }

    /**
     * Tests whether a canonical number is ordinal.
     * @param s canonical number
     * @return whether an ordinal suffix is present
     */
    private static boolean isOrdinal(final String s)
    {
        return !ordinalSuffix(s).isEmpty();
    }
}
