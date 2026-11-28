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

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.MweLexicon;


/**
 * Merges multi-word expressions (MWEs) into single tokens using a
 * {@link MweLexicon}.
 *
 * <p>The filter evaluates two deterministic paths over the same lexicon:</p>
 *
 * <ul>
 *   <li>the normalized-form path reads {@link CharTermAttribute};</li>
 *   <li>the lemma path reads {@link LemmaAttribute} when non-empty and otherwise
 *       falls back to {@link CharTermAttribute}.</li>
 * </ul>
 *
 * <p>This permits the same lexicon to recognize both fixed-form expressions,
 * such as {@code a priori}, and inflection-independent expressions, such as
 * {@code avoir lieu}. For a token whose normalized form is {@code a} and whose
 * lemma is {@code avoir}, the form path consumes {@code a}, while the lemma path
 * consumes {@code avoir}.</p>
 *
 * <p>Matching uses maximal munch: the longest accepted expression wins. If the
 * two paths accept expressions of equal length, the normalized-form match wins
 * because it is the more specific analysis.</p>
 *
 * <p>The filter has replace-only output semantics. The component tokens are
 * replaced by one token carrying the canonical form stored in the lexicon.
 * {@link PositionIncrementAttribute} is inherited from the first component,
 * {@link OffsetAttribute} spans the complete expression, and
 * {@link TypeAttribute} is set to {@link #TYPE_MWE}. The merged token has an
 * empty {@link LemmaAttribute}, because its term is already canonical. Its POS
 * is the tag registered for the canonical form, or zero when unspecified,
 * never the POS inherited from its first component.</p>
 *
 * <p>The filter must run after normalization and lemmatization annotation, but
 * before destructive lemma projection, stop-word removal, or stemming.</p>
 */
public final class MweFilter extends CompoundFilterBase
{
    /** Token type assigned to compounded multi-word expressions. */
    public static final String TYPE_MWE = "MWE";

    /** Optional lemma override populated by the upstream lemmatizer. */
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);

    /** Frozen lexicon and automaton used for both matching paths. */
    private final MweLexicon lexicon;

    /** Output POS, assigned from the accepted lexicon entry. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Output token type. */
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    /**
     * Constructs an MWE filter.
     *
     * @param input upstream token stream containing normalized forms and optional
     *              lemma annotations
     * @param lexicon MWE lexicon; it is frozen by this constructor
     */
    public MweFilter(final TokenStream input, final MweLexicon lexicon)
    {
        super(input, capacity(lexicon));
        this.lexicon = lexicon;
    }

    /**
     * Retains the existing dictionary-driven positional policy for MWEs.
     * @param token buffered token
     * @return true; the automaton determines whether this component matches
     */
    @Override
    protected boolean canJoin(final AttributeSource token)
    {
        return true;
    }

    /**
     * Finds the longest match on either automaton path, preferring form over
     * lemma when both accept at the same position.
     * @return true if a canonical expression is emitted
     * @throws IOException if lookahead fails
     */
    @Override
    protected boolean compound() throws IOException
    {
        int formState = lexicon.root();
        int lemmaState = lexicon.root();
        int count = 0;
        int matchOrd = -1;
        for (int i = 0; ; i++) {
            final AttributeSource slot = at(i);
            if (slot == null) break;
            formState = stepForm(formState, slot);
            lemmaState = stepLemma(lemmaState, slot);
            if (formState < 0 && lemmaState < 0) break;
            final int formOrd = formState < 0 ? -1 : lexicon.accept(formState);
            final int lemmaOrd = lemmaState < 0 ? -1 : lexicon.accept(lemmaState);
            final int accepted = formOrd >= 0 ? formOrd : lemmaOrd;
            if (accepted >= 0) {
                count = i + 1;
                matchOrd = accepted;
            }
        }
        if (matchOrd < 0) return false;
        merge(count, false);
        lemmaAtt.setEmpty();
        posAtt.setPos(lexicon.pos(matchOrd));
        final int length = lexicon.formLength(matchOrd);
        final char[] buffer = termAtt.resizeBuffer(length);
        lexicon.copy(matchOrd, buffer, 0);
        termAtt.setLength(length);
        typeAtt.setType(TYPE_MWE);
        return true;
    }

    /**
     * Avoids buffering ordinary words absent from both automaton root paths.
     * @param token live input token
     * @return true if either path can begin a match
     */
    @Override
    protected boolean mayStart(final AttributeSource token)
    {
        final int root = lexicon.root();
        return stepForm(root, token) >= 0 || stepLemma(root, token) >= 0;
    }

    /**
     * Freezes the lexicon before determining its lookahead requirement.
     * @param lexicon expression dictionary
     * @return maximum expression length plus one lookahead slot
     */
    private static int capacity(final MweLexicon lexicon)
    {
        lexicon.freeze();
        return lexicon.maxLen() + 1;
    }

    /**
     * Advances the normalized-form automaton path with a buffered token.
     *
     * @param state current automaton state
     * @param slot buffered token attributes
     * @return next automaton state, or a negative value if the path is dead
     */
    private int stepForm(final int state, final AttributeSource slot)
    {
        if (state < 0) {
            return state;
        }

        final CharTermAttribute form =
            slot.getAttribute(CharTermAttribute.class);

        return lexicon.step(state, form.buffer(), form.length());
    }

    /**
     * Advances the canonical lemma path with a buffered token.
     *
     * <p>An empty lemma channel means that the normalized form is already the
     * canonical representation or that no lemma is available.</p>
     *
     * @param state current automaton state
     * @param slot buffered token attributes
     * @return next automaton state, or a negative value if the path is dead
     */
    private int stepLemma(final int state, final AttributeSource slot)
    {
        if (state < 0) {
            return state;
        }

        final LemmaAttribute lemma =
            slot.getAttribute(LemmaAttribute.class);

        if (lemma.length() > 0) {
            return lexicon.step(state, lemma.buffer(), lemma.length());
        }

        final CharTermAttribute form =
            slot.getAttribute(CharTermAttribute.class);

        return lexicon.step(state, form.buffer(), form.length());
    }
}
