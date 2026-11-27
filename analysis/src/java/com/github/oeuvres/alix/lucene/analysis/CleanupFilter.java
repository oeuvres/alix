/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 * Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 *
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010 Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;

/**
 * Final field-aware cleanup and projection of annotated tokens.
 *
 * <p>Markup and dictionary-listed noise are removed without adding positional
 * gaps. Punctuation, symbols, empty terms and rejected lexical entries are
 * removed with their positional increments preserved. Structural boundaries
 * survive removal and are attached to the next emitted token.</p>
 *
 * <p>Lexical exclusion sets are consulted on the resolved lemma, when present,
 * or on the surface term otherwise. Output uses the same canonical form by
 * default. Fields beginning with {@code word2vec} replace NUM-family tokens
 * with {@code #}; other fields retain numerical values. Ordinals and aggregated
 * ages are not NUM-family tokens and retain their canonical values.</p>
 *
 * <p>All grammatical interpretation belongs to upstream filters. This filter
 * does not assign POS or recognize language-specific forms.</p>
 */
public class CleanupFilter extends TokenFilter
{
    /** Structural boundary preceding the token. */
    private final BoundaryAttribute boundaryAtt = addAttribute(BoundaryAttribute.class);
    /** Field being analyzed, available to field-specific policy overrides. */
    protected final String fieldName;
    /** Resolved lemma, when distinct from the term. */
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);
    /** POS annotation established upstream. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    /** Position increment of the current token. */
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    /** Strongest pending structural boundary. */
    private int pendingBoundary = BoundaryAttribute.NONE;
    /** Sum of position increments from rejected tokens. */
    private int pendingHoles;
    /** Optional lexical exclusions that preserve positional gaps. */
    public final CharArraySet rejected;
    /** Optional noise exclusions that collapse positions. */
    public final CharArraySet skipped;
    /** Surface or canonical term provided by the upstream stream. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Whether the field is a Word2Vec training field. */
    private final boolean word2vec;
    /** Cardinal-number marker for Word2Vec training. */
    protected static final String NUMBER_MARKER = "#";

    /**
     * Creates a field-aware cleanup filter.
     * @param input annotated upstream stream
     * @param fieldName name supplied by the analyzer
     * @param skipped optional noise dictionary, with positions collapsed
     * @param rejected optional lexical exclusion dictionary, with gaps preserved
     */
    public CleanupFilter(final TokenStream input, final String fieldName,
        final CharArraySet skipped, final CharArraySet rejected)
    {
        super(input);
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName");
        this.word2vec = fieldName.startsWith("word2vec");
        this.skipped = skipped;
        this.rejected = rejected;
    }

    /**
     * Preserves trailing gaps and structural boundaries at end of input.
     * @throws IOException if the upstream stream cannot finish
     */
    @Override
    public void end() throws IOException
    {
        super.end();
        posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + pendingHoles);
        boundaryAtt.setBoundary(Math.max(boundaryAtt.getBoundary(), pendingBoundary));
        pendingHoles = 0;
        pendingBoundary = BoundaryAttribute.NONE;
    }

    /**
     * Emits the next accepted token, applying projection and pending gaps.
     * @return true if a token is emitted, false at end of input
     * @throws IOException if the upstream stream cannot advance
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {
            final Upos pos = Upos.get(posAtt.getPos());
            if (pos == null) {
                throw new IllegalStateException("Invalid upstream POS: " + posAtt.getPos());
            }
            pendingBoundary = Math.max(pendingBoundary, boundaryAtt.getBoundary());
            pendingBoundary = Math.max(pendingBoundary, boundary(pos));

            if (termAtt.length() == 0) {
                pendingHoles += posIncrAtt.getPositionIncrement();
                continue;
            }
            if (skip(pos)) continue;
            if (!accept(pos)) {
                pendingHoles += posIncrAtt.getPositionIncrement();
                continue;
            }
            rewriteTerm(pos);
            posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + pendingHoles);
            boundaryAtt.setBoundary(pendingBoundary);
            pendingHoles = 0;
            pendingBoundary = BoundaryAttribute.NONE;
            return true;
        }
        return false;
    }

    /**
     * Resets the filter for another input stream.
     * @throws IOException if the upstream stream cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        pendingHoles = 0;
        pendingBoundary = BoundaryAttribute.NONE;
        boundaryAtt.setBoundary(BoundaryAttribute.NONE);
    }

    /**
     * Determines whether a token is emitted or rejected with a positional gap.
     * @param pos upstream grammatical annotation
     * @return true to emit the token
     */
    protected boolean accept(final Upos pos)
    {
        final Upos base = pos.base();
        return base != Upos.PUNCT && base != Upos.SYM && !contains(rejected);
    }

    /**
     * Determines whether a token is removed without propagating its position.
     * @param pos upstream grammatical annotation
     * @return true to collapse the token's position
     */
    protected boolean skip(final Upos pos)
    {
        if (pos == Upos.XML) return true;
        final Upos base = pos.base();
        if (base == Upos.PUNCT || base == Upos.SYM) return false;
        return contains(skipped);
    }

    /**
     * Selects the lemma or surface representation for Word2Vec training.
     * Canonical indexing always uses the available lemma.
     * @param pos upstream POS ordinal
     * @return true to project the lemma
     */
    protected boolean useLemma(final int pos)
    {
        return true;
    }

    /**
     * Maps resolved structural POS annotations to boundary strength.
     * @param pos upstream grammatical annotation
     * @return structural boundary strength, or NONE
     */
    private static int boundary(final Upos pos)
    {
        return switch (pos) {
            case PUNCTsection -> BoundaryAttribute.SECTION;
            case PUNCTpara -> BoundaryAttribute.PARAGRAPH;
            case PUNCTsent -> BoundaryAttribute.SENTENCE;
            default -> BoundaryAttribute.NONE;
        };
    }

    /**
     * Tests a lexical exclusion set against the canonical lexical form.
     * @param set optional exclusion set
     * @return true if the resolved lemma or fallback term is in the set
     */
    private boolean contains(final CharArraySet set)
    {
        if (set == null) return false;
        if (lemmaAtt.length() != 0) {
            return set.contains(lemmaAtt.buffer(), 0, lemmaAtt.length());
        }
        return set.contains(termAtt.buffer(), 0, termAtt.length());
    }

    /**
     * Projects the field-specific output without altering POS or lemma.
     * @param pos upstream grammatical annotation
     */
    private void rewriteTerm(final Upos pos)
    {
        if (word2vec && pos.base() == Upos.NUM) {
            termAtt.setEmpty().append(NUMBER_MARKER);
        }
        else if (lemmaAtt.length() != 0 && (!word2vec || useLemma(pos.ordinal()))) {
            termAtt.copyBuffer(lemmaAtt.buffer(), 0, lemmaAtt.length());
        }
    }
}
