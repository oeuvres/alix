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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
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
 * empty {@link LemmaAttribute}, because its term is already canonical.</p>
 *
 * <p>The filter must run after normalization and lemmatization annotation, but
 * before destructive lemma projection, stop-word removal, or stemming.</p>
 */
public final class MweFilter extends TokenFilter
{
    /** Token type assigned to compounded multi-word expressions. */
    public static final String TYPE_MWE = "MWE";

    /** Whether the upstream input has been exhausted. */
    private boolean inputExhausted;

    /** Optional lemma override populated by the upstream lemmatizer. */
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);

    /** Frozen lexicon and automaton used for both matching paths. */
    private final MweLexicon lexicon;

    /** Output offsets. */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);

    /** Buffered token states, including one look-ahead token. */
    private final TokenStateQueue queue;

    /** Normalized token text. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

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
        super(input);
        lexicon.freeze();
        this.lexicon = lexicon;
        this.queue = new TokenStateQueue(lexicon.maxLen() + 1, this);
    }

    /**
     * Emits the next ordinary or compounded token.
     *
     * @return {@code true} when a token was emitted, otherwise {@code false}
     * @throws IOException if the upstream stream cannot be read
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        int formState = lexicon.root();
        int lemmaState = lexicon.root();
        int matchPos = -1;
        int matchOrd = -1;
        boolean dead = false;

        /*
         * Re-walk buffered tail tokens. A previous call may have consumed
         * look-ahead beyond the emitted token.
         */
        for (int i = 0; i < queue.size(); i++) {
            final AttributeSource slot = queue.get(i);

            formState = stepForm(formState, slot);
            lemmaState = stepLemma(lemmaState, slot);

            if (formState < 0 && lemmaState < 0) {
                dead = true;
                break;
            }

            /*
             * Record lemma first and form second: when both accept at this
             * position, the exact normalized-form match takes precedence.
             */
            if (lemmaState >= 0) {
                final int accepted = lexicon.accept(lemmaState);
                if (accepted >= 0) {
                    matchPos = i;
                    matchOrd = accepted;
                }
            }
            if (formState >= 0) {
                final int accepted = lexicon.accept(formState);
                if (accepted >= 0) {
                    matchPos = i;
                    matchOrd = accepted;
                }
            }
        }

        /*
         * Continue with fresh input until both paths die or input ends.
         */
        while (!dead && !inputExhausted) {
            if (!input.incrementToken()) {
                inputExhausted = true;
                break;
            }

            queue.addLast(this);
            final int pos = queue.size() - 1;
            final AttributeSource slot = queue.get(pos);

            formState = stepForm(formState, slot);
            lemmaState = stepLemma(lemmaState, slot);

            if (formState < 0 && lemmaState < 0) {
                dead = true;
                break;
            }

            if (lemmaState >= 0) {
                final int accepted = lexicon.accept(lemmaState);
                if (accepted >= 0) {
                    matchPos = pos;
                    matchOrd = accepted;
                }
            }
            if (formState >= 0) {
                final int accepted = lexicon.accept(formState);
                if (accepted >= 0) {
                    matchPos = pos;
                    matchOrd = accepted;
                }
            }
        }

        if (queue.isEmpty()) {
            return false;
        }

        if (matchPos >= 0) {
            emitMerged(matchPos, matchOrd);
            return true;
        }

        /*
         * No expression begins at the queue head. Emit that token unchanged;
         * later buffered tokens remain available as possible MWE starts.
         */
        queue.removeFirst(this);
        return true;
    }

    /**
     * Resets this filter and discards all buffered states.
     *
     * @throws IOException if the upstream stream cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        queue.clear();
        inputExhausted = false;
    }

    /**
     * Emits one canonical token for an accepted expression.
     *
     * @param matchPos zero-based position of the final matched queue slot
     * @param matchOrd ordinal of the canonical expression in the lexicon
     */
    private void emitMerged(final int matchPos, final int matchOrd)
    {
        final int endOffset = queue.get(matchPos)
            .getAttribute(OffsetAttribute.class)
            .endOffset();

        /*
         * Preserve the first component's position increment, start offset,
         * flags, and other attributes.
         */
        queue.restoreTo(this, 0);

        /*
         * The canonical MWE is stored directly in termAtt. Do not leak the
         * first component's lemma into the compounded token.
         */
        lemmaAtt.setEmpty();

        final int length = lexicon.formLength(matchOrd);
        final char[] buffer = termAtt.resizeBuffer(length);
        lexicon.copy(matchOrd, buffer, 0);
        termAtt.setLength(length);

        offsetAtt.setOffset(offsetAtt.startOffset(), endOffset);
        typeAtt.setType(TYPE_MWE);

        for (int i = 0; i <= matchPos; i++) {
            queue.removeFirst();
        }
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
