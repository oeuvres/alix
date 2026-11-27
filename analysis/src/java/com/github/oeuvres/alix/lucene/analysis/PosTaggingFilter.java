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

import static com.github.oeuvres.alix.common.Upos.PUNCTpara;
import static com.github.oeuvres.alix.common.Upos.PUNCTsection;
import static com.github.oeuvres.alix.common.Upos.PUNCTsent;
import static com.github.oeuvres.alix.common.Upos.PUNCTstruct;
import static com.github.oeuvres.alix.common.Upos.PUNCT;
import static com.github.oeuvres.alix.common.Upos.PROPN;
import static com.github.oeuvres.alix.common.Upos.TOKEN;
import static com.github.oeuvres.alix.common.Upos.XML;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;

import opennlp.tools.util.Sequence;

/**
 * Completes unassigned POS annotations in sentence-sized chunks.
 *
 * <p>Only {@link Upos#TOKEN} is eligible for statistical assignment. An
 * existing grammatical POS constrains decoding through {@link Upos#base()},
 * without replacing the original tag. Other technical tags are preserved;
 * XML and empty terms are omitted. Structural boundaries are submitted as
 * periods. Each submitted token remains one model token, including MWEs.</p>
 *
 * <p>Sentence-initial lowercasing is confined to unassigned tokens and uses
 * the model's dictionary. Terms, lemmas, offsets and positions are unchanged.
 * A failed decode leaves the upstream annotations intact.</p>
 */
public class PosTaggingFilter extends TokenFilter
{
    /** Maximum number of Lucene tokens buffered in one tagging chunk. */
    public static final int SENTMAX = 300;

    /** Current token term; registering it ensures that buffered states contain it. */
    @SuppressWarnings("unused")
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /** Current token POS, including authoritative upstream annotations. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Current token tagging probability, registered before queue snapshots. */
    @SuppressWarnings("unused")
    private final ProbAttribute probAtt = addAttribute(ProbAttribute.class);

    /** Buffered token states for the current sentence or chunk. */
    private TokenStateQueue queue;

    /** Queue index to corresponding tagger-token index, or {@code -1} when omitted. */
    private final int[] queueToTagIndex = new int[SENTMAX];

    /** Whether the next lexical token is the first lexical token of a sentence. */
    private boolean sentenceStart = true;

    /** Shared POS decoder. */
    private final PosTagger tagger;

    /**
     * Creates a POS tagging filter.
     *
     * @param input upstream token stream
     * @param tagger shared POS decoder
     * @throws NullPointerException if {@code tagger} is {@code null}
     */
    public PosTaggingFilter(
        final TokenStream input,
        final PosTagger tagger
    ) {
        super(input);
        if (tagger == null) {
            throw new NullPointerException("tagger");
        }
        this.tagger = tagger;
    }

    /**
     * Emits the next buffered and POS-tagged token, filling and tagging a new
     * sentence or chunk when the queue is empty.
     *
     * @return {@code true} when a token is emitted; {@code false} at end of stream
     * @throws IOException if the upstream token stream cannot be read
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        ensureQueue();

        if (!queue.isEmpty()) {
            clearAttributes();
            queue.removeFirst(this);
            return true;
        }

        fillQueue();
        if (queue.isEmpty()) {
            return false;
        }

        tagBufferedQueue();

        clearAttributes();
        queue.removeFirst(this);
        return true;
    }

    /**
     * Resets this filter and its sentence-start state for a new input stream.
     *
     * @throws IOException if the upstream token stream cannot be reset
     */
    @Override
    public void reset() throws IOException
    {
        super.reset();
        ensureQueue();
        queue.clear();
        sentenceStart = true;
    }

    /** Creates the token-state queue on first use. */
    private void ensureQueue()
    {
        if (queue == null) {
            queue = new TokenStateQueue(SENTMAX, this);
        }
    }

    /**
     * Reads upstream tokens until a sentence boundary, {@link #SENTMAX}, or
     * end of input is reached. The boundary token itself is included.
     *
     * @throws IOException if the upstream token stream cannot be read
     */
    private void fillQueue() throws IOException
    {
        while (queue.size() < SENTMAX) {
            clearAttributes();
            if (!input.incrementToken()) {
                return;
            }

            queue.addLast(this);
            if (isSentenceBoundary(posAtt.getPos())) {
                return;
            }
        }
    }

    /**
     * Tests whether an upstream structural POS code ends a sentence-sized
     * tagging unit.
     *
     * @param pos upstream POS code
     * @return {@code true} for section, paragraph, sentence, or structural boundaries
     */
    private static boolean isSentenceBoundary(final int pos)
    {
        return pos == PUNCTsection.ordinal()
            || pos == PUNCTpara.ordinal()
            || pos == PUNCTsent.ordinal()
            || pos == PUNCTstruct.ordinal();
    }

    /**
     * Decodes the buffered tokens with authoritative upstream POS constraints,
     * then writes model predictions only to unassigned tokens.
     */
    private void tagBufferedQueue()
    {
        final int n = queue.size();
        final String[] sentence = new String[n];
        final String[] forcedTags = new String[n];
        Arrays.fill(queueToTagIndex, 0, n, -1);
        int count = 0;
        boolean unresolved = false;

        for (int i = 0; i < n; i++) {
            final AttributeSource slot = queue.get(i);
            final Upos tag = Upos.get(slot.getAttribute(PosAttribute.class).getPos());
            if (tag == null) {
                throw new IllegalStateException("Invalid upstream POS: "
                    + slot.getAttribute(PosAttribute.class).getPos());
            }
            final Upos base = tag.base();
            if (base != null || tag == XML) {
                slot.getAttribute(ProbAttribute.class).setProb(1.0);
            }
            if (tag == XML) continue;

            if (isSentenceBoundary(tag.ordinal())) {
                sentence[count] = ".";
                forcedTags[count++] = PUNCT.name();
                sentenceStart = true;
                continue;
            }

            final CharTermAttribute term = slot.getAttribute(CharTermAttribute.class);
            if (term.length() == 0) continue;
            String word = term.toString();
            if (base != PUNCT) {
                if (sentenceStart && tag == TOKEN && !tagger.hasTag(word, PROPN.name())) {
                    final String lower = word.toLowerCase(Locale.ROOT);
                    final String[] tags = tagger.tags(lower);
                    if (tags != null && tags.length != 0) word = lower;
                }
                sentenceStart = false;
            }
            if (tag == TOKEN) {
                queueToTagIndex[i] = count;
                unresolved = true;
            }
            sentence[count] = word;
            forcedTags[count] = base == null ? null : base.name();
            count++;
        }

        if (!unresolved) return;
        final String[] words = count == n ? sentence : Arrays.copyOf(sentence, count);
        final String[] constraints = count == n ? forcedTags : Arrays.copyOf(forcedTags, count);
        final Sequence sequence = tagger.tag(words, constraints);
        if (sequence == null) return;

        final List<String> tags = sequence.getOutcomes();
        final double[] probabilities = sequence.getProbs();
        if (tags.size() != count || probabilities.length != count) return;
        // Do not apply a partial or unconstrained result.
        for (int i = 0; i < count; i++) {
            if (constraints[i] != null && !constraints[i].equals(tags.get(i))) return;
        }
        for (int i = 0; i < n; i++) {
            final int index = queueToTagIndex[i];
            if (index < 0) continue;
            final Upos predicted = Upos.get(tags.get(index));
            if (predicted == null || predicted.base() == null) continue;
            final AttributeSource slot = queue.get(i);
            slot.getAttribute(PosAttribute.class).setPos(predicted.ordinal());
            slot.getAttribute(ProbAttribute.class).setProb(probabilities[index]);
        }
    }
}
