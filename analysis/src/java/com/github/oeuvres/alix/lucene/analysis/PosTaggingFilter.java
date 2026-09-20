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
import static com.github.oeuvres.alix.common.Upos.XML;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.ArrayUtil;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;

import opennlp.tools.util.Sequence;

/**
 * Assigns POS tags to a Lucene token stream by buffering tokens and submitting
 * each sentence or bounded chunk to a shared {@link PosTagger}.
 * <p>
 * Each non-XML Lucene token is submitted to the tagger as exactly one token.
 * Multiword expressions therefore remain single tokens, including any spaces
 * they contain. Structural punctuation supplied by upstream filters is
 * preserved. Sentence boundaries are submitted to the tagger as {@code "."},
 * while XML tokens are omitted.
 * </p>
 * <p>
 * The first lexical token after a sentence boundary is lowercased before it is
 * submitted to the tagger. Leading punctuation does not consume this
 * sentence-initial state, and the state is preserved when a sentence is split
 * because it exceeds {@link #SENTMAX}.
 * </p>
 */
public class PosTaggingFilter extends TokenFilter
{
    /** Maximum number of Lucene tokens buffered in one tagging chunk. */
    public static final int SENTMAX = 300;

    /** Current token term; registering it ensures that buffered states contain it. */
    @SuppressWarnings("unused")
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /** Current token POS; contains upstream structural classes before tagging. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Current token tagging probability; registering it ensures that buffered states contain it. */
    @SuppressWarnings("unused")
    private final ProbAttribute probAtt = addAttribute(ProbAttribute.class);

    /** Buffered token states for the current sentence or chunk. */
    private TokenStateQueue queue;

    /** Queue index to corresponding tagger-token index, or {@code -1} when omitted. */
    private int[] queueToTagIndex = new int[0];

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
        return pos == PUNCTsection.code
            || pos == PUNCTpara.code
            || pos == PUNCTsent.code
            || pos == PUNCTstruct.code;
    }

    /**
     * Builds the tagger input for the buffered queue, invokes the tagger, and
     * writes each tag and probability back to its corresponding Lucene token.
     */
    private void tagBufferedQueue()
    {
        final int n = queue.size();
        if (n == 0) {
            return;
        }

        queueToTagIndex = ArrayUtil.grow(queueToTagIndex, n);
        Arrays.fill(queueToTagIndex, 0, n, -1);

        final List<String> sentence = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            final PosAttribute pos = queue.get(i).getAttribute(PosAttribute.class);
            if (pos == null) {
                continue;
            }

            final int origPos = pos.getPos();

            if (origPos == XML.code) {
                final ProbAttribute prob = queue.get(i).getAttribute(ProbAttribute.class);
                if (prob != null) {
                    prob.setProb(1.0);
                }
                continue;
            }

            if (isSentenceBoundary(origPos)) {
                sentence.add(".");
                sentenceStart = true;
                continue;
            }

            final CharTermAttribute term = queue.get(i).getAttribute(CharTermAttribute.class);
            if (term == null || term.length() == 0) {
                continue;
            }

            String taggerTerm = term.toString();
            if (!Upos.isPunct(origPos)) {
                if (sentenceStart) {
                    taggerTerm = taggerTerm.toLowerCase(Locale.ROOT);
                }
                sentenceStart = false;
            }

            queueToTagIndex[i] = sentence.size();
            sentence.add(taggerTerm);
        }

        if (sentence.isEmpty()) {
            return;
        }

        final Sequence sequence = tagger.tag(sentence.toArray(new String[0]));
        if (sequence == null) {
            return;
        }

        final List<String> tags = sequence.getOutcomes();
        final double[] probabilities = sequence.getProbs();
        final int taggedLength = Math.min(sentence.size(), Math.min(tags.size(), probabilities.length));

        for (int i = 0; i < n; i++) {
            final int tagIndex = queueToTagIndex[i];
            if (tagIndex < 0 || tagIndex >= taggedLength) {
                continue;
            }

            final PosAttribute pos = queue.get(i).getAttribute(PosAttribute.class);
            if (pos == null || Upos.isPunct(pos.getPos())) {
                continue;
            }

            final ProbAttribute prob = queue.get(i).getAttribute(ProbAttribute.class);
            if (prob != null) {
                prob.setProb(probabilities[tagIndex]);
            }

            final Upos taggedPos = Upos.get(tags.get(tagIndex));
            if (taggedPos != null) {
                pos.setPos(taggedPos.code());
            }
        }
    }
}
