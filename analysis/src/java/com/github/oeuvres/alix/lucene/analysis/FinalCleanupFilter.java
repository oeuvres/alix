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
 * <p>Typical placement: after a tokenizer and linguistic filters (POS tagger, lemmatizer, normalizers),
 * on a dedicated "canonical terms" field used for tasks such as word-clouds, co-occurrence, or
 * SpanQuery/PhraseQuery-based context computations.</p>
 *
 * <h2>Design goals</h2>
 * <ul>
 *   <li><b>Exact word-count length</b>: the stream <em>never emits</em> empty terms and <em>does not</em> emit punctuation
 *       tokens. Therefore, {@code FieldInvertState.getLength()} reflects the number of emitted "word" tokens, i.e.
 *       your exact doc length in words for this canonical field.</li>
 *   <li><b>Boundary preservation via gaps</b>: punctuation (and any other tokens rejected by {@link #accept()})
 *       is removed but its positional increment is accumulated and added to the next emitted token, creating a
 *       {@code posInc > 1} gap. This prevents phrase/span matches from crossing punctuation boundaries unless slop allows it.</li>
 *   <li><b>Noise removal without holes</b>: {@link #skip()} drops tokens and <em>collapses positions</em>
 *       (their {@code posInc} is not propagated). This is intended for markup artifacts and other non-text noise
 *       that should not affect distances.</li>
 *   <li><b>Number unification</b>: numeric tokens ({@code DIGIT}/{@code NUM}) are mapped to a single marker {@code "#"}.</li>
 * </ul>
 *
 * <p><b>Important:</b> This is a {@link TokenFilter}; it does not rewrite the input character stream. If you need to strip
 * XML/HTML markup, do it with a CharFilter upstream.</p>
 *
 * <h2>Emitted tokens and length semantics</h2>
 * <p>This filter enforces the invariant: <em>every emitted token is a word token</em>. As a consequence:</p>
 * <ul>
 *   <li>{@code FieldInvertState.getLength()} counts words (tokens emitted downstream of this filter).</li>
 *   <li>Punctuation boundaries are represented as gaps in positions, not as emitted tokens.</li>
 *   <li>This filter is compatible with "no synonyms / no overlap" canonical fields; {@code getNumOverlap()} is expected to be 0.</li>
 * </ul>
 */
public class FinalCleanupFilter extends TokenFilter
{
    /** The term provided by the upstream TokenStream. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /** Used to propagate/adjust holes when tokens are removed with preserved gaps. */
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);

    /** Linguistic category (short code), see {@link Upos}. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /**
     * Pending positional gaps to be applied to the next emitted token.
     *
     * <p>We accumulate the {@code posInc} of tokens rejected by {@link #accept()} (including punctuation),
     * then add it to the next accepted token's {@code posInc}. This ensures:
     * <ul>
     *   <li>punctuation does not emit tokens (so it does not contribute to {@code FieldInvertState.length});</li>
     *   <li>phrase/span matching does not cross removed boundaries (gap &gt; 0).</li>
     * </ul>
     * </p>
     */
    private int pendingHoles = 0;

    /** Heuristic hook: drop very short tokens (typically non-informative for "terms" fields). Not enforced by default. */
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
     *   <li>Tokens for which {@link #skip()} returns {@code true} are dropped and do <em>not</em> create holes
     *       (positions collapse).</li>
     *   <li>Tokens for which {@link #accept()} returns {@code false} are dropped and their {@code posInc} contributes
     *       to {@link #pendingHoles}, added to the next emitted token.</li>
     *   <li>Tokens for which {@link #accept()} returns {@code true} are emitted after applying any accumulated {@code pendingHoles}.</li>
     * </ol>
     *
     * <p><b>Invariant:</b> This filter never emits empty terms. If an upstream component produces an empty
     * {@link CharTermAttribute}, it is treated as a removed token whose {@code posInc} contributes to a hole.</p>
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {

            // Defensive: never emit empty terms; treat as removed token with a positional contribution.
            if (termAtt.length() == 0) {
                pendingHoles += posIncrAtt.getPositionIncrement();
                continue;
            }

            if (skip()) {
                // Intentionally collapse positions for this token (no hole propagation).
                continue;
            }

            if (accept()) {
                if (pendingHoles != 0) {
                    posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + pendingHoles);
                    pendingHoles = 0;
                }
                return true;
            }

            // Token rejected but its position increment must be preserved as a gap.
            pendingHoles += posIncrAtt.getPositionIncrement();
        }
        return false;
    }

    /**
     * Resets this stream and clears any pending holes from previous consumption.
     */
    @Override
    public void reset() throws IOException {
        super.reset();
        pendingHoles = 0;
    }

    /**
     * Called at end-of-stream.
     *
     * <p>If the stream ends after a sequence of rejected tokens (e.g., trailing punctuation), their accumulated
     * {@link #pendingHoles} would otherwise be lost. We therefore add them to the final position increment
     * reported by {@link TokenStream#end()}.</p>
     *
     * <p>Note: this does not affect {@code FieldInvertState.getLength()} (which counts emitted tokens), but it
     * preserves correct final position semantics for consumers that care about positions.</p>
     */
    @Override
    public void end() throws IOException {
        super.end();
        if (pendingHoles != 0) {
            posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + pendingHoles);
            pendingHoles = 0;
        }
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

        // Markup / structural artifacts injected by XML processing.
        if (pos == XML.code) return true;

        // defensive, should already be handled in incrementToken(), no count
        if (len == 0) return true;
        

        // short function word, OK, not noise
        if (termAtt.length() == 1) {
            switch (Upos.get(pos) ) {
                case ADP:
                case AUX:
                case PRON:
                case VERB:
                    return false;
                default:
                    return true;
            }
        }

        if (termAtt.length() == 2) {
            final char c2 = termAtt.charAt(1);
            // a’ a', C. variables or initials not resolved to name
            if (c2 == '\'' || c2 == '’' || c2 == '.') {
                return true;
            }
        }

        // keep stop words (do not drop them here)
        return false;
    }

    /**
     * Accepts (and optionally rewrites) the current token.
     *
     * <p>Contract:</p>
     * <ul>
     *   <li>Return {@code true} to emit the token (possibly after rewriting {@link #termAtt}).</li>
     *   <li>Return {@code false} to drop the token <em>but preserve a positional gap</em>
     *       (its {@code posInc} will be added to the next emitted token via {@link #pendingHoles}).</li>
     * </ul>
     *
     * <p>For this canonical field, punctuation is dropped with a gap (to block phrase/span crossing)
     * rather than emitted as a token. This keeps {@code FieldInvertState.getLength()} equal to the exact word count.</p>
     *
     * @return {@code true} to emit the token, {@code false} to drop it while keeping a hole
     */
    protected boolean accept()
    {
        final int pos = posAtt.getPos();
        final int len = termAtt.length();
        
        
        // Punctuation: drop, but preserve a positional gap (handled by pendingHoles).
        if (Upos.isPunct(pos)) {
            return false;
        }

        // Example: tokens starting with <, >, ≤, etc. (implementation-specific in Char.isMath()).
        if (Char.isMath(termAtt.charAt(0))) return false;

        final char last = termAtt.charAt(len - 1);

        // Single trailing digit preceded by a non-digit: "abc4" (often a variable/label).
        // if (len >= 2 && Char.isDigit(last) && !Char.isDigit(termAtt.charAt(len - 2))) return true;

        /*
        // Numbers: normalize to a single marker.
        if (pos == DIGIT.code || pos == NUM.code) {
            termAtt.setEmpty().append(NUMBER_MARKER);
            return true;
        }
        
        // Return all know tokens
        if (pos > X.code) {
            return true;
        }
        */
        

        // Default: keep token as-is (or rewritten by upstream filters).
        return true;
    }
}