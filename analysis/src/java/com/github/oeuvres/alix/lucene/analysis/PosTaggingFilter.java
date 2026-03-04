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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.ArrayUtil;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

/**
 * POS tagging filter with sentence buffering and many-to-many mapping
 * between Lucene tokens and the String[] sent to the POS tagger.
 *
 * Language-agnostic:
 * - no lexical resources
 * - no language-specific rewrite rules
 * - no post-correction heuristics
 *
 * Rewriting is delegated to a pluggable TaggerRewriter.
 */
public class PosTaggingFilter extends TokenFilter
{

    /** Max buffered tokens per sentence/chunk. */
    public static final int SENTMAX = 300;

    /**
     * Rewriter used to build the tagger String[] from a Lucene term.
     * Contract:
     * - return null or empty => fallback to identity (term)
     * - returned strings are submitted to the tagger in place of the single Lucene token
     * - this filter keeps mapping back to the original Lucene token index
     */
    @FunctionalInterface
    public interface TaggerRewriter {
        /**
         * Append 0..N tagger tokens for one Lucene token.
         * Contract: append only non-null, non-empty strings.
         */
        void rewrite(String term, List<String> out);
    }

    /** Identity rewriter (1 Lucene token -> 1 tagger token), no per-token array allocation. */
    public static final TaggerRewriter IDENTITY_REWRITER = new TaggerRewriter() {
        @Override
        public void rewrite(final String term, final List<String> out) {
            out.add(term);
        }
    };
    
    /** Identity rewriter (1 Lucene token -> 1 tagger token), no per-token array allocation. */
    public static final TaggerRewriter HYPHEN_REWRITER = new TaggerRewriter() {
        @Override
        public void rewrite(final String term, final List<String> out) {
            out.add(term.replace("-", ""));
        }
    };


    /** The term provided by the Tokenizer (current token cursor). */
    @SuppressWarnings("unused")
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /** POS attribute (read structural class from upstream; write POS from tagger). */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Probability attribute set from tagger confidence. */
    private final ProbAttribute probAtt = addAttribute(ProbAttribute.class);

    /** Buffered token states (one sentence/chunk). */
    private TokenStateQueue queue;

    /** non-thread-safe tagger, one per filter instance */
    private final POSTaggerME tagger;

    /** Optional term rewriter for tagger input (language-specific logic belongs outside this class). */
    private final TaggerRewriter rewriter;

    // ---- Mapping buffers ----
    /** tagger token index -> queue index (many tagger tokens may map to same queue token). */
    private int[] tagToQueue = new int[0];

    /** queue index -> first tagger token index, or -1 if not submitted to tagger (e.g. XML). */
    private int[] queueToTagStart = new int[0];

    /** queue index -> number of tagger tokens generated for this Lucene token (0,1,N). */
    private int[] queueToTagCount = new int[0];

    /**
     * Default constructor: identity rewrite (1->1).
     */
    public PosTaggingFilter(TokenStream input, POSModel posModel)
    {
        this(input, posModel, IDENTITY_REWRITER);
    }

    /**
     * Constructor with pluggable many-to-many rewriter.
     */
    public PosTaggingFilter(TokenStream input, POSModel posModel, TaggerRewriter rewriter)
    {
        super(input);
        this.tagger = new POSTaggerME(posModel);
        this.rewriter = (rewriter == null) ? IDENTITY_REWRITER : rewriter;
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        ensureQueue();

        // 0) Drain queued tokens first
        if (!queue.isEmpty()) {
            clearAttributes();
            queue.removeFirst(this);
            return true;
        }

        // 1) Fill queue until boundary or SENTMAX or EOF
        fillQueue();

        final int n = queue.size();
        if (n == 0) {
            return false;
        }

        // 2) Build tagger sentence + mappings, then tag, then write back
        tagBufferedQueue();

        // 3) Emit first token of the now-tagged queue
        clearAttributes();
        queue.removeFirst(this);
        return true;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        ensureQueue();
        queue.clear();
    }

    private void ensureQueue()
    {
        if (queue == null) {
            queue = new TokenStateQueue(SENTMAX, this);
        }
    }

    /**
     * Fill queue until sentence boundary, SENTMAX, or EOF.
     */
    private void fillQueue() throws IOException
    {
        while (queue.size() < SENTMAX) {
            clearAttributes();
            if (!input.incrementToken()) {
                break;
            }

            queue.addLast(this);

            final int pos = posAtt.getPos(); // structural classification from upstream
            if (isSentenceBoundary(pos)) {
                break;
            }
        }
    }

    /**
     * Build String[] for tagger with many-to-many mapping and write back tags.
     *
     * Policy for expanded tokens (1->N):
     * - POS is NOT overwritten here (language-specific projection should be elsewhere)
     * - probability is set to max(probabilities of generated tagger tokens)
     */
    private void tagBufferedQueue()
    {
        final int n = queue.size();
        if (n == 0) return;

        // Prepare per-queue mapping arrays
        queueToTagStart = ArrayUtil.grow(queueToTagStart, n);
        queueToTagCount = ArrayUtil.grow(queueToTagCount, n);
        Arrays.fill(queueToTagStart, 0, n, -1);
        Arrays.fill(queueToTagCount, 0, n, 0);

        // Build tagger sentence dynamically
        final List<String> sentenceList = new ArrayList<>(n + 8);
        int m = 0; // tagger token count

        // Build many-to-many mapping
        for (int i = 0; i < n; i++) {
            final PosAttribute p = queue.get(i).getAttribute(PosAttribute.class);
            if (p == null) continue;

            final int pos = p.getPos();

            // Skip structural XML tags entirely (1 -> 0)
            if (pos == XML.code) {
                probAtt.setProb(1);
                continue;
            }

            // Sentence boundaries are submitted as punctuation token (1 -> 1)
            if (isSentenceBoundary(pos)) {
                queueToTagStart[i] = m;
                queueToTagCount[i] = 1;

                tagToQueue = ArrayUtil.grow(tagToQueue, m + 1);
                tagToQueue[m] = i;
                sentenceList.add(".");
                m++;
                continue;
            }

            final CharTermAttribute t = queue.get(i).getAttribute(CharTermAttribute.class);
            if (t == null || t.length() == 0) {
                continue; // 1 -> 0
            }

            final String term = t.toString();

            final int before = sentenceList.size();
            rewriter.rewrite(term, sentenceList);
            int cnt = sentenceList.size() - before;

            // Defensive fallback to identity (rewriter appended nothing)
            if (cnt <= 0) {
                sentenceList.add(term);
                cnt = 1;
            }

            queueToTagStart[i] = m;
            queueToTagCount[i] = cnt;

            tagToQueue = ArrayUtil.grow(tagToQueue, m + cnt);
            for (int j = 0; j < cnt; j++) {
                tagToQueue[m + j] = i;
            }
            m += cnt;
        }

        // Nothing taggable: queue drains unchanged.
        if (m == 0) return;

        final String[] sentence = sentenceList.toArray(new String[m]);
        
        // debug, check how sentence is splitted
        // final CharTermAttribute termLast = queue.get(n-1).getAttribute(CharTermAttribute.class);
        // termLast.setEmpty().append("$$$");
        // termLast.setEmpty().append(Arrays.toString(sentence));

        // Tag
        final String[] tags = tagger.tag(sentence);
        final double[] probs = tagger.probs();

        final int tlen = Math.min(m, Math.min(tags.length, probs.length));

        // Write back, queue token by queue token (using queue -> tagger slice mapping)
        for (int i = 0; i < n; i++) {
            final int start = queueToTagStart[i];
            final int cnt = queueToTagCount[i];
            if (start < 0 || cnt <= 0) continue;

            final PosAttribute posAttr = queue.get(i).getAttribute(PosAttribute.class);
            if (posAttr == null) continue;

            final int origPos = posAttr.getPos();

            // Preserve upstream punctuation classification unchanged
            if (Upos.isPunct(origPos)) {
                continue;
            }

            // Probability: max over generated slice (useful even for expanded tokens)
            final ProbAttribute prob = queue.get(i).getAttribute(ProbAttribute.class);
            if (prob != null) {
                double pmax = Double.NEGATIVE_INFINITY;
                final int end = Math.min(start + cnt, tlen);
                for (int j = start; j < end; j++) {
                    if (probs[j] > pmax) pmax = probs[j];
                }
                if (pmax != Double.NEGATIVE_INFINITY) {
                    prob.setProb(pmax);
                }
            }

            // POS write-back only for 1->1 mapping in this generic filter.
            // Expanded tokens are rewritten for context preservation; projection is language-specific.
            if (cnt != 1) {
                continue;
            }

            if (start >= tlen) continue;

            final Upos upos = Upos.get(tags[start].replace('+', '_'));
            if (upos != null) {
                posAttr.setPos(upos.code());
            }
        }
    }
    
    private static boolean isSentenceBoundary(final int pos)
    {
        return pos == PUNCTsection.code || pos == PUNCTpara.code || pos == PUNCTsent.code;
    }
}