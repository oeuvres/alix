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

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.util.TermProbe;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.LemmaLexicon;

/**
 * Populates a parallel lemma channel for each token using a {@link LemmaLexicon}.
 *
 * <p>This filter never changes the token text carried by {@link CharTermAttribute}.
 * Resolved lemmas are written to {@link LemmaAttribute}; an empty lemma means that
 * no lemma override is required and downstream code may retain the surface form.
 *
 * <h2>Lookup policy</h2>
 *
 * <p>The filter gives precedence to information that is stronger than a generic
 * dictionary lookup:
 * <ol>
 *   <li>An uppercase form present in the supplied proper-name set is protected,
 *       tagged {@code PROPN}, and is not lemmatized through the word lexicon.</li>
 *   <li>A token already tagged as {@code PROPN} (or a project-specific PROPN
 *       subtype) is protected from common-word lemmatization. Proper-name
 *       normalization belongs to a separate resource.</li>
 *   <li>When a POS is present, lemma lookup first uses that POS. If the
 *       POS-specific mapping is absent, lookup falls back to the POS-agnostic
 *       mapping. The tagger POS is evidence, not an authority for lemma choice.</li>
 *   <li>For an uppercase form unknown in its original case, lowercase lookup is
 *       attempted. If the lowercase form is also absent, the token is tagged
 *       {@code PROPN}, even when the statistical tagger proposed another POS.</li>
 *   <li>When POS itself is unknown, sentence position adds a further fallback:
 *       an uppercase token inside a sentence is treated as a proper name, whereas
 *       an uppercase token at sentence start may be probed in lowercase.</li>
 *   <li>A POS-agnostic lemma is therefore both the fallback after a failed
 *       POS-specific lookup and the direct lookup when POS is unknown.</li>
 * </ol>
 *
 * <p>Lowercase probing never rewrites {@code CharTermAttribute}. If the lowercase
 * dictionary form is itself the lemma, it is copied to {@code LemmaAttribute};
 * for example surface {@code Mais} may yield lemma {@code mais}.
 *
 * <h2>Sentence-start state</h2>
 *
 * <p>Start of stream is treated as sentence start. Sentence, paragraph, section,
 * and structural boundaries reset the state. XML and other punctuation do not
 * consume it. The first non-punctuation token consumes the state even when that
 * token is a keyword or a number.
 *
 * <h2>Tokens ignored by design</h2>
 *
 * <p>No lemma is written for XML, punctuation, numbers, keywords, protected or
 * tagged proper names, acronyms protected from lowercase probing, unknown forms,
 * or forms for which neither a POS-specific nor a POS-agnostic lemma can be found.
 *
 * @see LemmaAttribute
 * @see LemmaLexicon
 * @see PosAttribute
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
        if (!input.incrementToken()) {
            return false;
        }

        final int posId = posAtt.getPos();

        if (isSentenceBoundary(posId)) {
            sentenceStart = true;
            return true;
        }

        // XML and non-boundary punctuation do not consume sentence-start state.
        if (posId == Upos.XML.code || Upos.isPunct(posId)) {
            return true;
        }

        if (termAtt.length() < 1) {
            return true;
        }

        // Any non-punctuation token consumes sentence-start state, even if skipped below.
        final boolean atSentenceStart = sentenceStart;
        sentenceStart = false;

        if (keywordAtt.isKeyword() || Upos.isNum(posId)) {
            return true;
        }

        final boolean uppercase = Char.isUpperCase(termAtt.charAt(0));
        final boolean hasPos = hasPos(posId);

        // Explicit proper-name protection has priority over the common-word lexicon.
        if (uppercase && propn != null && propn.contains(termAtt)) {
            posAtt.setPos(Upos.PROPN.code);
            return true;
        }

        // word.csv must never normalize a token already identified as a proper name.
        if (isProperName(posId)) {
            return true;
        }

        // With no POS information, internal capitalization is our proper-name fallback.
        if (uppercase && !hasPos && !atSentenceStart) {
            posAtt.setPos(Upos.PROPN.code);
            return true;
        }

        // Protect acronyms from accidental lowercase lexical matches (USA != user, etc.).
        if (uppercase && termAtt.length() > 1 && Char.isUpperCase(termAtt.charAt(1))) {
            return true;
        }

        int termId = lexicon.ord(termAtt);
        boolean lowercaseLookup = false;

        // A capitalized unknown form may be a sentence-initial common word, or may
        // have a non-PROPN POS proposal supplied by an upstream tagger.
        if (termId < 0 && uppercase) {
            probe.copyFrom(termAtt).toLowerCase();
            termId = lexicon.ord(probe);
            lowercaseLookup = (termId >= 0);
        }

        if (termId < 0) {
            // No exact or lowercase dictionary form exists. In this case,
            // capitalization is stronger evidence than a non-PROPN proposal
            // from the statistical tagger: restore the proper-name fallback.
            if (uppercase) {
                posAtt.setPos(Upos.PROPN.code);
            }
            return true;
        }

        int lemmaId = LemmaLexicon.NO_LEMMA;
        if (hasPos) {
            // The tagger POS is tried first, but it is only a proposal. A missing
            // POS-specific mapping must not prevent the dictionary fallback below.
            lemmaId = lexicon.lemmaId(termId, posId);
        }

        // POS-agnostic dictionary fallback. This is also the direct lookup when
        // no usable POS was supplied upstream.
        if (lemmaId < 0) {
            lemmaId = lexicon.lemmaId(termId);
        }

        if (lemmaId < 0) {
            return true;
        }

        // For an exact-case lookup, identical form and lemma need no override.
        // For a lowercase probe, they still differ from the original surface case.
        if (lemmaId == termId && !lowercaseLookup) {
            return true;
        }

        copyLemma(lemmaId);
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
     * Tests whether a POS value is available for a first, POS-specific lemma lookup.
     *
     * @param posId POS code
     * @return {@code true} unless the POS is unset or explicitly unknown
     */
    private static boolean hasPos(final int posId)
    {
        return posId > PosAttribute.UNKNOWN && posId != Upos.UNKNOWN.code;
    }

    /**
     * Tests the PROPN code family, including project-specific PROPN subtypes.
     *
     * @param posId POS code
     * @return {@code true} for a proper-name POS
     */
    private static boolean isProperName(final int posId)
    {
        return posId >= Upos.PROPN.code && posId <= Upos.PROPNgod.code;
    }

    /**
     * Tests whether a structural POS code starts a new sentence context.
     *
     * @param posId POS code
     * @return {@code true} for sentence, paragraph, section, or structural boundaries
     */
    private static boolean isSentenceBoundary(final int posId)
    {
        return posId == Upos.PUNCTsent.code
            || posId == Upos.PUNCTpara.code
            || posId == Upos.PUNCTsection.code
            || posId == Upos.PUNCTstruct.code;
    }
}
