package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Recognizes numbered references using an injected localized introducer set.
 * Accepts introducer + integer/ordinal or ordinal + introducer, optionally
 * followed by a Latin suffix. NumberFilter supplies the numerical lemmas.
 * The surface expression is retained, with lemma {REF} and POS NUMref.
 */
public final class ReferenceFilter extends CompoundFilterBase
{
    /** Canonical lemma for numbered references. */
    public static final String REFERENCE = "{REF}";
    private static final Set<String> SUFFIXES = Set.of("bis", "ter", "quater",
        "quinquies", "sexies", "septies", "octies", "nonies", "decies");
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final CharArraySet introducers;

    /**
     * Creates a reference filter.
     * @param input stream after numerical and age recognition
     * @param introducers localized reference introducers, normally case insensitive
     */
    public ReferenceFilter(final TokenStream input, final CharArraySet introducers)
    {
        super(input, 3);
        this.introducers = Objects.requireNonNull(introducers, "introducers");
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
     * Recognizes a reference in either supported order.
     * @return true if a compound is emitted
     * @throws IOException if lookahead fails
     */
    @Override
    protected boolean compound() throws IOException
    {
        final AttributeSource first = at(0);
        if (!mayStart(first)) return false;
        final AttributeSource second = at(1);
        if (second == null) return false;
        if (!(isIntroducer(first) && numberKind(second) != 0)
                && !(numberKind(first) == 2 && isIntroducer(second))) return false;
        final AttributeSource suffix = at(2);
        final int end = suffix != null && SUFFIXES.contains(suffix.getAttribute(CharTermAttribute.class).toString().toLowerCase(Locale.ROOT)) ? 3 : 2;
        merge(end, true);
        lemmaAtt.setEmpty();
        lemmaAtt.append(REFERENCE);
        posAtt.setPos(Upos.NUMref.ordinal());
        lenAtt.setPositionLength(1);
        return true;
    }

    /**
     * Tests whether a token can begin a reference.
     * @param token source token
     * @return true for an introducer or ordinal
     */
    @Override
    protected boolean mayStart(final AttributeSource token)
    {
        return token.getAttribute(PositionIncrementAttribute.class).getPositionIncrement() != 0
            && token.getAttribute(PositionLengthAttribute.class).getPositionLength() == 1
            && (isIntroducer(token) || numberKind(token) == 2);
    }

    /**
     * Checks a localized introducer without interpreting its grammatical meaning.
     * @param token source token
     * @return true for a dictionary member
     */
    private boolean isIntroducer(final AttributeSource token)
    {
        final Upos tag = Upos.get(token.getAttribute(PosAttribute.class).getPos());
        return tag != null && tag != Upos.XML && tag.base() != Upos.PUNCT
            && tag.base() != Upos.SYM && introducers.contains(token.getAttribute(CharTermAttribute.class));
    }

    /**
     * Distinguishes upstream integer and ordinal lemmas, including unresolved
     * candidates such as second -> 2e. Does not parse number words or Roman numerals.
     * @param token source token
     * @return 0 for no candidate, 1 for integer, 2 for ordinal
     */
    private static int numberKind(final AttributeSource token)
    {
        final Upos tag = Upos.get(token.getAttribute(PosAttribute.class).getPos());
        if (tag != Upos.NUMcard && tag != Upos.ADJord && tag != Upos.TOKEN) return 0;
        final CharSequence value = token.getAttribute(LemmaAttribute.class);
        int i = 0;
        while (i < value.length() && value.charAt(i) >= '0' && value.charAt(i) <= '9') i++;
        if (i == 0) return 0;
        if (i == value.length()) return tag == Upos.ADJord ? 0 : 1;
        if (tag == Upos.NUMcard) return 0;
        return value.charAt(i) == 'e' && (i + 1 == value.length()
                || (i + 2 == value.length() && value.charAt(i + 1) == 'r')) ? 2 : 0;
    }
}
