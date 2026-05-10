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

import com.github.oeuvres.alix.util.MweLexicon;


/**
 * A {@link TokenFilter} that merges multi-word expressions (MWEs) into single tokens,
 * using a {@link MweLexicon} for detection.
 *
 * <p>Matching strategy: maximal munch (longest match). When "New York" and "New York City"
 * are both registered, "New York City" wins if all three tokens are present.</p>
 *
 * <p>Output semantics: replace-only. The N component tokens are replaced by one merged
 * token carrying the canonical form. {@link PositionIncrementAttribute} is taken from the
 * first component; {@link OffsetAttribute} spans from first to last component;
 * {@link TypeAttribute} is set to {@link #TYPE_MWE}. Unigram positions for component
 * words are not preserved (no graph output).</p>
 *
 * <p>The {@link MweLexicon} must be frozen before being passed to this filter.
 * The analyzer used to build the lexicon must match the filters upstream of this one
 * in the analysis chain — normalization (lowercasing, folding) must already have been
 * applied to tokens before they reach this filter.</p>
 *
 * <p>Chain position: after normalization (lowercase, diacritics), before stop-word
 * removal and stemming. MWEs frequently contain stop words ("state of the art",
 * "fin de siècle"); removing stops first destroys the pattern.</p>
 */
public final class MweFilter extends TokenFilter
{
    public static final String TYPE_MWE = "MWE";

    private final MweLexicon lexicon;

    /** Ring-buffer of captured token states; capacity = lexicon.maxLen() + 1. */
    private final TokenStateQueue queue;

    /**
     * Automaton state recorded after consuming each buffered slot.
     * Parallel to the queue's logical indices; sized to queue capacity.
     */
    private final int[] autoState;

    private final CharTermAttribute          termAtt     = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute            offsetAtt   = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt  = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute              typeAtt     = addAttribute(TypeAttribute.class);

    private boolean inputExhausted = false;

    /**
     * @param input   upstream token stream; normalization must already be applied
     * @param lexicon frozen MWE lexicon matching the upstream normalization
     */
    public MweFilter(final TokenStream input, final MweLexicon lexicon)
    {
        super(input);
        this.lexicon   = lexicon;
        final int cap  = lexicon.maxLen() + 1;
        this.queue     = new TokenStateQueue(cap, this);
        this.autoState = new int[cap];
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        // Re-walk any tokens left in the queue from the previous cycle, then
        // continue reading from input. This unified loop handles both the tail
        // of a previous match and fresh input without a separate drain phase,
        // and allows a new MWE to start on buffered tail tokens.

        int state    = lexicon.root();
        int matchPos = -1;
        int matchOrd = -1;

        // Phase 1: re-walk tokens already buffered (tail from previous cycle).
        for (int i = 0; i < queue.size(); i++) {
            final AttributeSource slot     = queue.get(i);
            final CharTermAttribute slotTerm = slot.getAttribute(CharTermAttribute.class);
            state = lexicon.step(state, slotTerm.buffer(), slotTerm.length());
            autoState[i] = state;
            if (state < 0) {
                // Automaton dead inside buffered tail: emit head as-is, leave rest
                // for the next call's re-walk.
                queue.removeFirst(this);
                return true;
            }
            final int acc = lexicon.accept(state);
            if (acc >= 0) { matchPos = i; matchOrd = acc; }
        }

        // Phase 2: read new tokens from input.
        while (!inputExhausted) {
            if (!input.incrementToken()) {
                inputExhausted = true;
                break;
            }
            queue.addLast(this);
            final int slot = queue.size() - 1;
            state = lexicon.step(state, termAtt.buffer(), termAtt.length());
            autoState[slot] = state;
            if (state < 0) break;   // automaton dead; record what we have
            final int acc = lexicon.accept(state);
            if (acc >= 0) { matchPos = slot; matchOrd = acc; }
        }

        if (queue.isEmpty()) return false;  // input exhausted and nothing buffered

        if (matchPos >= 0) {
            emitMerged(matchPos, matchOrd);
            return true;
        }

        // No match: emit head as-is; tail stays for the next call.
        queue.removeFirst(this);
        return true;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        queue.clear();
        inputExhausted = false;
    }

    /**
     * Emits the merged token for the match spanning queue slots {@code [0..matchPos]}.
     * Restores all attributes from slot 0 (preserving posIncr, startOffset, etc.),
     * then overrides term with the canonical form and endOffset with that of slot matchPos.
     */
    private void emitMerged(final int matchPos, final int matchOrd)
    {
        // Capture endOffset before restoreTo overwrites all attributes.
        final int endOffset = queue.get(matchPos)
                                   .getAttribute(OffsetAttribute.class)
                                   .endOffset();

        // Restore all attributes from first component (posIncr, startOffset, flags, ...).
        queue.restoreTo(this, 0);

        final int len = lexicon.formLength(matchOrd);
        final char[] buf = termAtt.resizeBuffer(len);
        lexicon.copy(matchOrd, buf, 0);
        termAtt.setLength(len);

        // Fix endOffset and type.
        offsetAtt.setOffset(offsetAtt.startOffset(), endOffset);
        typeAtt.setType(TYPE_MWE);

        // Discard matched slots; tail (if any) stays in queue for next call.
        for (int i = 0; i <= matchPos; i++) queue.removeFirst();
    }
}
