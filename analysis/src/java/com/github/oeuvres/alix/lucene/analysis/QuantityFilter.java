package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Recognizes a numerical value followed by a localized measurement unit.
 * Units are matched case-sensitively and longest first, with up to three
 * component tokens. The surface expression is retained, with the dimension
 * as lemma and NOUNqty as POS. Numerical interpretation belongs to NumberFilter.
 */
public final class QuantityFilter extends CompoundFilterBase
{
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final CharArrayMap<char[]> units;
    private final CharArraySet prefixes = new CharArraySet(32, false);

    /**
     * Creates a quantity filter.
     * @param input stream after numerical, age and reference recognition
     * @param units case-sensitive mapping of unit forms to dimension lemmas;
     *              multi-token units use single spaces between components
     */
    public QuantityFilter(final TokenStream input, final CharArrayMap<char[]> units)
    {
        super(input, 4);
        this.units = Objects.requireNonNull(units, "units");
        for (Object key : units.keySet()) {
            final String name = key instanceof char[] chars ? new String(chars) : key.toString();
            for (int i = name.indexOf(' '); i >= 0; i = name.indexOf(' ', i + 1)) {
                prefixes.add(name.substring(0, i));
            }
        }
    }

    /**
     * Blocks punctuation, markup, empty tokens and noncontiguous positions.
     * @param token buffered token
     * @return true if the token may extend the span
     */
    @Override
    protected boolean canJoin(final AttributeSource token)
    {
        final Upos tag = Upos.get(token.getAttribute(PosAttribute.class).getPos());
        return super.canJoin(token) && tag != null && tag != Upos.XML && tag.base() != Upos.PUNCT
            && token.getAttribute(CharTermAttribute.class).length() != 0;
    }

    /**
     * Recognizes the longest known unit after the numerical token.
     * @return true if a compound is emitted
     * @throws IOException if lookahead fails
     */
    @Override
    protected boolean compound() throws IOException
    {
        if (!mayStart(at(0))) return false;
        int count = 0;
        char[] best = null;
        AttributeSource previous = null;
        final StringBuilder key = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            final AttributeSource unit = at(i);
            if (unit == null) break;
            final CharTermAttribute component = unit.getAttribute(CharTermAttribute.class);
            if (previous != null) {
                // A separated ordinary digit can be the next observation,
                // not an exponent. Only permit ASCII powers when attached.
                if (("2".contentEquals(component) || "3".contentEquals(component))
                        && !adjacent(previous, unit)) break;
                key.append(' ');
            }
            key.append(component);
            previous = unit;
            final char[] dimension = units.get(key);
            if (dimension != null) {
                count = i + 1;
                best = dimension;
            }
            if (!prefixes.contains(key)) break;
        }
        if (best == null) return false;
        merge(count, true);
        lemmaAtt.copyBuffer(best, 0, best.length);
        posAtt.setPos(Upos.NOUNqty.ordinal());
        lenAtt.setPositionLength(1);
        return true;
    }

    /**
     * Tests for a resolved cardinal or an isolated numerical candidate.
     * Ordinals and already aggregated ages/references are not quantities.
     * @param token source token
     * @return true for a potential quantity value
     */
    @Override
    protected boolean mayStart(final AttributeSource token)
    {
        if (token.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() == 0
                || token.getAttribute(PositionLengthAttribute.class).getPositionLength() != 1) return false;
        final Upos tag = Upos.get(token.getAttribute(PosAttribute.class).getPos());
        if (tag == Upos.NUMcard) return token.getAttribute(LemmaAttribute.class).length() != 0;
        if (tag != Upos.TOKEN) return false;
        final CharSequence value = token.getAttribute(LemmaAttribute.class);
        if (value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }

    /**
     * Tests source adjacency for an ambiguous ASCII exponent.
     * @param left preceding token
     * @param right following token
     * @return true when the source offsets touch
     */
    private static boolean adjacent(final AttributeSource left, final AttributeSource right)
    {
        return left.getAttribute(OffsetAttribute.class).endOffset()
            == right.getAttribute(OffsetAttribute.class).startOffset();
    }
}
