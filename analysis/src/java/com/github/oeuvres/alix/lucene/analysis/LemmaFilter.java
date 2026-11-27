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

import static com.github.oeuvres.alix.util.LemmaLexicon.NO_LEMMA;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.util.TermProbe;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.LemmaLexicon;

/**
 * Resolves numerical lemma candidates and looks up lexical lemmas after POS tagging.
 *
 * <p>Confirmed numerical annotations, canonical ages and quantities, symbols,
 * keywords, proper names and canonical MWEs are protected. Ambiguous numerical
 * candidates from {@link NumberFilter} are accepted only with compatible POS;
 * otherwise they are cleared before lexical lookup. This relies on the current
 * upstream contract: unresolved numerical candidates are unsigned integers or
 * integer ordinals ending in {@code e} or {@code er}.</p>
 *
 * <p>Lexical lookup tries the exact POS, its standard UPOS base, then the
 * POS-independent mapping. The last fallback is not used for rejected numeric
 * candidates, so an unrelated dictionary sense cannot restore the candidate.
 * Other upstream lemma overrides are preserved.</p>
 *
 * <p>Known capitalized common words are normalized to lowercase, except
 * acronyms and protected proper names. Unknown capitalized forms are retained.
 * Sentence boundaries reset the sentence-start state. XML and punctuation do
 * not consume it. POS is never changed by lexical lookup; accepted numerical
 * candidates are refined to NUMcard or ADJord.</p>
 */
public final class LemmaFilter extends TokenFilter
{
    /** Lemma lexicon for common-word forms. */
    private final LemmaLexicon lexicon;

    /** Optional set of proper names protected from common-word lemmatization. */
    private final CharArraySet propn;

    /** Reusable probe for lowercase dictionary lookup. */
    private final TermProbe probe = new TermProbe();

    /** Current surface token. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /** Keyword marker used to suppress lemmatization. */
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);

    /** Current POS supplied by upstream analysis. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    
    /** Token type identifies already canonical multiword expressions. */
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    /** Sparse output lemma channel. */
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);

    /** Whether the next lexical token is sentence-initial. */
    private boolean sentenceStart = true;

    /**
     * Creates a lemmatization side-channel filter without a proper-name set.
     *
     * @param input input token stream
     * @param lexicon lemma lexicon used to resolve forms and lemmas
     * @throws NullPointerException if {@code input} or {@code lexicon} is null
     */
    public LemmaFilter(final TokenStream input, final LemmaLexicon lexicon)
    {
        this(input, lexicon, null);
    }

    /**
     * Creates a lemmatization side-channel filter.
     *
     * @param input input token stream
     * @param lexicon lemma lexicon used to resolve common-word forms and lemmas
     * @param propn optional set of protected proper-name surface forms
     * @throws NullPointerException if {@code input} or {@code lexicon} is null
     */
    public LemmaFilter(
        final TokenStream input,
        final LemmaLexicon lexicon,
        final CharArraySet propn
    ) {
        super(Objects.requireNonNull(input, "input"));
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        this.propn = propn;
    }

    /**
     * Advances the stream by one token and populates {@link LemmaAttribute} when
     * a distinct or case-normalized lemma can be resolved.
     *
     * @return {@code true} if a token is available; {@code false} at end of stream
     * @throws IOException if the input stream throws while advancing
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        lemmaAtt.setEmpty();
        if (!input.incrementToken()) return false;

        final Upos pos = Upos.get(posAtt.getPos());
        if (pos == null) {
            throw new IllegalStateException("Invalid upstream POS: " + posAtt.getPos());
        }
        final Upos base = pos.base();
        if (isSentenceBoundary(pos.ordinal())) {
            sentenceStart = true;
            return true;
        }
        if (pos == Upos.XML || base == Upos.PUNCT || termAtt.length() == 0) return true;

        final boolean atSentenceStart = sentenceStart;
        sentenceStart = false;

        // These annotations have already been resolved by upstream rules.
        if (pos == Upos.NUMcard || pos == Upos.ADJord
                || pos == Upos.NOUNage || pos == Upos.NOUNqty || base == Upos.SYM) {
            return true;
        }

        // Only unresolved numerical candidates need contextual validation.
        final Upos candidate = numericCandidate(lemmaAtt);
        if (candidate != null) {
            if (!keywordAtt.isKeyword() && (base == Upos.NUM
                    || (candidate == Upos.ADJord && base == Upos.ADJ))) {
                posAtt.setPos(candidate.ordinal());
                return true;
            }
            lemmaAtt.setEmpty();
        }
        // Retain other upstream lemmas and canonical expressions.
        if (lemmaAtt.length() != 0 || keywordAtt.isKeyword() || base == Upos.NUM
                || base == Upos.PROPN || MweFilter.TYPE_MWE.equals(typeAtt.type())) {
            return true;
        }

        final boolean uppercase = Char.isUpperCase(termAtt.charAt(0));
        int termId;
        if (uppercase) {
            // Protect acronyms and sentence-internal known proper names.
            if (termAtt.length() > 1 && Char.isUpperCase(termAtt.charAt(1))) return true;
            if (!atSentenceStart && propn != null && propn.contains(termAtt)) return true;
            probe.copyFrom(termAtt).toLowerCase();
            termId = lexicon.ord(probe);
            if (termId < 0) return true;
            // Keep the existing common-word case normalization policy.
            termAtt.setEmpty().append(probe);
        }
        else {
            termId = lexicon.ord(termAtt);
            if (termId < 0) return true;
        }

        final int lemmaId = lookup(termId, pos, candidate == null);
        if (lemmaId >= 0 && lemmaId != termId) copyLemma(lemmaId);
        return true;
    }

    /**
     * Resets sentence-position state for a reused token stream.
     *
     * @throws IOException if the upstream stream cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        sentenceStart = true;
        probe.clear();
    }

    /**
     * Copies one interned lexicon entry to the lemma output attribute.
     *
     * @param lemmaId lexicon ordinal of the lemma
     */
    private void copyLemma(final int lemmaId)
    {
        final int len = lexicon.length(lemmaId);
        final char[] dst = lemmaAtt.resizeBuffer(len);
        lexicon.copy(lemmaId, dst, 0);
        lemmaAtt.setLength(len);
    }

    /**
     * Looks up a lemma using the exact POS, its standard base and optionally
     * the POS-independent mapping.
     * @param termId interned surface form
     * @param pos upstream grammatical tag
     * @param fallback whether a POS-independent mapping may be used
     * @return lemma ordinal, or {@link LemmaLexicon#NO_LEMMA}
     */
    private int lookup(final int termId, final Upos pos, final boolean fallback)
    {
        final Upos base = pos.base();
        int lemmaId = NO_LEMMA;
        if (base != null) {
            lemmaId = lexicon.lemmaId(termId, pos.ordinal());
            if (lemmaId == NO_LEMMA && base != pos) {
                lemmaId = lexicon.lemmaId(termId, base.ordinal());
            }
        }
        if (lemmaId == NO_LEMMA && fallback) lemmaId = lexicon.lemmaId(termId);
        return lemmaId;
    }

    /**
     * Identifies an unresolved integer or ordinal candidate supplied by NumberFilter.
     * The numeric grammar itself remains in NumberFilter.
     * @param lemma upstream lemma value
     * @return canonical numeric category, or null for a noncandidate
     */
    private static Upos numericCandidate(final CharSequence lemma)
    {
        final int length = lemma.length();
        if (length == 0) return null;
        int i = 0;
        while (i < length && lemma.charAt(i) >= '0' && lemma.charAt(i) <= '9') i++;
        if (i == 0) return null;
        if (i == length) return Upos.NUMcard;
        if (lemma.charAt(i) == 'e' && (i + 1 == length
                || (i + 2 == length && lemma.charAt(i + 1) == 'r'))) return Upos.ADJord;
        return null;
    }

    /**
     * Tests whether a structural POS code starts a new sentence context.
     *
     * @param posId POS code
     * @return {@code true} for sentence, paragraph, section, or structural boundaries
     */
    private static boolean isSentenceBoundary(final int posId)
    {
        return posId == Upos.PUNCTsent.ordinal()
            || posId == Upos.PUNCTpara.ordinal()
            || posId == Upos.PUNCTsection.ordinal()
            || posId == Upos.PUNCTstruct.ordinal();
    }
}
