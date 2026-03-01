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
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.Char;

/**
 * Final, last-stage token hygiene before indexing.
 *
 * <p>Typical placement: after a tokenizer and linguistic filters (POS tagger, lemmatizer, synonym/normalizers),
 * on a dedicated "terms" field used for tasks such as word-clouds, co-occurrence, or SpanQuery-based context
 * computations.</p>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li><b>Noise removal without holes</b>: {@link #skip()} drops tokens and <em>collapses positions</em>
 *       (their {@code posInc} is not propagated). This is used for markup artifacts and other "non-text" noise.</li>
 *   <li><b>Optional removal with holes</b>: {@link #accept()} may return {@code false} to drop a token while
 *       <em>preserving a positional gap</em> (its {@code posInc} is accumulated and added to the next emitted token).
 *       This is useful if you want to preserve word-distance despite removing some tokens (e.g., stopwords).</li>
 *   <li><b>Punctuation rail</b>: punctuation tokens are emitted as a zero-length term (empty {@link CharTermAttribute})
 *       to prevent phrase/span matches from crossing punctuation boundaries while keeping positional continuity.</li>
 *   <li><b>Number unification</b>: numeric tokens ({@code DIGIT}/{@code NUM}) are mapped to a single marker {@code "#"}.</li>
 * </ul>
 *
 * <p><b>Note:</b> This is a {@link TokenFilter}; it does not rewrite the input character stream. If you need to strip
 * XML/HTML markup, do it with a CharFilter upstream.</p>
 */
public class FinalCleanupFilter extends TokenFilter
{
    /** The term provided by the upstream TokenStream. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Used to propagate/adjust holes when tokens are removed with preserved gaps. */
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    /** Linguistic category (short code), see {@link Upos}. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Heuristic: drop very short tokens (typically non-informative for "terms" fields). */
    protected static final int MIN_TERM_LEN = 3;

    /** Marker used to normalize all numbers. */
    protected static final String NUMBER_MARKER = "#";

    /**
     * @param input upstream token stream (tokenizer or previous filters)
     */
    public FinalCleanupFilter(TokenStream input) {
        super(input);
    }

    /**
     * Emits the next accepted token, applying {@link #skip()} and {@link #accept()}.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Tokens for which {@link #skip()} returns {@code true} are dropped and do <em>not</em> create holes.</li>
     *   <li>Tokens for which {@link #accept()} returns {@code false} are dropped but their {@code posInc} contributes
     *       to a hole counter, added to the next emitted token.</li>
     * </ol>
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        int holeCount = 0;

        while (input.incrementToken()) {
            if (skip()) {
                // Intentionally collapse positions for this token.
                continue;
            }

            if (accept()) {
                if (holeCount != 0) {
                    posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + holeCount);
                }
                return true;
            }

            // Token rejected but its position increment must be preserved as a gap.
            holeCount += posIncrAtt.getPositionIncrement();
        }
        return false;
    }

    /**
     * Drops "noise" tokens <em>without preserving positional gaps</em> (positions collapse).
     *
     * <p>Override this to define what should be removed as non-textual noise for your corpus.</p>
     *
     * @return {@code true} to drop the current token and collapse positions; {@code false} to let it be processed
     *         by {@link #accept()}.
     */
    protected boolean skip()
    {
        final int pos = posAtt.getPos();
        final int len = termAtt.length();

        if (len == 0) return true;

        // Markup / structural artifacts injected by XML processing.
        if (pos == XML.code) return true;

        // Example: tokens starting with <, >, ≤, etc. (implementation-specific in Char.isMath()).
        if (Char.isMath(termAtt.charAt(0))) return true;

        final char last = termAtt.charAt(len - 1);

        // Truncated variables / elisions like "jusqu'" or any token ending with apostrophe.
        if (last == '\'') return true;

        // Single trailing digit preceded by a non-digit: "abc4" (often a variable/label).
        if (len >= 2 && Char.isDigit(last) && !Char.isDigit(termAtt.charAt(len - 2))) return true;

        // keep stop words
        return false;
    }

    /**
     * Accepts (and optionally rewrites) the current token.
     *
     * <p>Contract:</p>
     * <ul>
     *   <li>Return {@code true} to emit the token (possibly after rewriting {@link #termAtt}).</li>
     *   <li>Return {@code false} to drop the token <em>but preserve a positional gap</em>
     *       (its {@code posInc} will be added to the next emitted token).</li>
     * </ul>
     *
     * @return {@code true} to emit the token, {@code false} to drop it while keeping a hole
     */
    protected boolean accept()
    {
        final int pos = posAtt.getPos();

        // Punctuation: keep a positional rail token that blocks phrase/span crossing.
        if (Upos.isPunct(pos)) {
            termAtt.setEmpty(); // zero-length term on purpose
            return true;
        }

        // Numbers: normalize to a single marker.
        if (pos == DIGIT.code || pos == NUM.code) {
            termAtt.setEmpty().append(NUMBER_MARKER);
            return true;
        }

        // Default: keep token as-is (or rewritten by upstream filters).
        return true;
    }
}