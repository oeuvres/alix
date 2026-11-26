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
import java.util.regex.Pattern;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.Char;

/**
 * Final, last-stage token hygiene before indexing.
 *
 * <p>
 * Typical placement: after a tokenizer and linguistic filters (POS tagger, lemmatizer,
 * normalizers),
 * on a dedicated "canonical terms" field used for tasks such as word-clouds, co-occurrence, or
 * SpanQuery/PhraseQuery-based context computations.
 * </p>
 *
 * <h2>Design goals</h2>
 * <ul>
 * <li><b>Exact word-count length</b>: the stream <em>never emits</em> empty terms and <em>does
 * not</em> emit punctuation
 * tokens. Therefore, {@code FieldInvertState.getLength()} reflects the number of emitted "word"
 * tokens, i.e.
 * your exact doc length in words for this canonical field.</li>
 * <li><b>Boundary preservation via gaps</b>: punctuation (and any other tokens rejected by
 * {@link #accept()})
 * is removed but its positional increment is accumulated and added to the next emitted token,
 * creating a
 * {@code posInc > 1} gap. This prevents phrase/span matches from crossing punctuation boundaries
 * unless slop allows it.</li>
 * <li><b>Noise removal without holes</b>: {@link #skip()} drops tokens and <em>collapses
 * positions</em>
 * (their {@code posInc} is not propagated). This is intended for markup artifacts and other
 * non-text noise
 * that should not affect distances.</li>
 * <li><b>Training output</b>: when no rejected-word set is supplied, cardinal
 * numbers ({@code DIGIT}/{@code NUM}) become {@code "#"}, ordinals retain their
 * value, and ordinary words use their upstream lemmas when available.
 * The training form policy is isolated in {@link #useLemma(int)}.</li>
 * </ul>
 *
 * <p>
 * <b>Important:</b> This is a {@link TokenFilter}; it does not rewrite the input character stream.
 * If you need to strip
 * XML/HTML markup, do it with a CharFilter upstream.
 * </p>
 *
 * <h2>Emitted tokens and length semantics</h2>
 * <p>
 * This filter enforces the invariant: <em>every emitted token is a word token</em>. As a
 * consequence:
 * </p>
 * <ul>
 * <li>{@code FieldInvertState.getLength()} counts words (tokens emitted downstream of this
 * filter).</li>
 * <li>Punctuation boundaries are represented as gaps in positions, not as emitted tokens.</li>
 * <li>Sentence, paragraph, and section boundaries are carried by {@link BoundaryAttribute}
 * on the next emitted token.</li>
 * <li>This filter is compatible with "no synonyms / no overlap" canonical fields;
 * {@code getNumOverlap()} is expected to be 0.</li>
 * </ul>
 */
public class CleanupFilter extends TokenFilter
{
    /** Structural boundary preceding the current emitted token. */
    private final BoundaryAttribute boundaryAtt = addAttribute(BoundaryAttribute.class);

    /** The term provided by the upstream TokenStream. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /** Used to propagate/adjust holes when tokens are removed with preserved gaps. */
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);

    /** Linguistic category (short code), see {@link Upos}. */
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);

    /** Optional lemma override populated by the upstream lemmatizer. */
    private final LemmaAttribute lemmaAtt = addAttribute(LemmaAttribute.class);

    /** Function word set. */
    public final CharArraySet rejected;

    /** Noise tokens set. */
    public final CharArraySet skipped;


    /**
     * Pending positional gaps to be applied to the next emitted token.
     *
     * <p>
     * We accumulate the {@code posInc} of tokens rejected by {@link #accept()} (including
     * punctuation),
     * then add it to the next accepted token's {@code posInc}. This ensures:
     * <ul>
     * <li>punctuation does not emit tokens (so it does not contribute to
     * {@code FieldInvertState.length});</li>
     * <li>phrase/span matching does not cross removed boundaries (gap &gt; 0).</li>
     * </ul>
     * </p>
     */
    private int pendingHoles = 0;

    /** Strongest structural boundary waiting for the next emitted token. */
    private int pendingBoundary = BoundaryAttribute.NONE;

    /**
     * Heuristic hook: drop very short tokens (typically non-informative for "terms" fields). Not
     * enforced by default.
     */
    protected static final int MIN_TERM_LEN = 3;

    /** Marker used to normalize cardinal numbers in training output. */
    protected static final String NUMBER_MARKER = "#";

    /** Arabic ordinal forms, including those produced by upstream normalization. */
    private static final Pattern ORDINAL = Pattern.compile(
        "\\p{Nd}+(?:e|es|er|ers|re|res|ème|èmes|eme|emes|ième|ièmes|ᵉ|ᵉʳ|ʳᵉ)"
    );


    /**
     * Creates a final token cleanup and normalization filter.
     *
     * @param input Source tokens
     * @param skipped Tokens to skip (suppress position)
     * @param rejected Tokens to remove from stream (position kept), or null
     *                 to retain function words and normalize numbers
     */
    public CleanupFilter(TokenStream input, final CharArraySet skipped, final CharArraySet rejected)
    {
        super(input);
        this.rejected = rejected;
        this.skipped = skipped;
    }

    /**
     * Called at end-of-stream.
     *
     * <p>
     * If the stream ends after a sequence of rejected tokens (e.g., trailing punctuation), their
     * accumulated
     * {@link #pendingHoles} would otherwise be lost. We therefore add them to the final position
     * increment
     * reported by {@link TokenStream#end()}.
     * </p>
     *
     * <p>
     * Note: this does not affect {@code FieldInvertState.getLength()} (which counts emitted
     * tokens), but it
     * preserves correct final position semantics for consumers that care about positions.
     * </p>
     */
    @Override
    public void end() throws IOException
    {
        super.end();
        if (pendingHoles != 0) {
            posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + pendingHoles);
            pendingHoles = 0;
        }
        boundaryAtt.setBoundary(pendingBoundary);
        pendingBoundary = BoundaryAttribute.NONE;
    }

    /**
     * Emits the next accepted token, applying {@link #skip()} and {@link #accept()}.
     *
     * <p>
     * Algorithm:
     * </p>
     * <ol>
     * <li>Tokens for which {@link #skip()} returns {@code true} are dropped and do <em>not</em>
     * create holes
     * (positions collapse).</li>
     * <li>Tokens for which {@link #accept()} returns {@code false} are dropped and their
     * {@code posInc} contributes
     * to {@link #pendingHoles}, added to the next emitted token.</li>
     * <li>Tokens for which {@link #accept()} returns {@code true} are normalized by
     * {@link #rewriteTerm()} and emitted after applying any accumulated
     * {@code pendingHoles}.</li>
     * </ol>
     *
     * <p>
     * <b>Invariant:</b> This filter never emits empty terms. If an upstream component produces an
     * empty
     * {@link CharTermAttribute}, it is treated as a removed token whose {@code posInc} contributes
     * to a hole.
     * </p>
     */
    @Override
    public final boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {
            boundaryAtt.setBoundary(BoundaryAttribute.NONE);

            final int pos = posAtt.getPos();
            final int boundary = boundary(pos);
            if (boundary > pendingBoundary) {
                pendingBoundary = boundary;
            }

            // Defensive: never emit empty terms; preserve their positional contribution.
            if (termAtt.length() == 0) {
                pendingHoles += posIncrAtt.getPositionIncrement();
                continue;
            }

            if (skip()) {
                // Intentionally collapse positions for this token (no hole propagation).
                continue;
            }

            if (accept()) {
                rewriteTerm();
                if (pendingHoles != 0) {
                    posIncrAtt.setPositionIncrement(
                        posIncrAtt.getPositionIncrement() + pendingHoles);
                    pendingHoles = 0;
                }
                boundaryAtt.setBoundary(pendingBoundary);
                pendingBoundary = BoundaryAttribute.NONE;
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
    public void reset() throws IOException
    {
        super.reset();
        pendingHoles = 0;
        pendingBoundary = BoundaryAttribute.NONE;
        boundaryAtt.setBoundary(BoundaryAttribute.NONE);
    }

    /**
     * Tests whether the current token should be emitted, without rewriting it.
     *
     * <p>
     * Contract:
     * </p>
     * <ul>
     * <li>Return {@code true} to emit the token after final normalization.</li>
     * <li>Return {@code false} to drop the token <em>but preserve a positional gap</em>
     * (its {@code posInc} will be added to the next emitted token via {@link #pendingHoles}).</li>
     * </ul>
     *
     * <p>
     * For this canonical field, punctuation is dropped with a gap (to block phrase/span crossing)
     * rather than emitted as a token. This keeps {@code FieldInvertState.getLength()} equal to the
     * exact word count.
     * </p>
     *
     * @return {@code true} to emit the token, {@code false} to drop it while keeping a hole
     */
    protected boolean accept()
    {
        final int len = termAtt.length();
        if (len < 1) return false;
        final int pos = posAtt.getPos();

        // Punctuation: drop, but preserve a positional gap (handled by pendingHoles).
        if (Upos.isPunct(pos)) {
            return false;
        }

        // known stop words, reject
        if (rejected != null && rejected.contains(termAtt.buffer(), 0, termAtt.length())) {
            return false;
        }

        // Preserve the existing admission rules for training numbers and elided articles.
        if (rejected == null && (isOrdinal(pos) || pos == DIGIT.code
                || pos == NUM.code || isElidedArticle())) {
            return true;
        }

        char first = termAtt.charAt(0);
        // Example: tokens starting with <, >, ≤, etc. (implementation-specific in Char.isMath()).
        if (Char.isMath(first) || Char.isDigit(first) || first == '-' || first == '\'') {
            return false;
        }
        char last = termAtt.charAt(len -1);
        if (Char.isMath(last) || Char.isDigit(last) || last == '-' || last == '\'' || last == '.' ) {
            return false;
        }

        // Preserve the previous admission rule: a resolved lemma bypasses the
        // Latin-letter check except for training function words. This does not
        // depend on the experimental output-form policy.
        if (!lemmaAtt.isEmpty() && (rejected != null || !isFunctionWord(pos))) {
            return true;
        }

        final char[] term = termAtt.buffer();
        for (int i = 0; i < len; i++) {
            final char c = term[i];
            if (Char.isLetter(c)) {
                // remove greek and OCR garbage
                if (!Char.isLatin(c)) return false;
            }
        }

        // Default: accept the token.
        return true;
    }

    /**
     * Maps punctuation POS codes to structural boundary strength.
     *
     * @param pos POS code
     * @return boundary strength, or {@link BoundaryAttribute#NONE}
     */
    private static int boundary(final int pos)
    {
        if (pos == PUNCTsection.code) return BoundaryAttribute.SECTION;
        if (pos == PUNCTpara.code) return BoundaryAttribute.PARAGRAPH;
        if (pos == PUNCTsent.code) return BoundaryAttribute.SENTENCE;
        return BoundaryAttribute.NONE;
    }

    /**
     * Tests the elided French article or object pronoun emitted by the clitic splitter.
     *
     * @return true for the normalized surface form {@code l'}
     */
    private boolean isElidedArticle()
    {
        return termAtt.length() == 2 && termAtt.charAt(0) == 'l'
            && termAtt.charAt(1) == '\'';
    }

    /**
     * Tests function-word categories for the existing token-admission rules only.
     * Determiner and pronoun code families include the extended lexical categories
     * if an upstream component supplies them; the OpenNLP dictionary normally
     * collapses those categories to DET and PRON.
     *
     * @param pos POS code
     * @return true for a function-word category
     */
    private static boolean isFunctionWord(final int pos)
    {
        return (pos >= DET.code && pos <= ADP_DET.code)
            || (pos >= PRON.code && pos <= PRONrel.code)
            || pos == AUX.code || pos == ADP.code || pos == ADP_PRON.code
            || pos == CCONJ.code || pos == SCONJ.code || pos == PART.code;
    }

    /**
     * Detects tokenizer ordinals and Arabic ordinal forms created by normalization.
     *
     * @param pos current POS code
     * @return true if the token should retain its ordinal value
     */
    private boolean isOrdinal(final int pos)
    {
        return pos == NUMord.code || (termAtt.length() > 1
            && Char.isDigit(termAtt.charAt(0)) && ORDINAL.matcher(termAtt).matches());
    }

    /**
     * Rewrites an accepted token. Numeric training conventions take precedence
     * over the ordinary lemma policy. An elided article or pronoun is eligible
     * for lemmatization just like any other word; if no lemma is available its
     * surface form is retained.
     */
    private void rewriteTerm()
    {
        final int pos = posAtt.getPos();
        if (rejected == null) {
            if (isOrdinal(pos)) {
                posAtt.setPos(NUMord.code);
                return;
            }
            if (pos == DIGIT.code || pos == NUM.code) {
                termAtt.setEmpty().append(NUMBER_MARKER);
                return;
            }
        }
        if (!lemmaAtt.isEmpty() && (rejected != null || useLemma(pos))) {
            termAtt.copyBuffer(lemmaAtt.buffer(), 0, lemmaAtt.length());
        }
    }

    /**
     * Drops "noise" tokens <em>without preserving positional gaps</em> (positions collapse).
     *
     * <p>
     * Override this to define what should be removed as non-textual noise for your corpus.
     * </p>
     *
     * @return {@code true} to drop the current token and collapse positions; {@code false} to let
     *         it be processed
     *         by {@link #accept()}.
     */
    protected boolean skip()
    {
        final int pos = posAtt.getPos();
        final int len = termAtt.length();

        // Punctuation is rejected by accept(); do not collapse its position here.
        if (Upos.isPunct(pos)) {
            return false;
        }

        // Markup / structural artifacts injected by XML processing.
        if (pos == XML.code) {
            return true;
        }

        // defensive, should already be handled in incrementToken(), no count
        if (len == 0) {
            return true;
        }
        // structural event, \n\n, used by Markdown, should have been processed
        if (termAtt.charAt(0) == '↵') {
            return true;
        }

        // Protect training numbers and elided articles even if listed as noise.
        if (rejected == null && (pos == DIGIT.code || pos == NUM.code
                || isOrdinal(pos) || isElidedArticle())) {
            return false;
        }

        // Known noise tokens: collapse positions.
        if (skipped != null && skipped.contains(termAtt.buffer(), 0, termAtt.length())) {
            return true;
        }

        // short function word, OK, not noise
        if (termAtt.length() == 1) {
            if (rejected == null && isFunctionWord(pos)) return false;
            return pos != ADP.code && pos != AUX.code
                && pos != PRON.code && pos != VERB.code;
        }

        final char last = termAtt.charAt(len - 1);
        if (termAtt.length() == 2) {
            // a’ a', C. variables or initials not resolved to name
            if (last == '\'' || last == '’' || last == '.') {
                return true;
            }
        }

        // Single trailing digit preceded by a non-digit: "abc4" (often a variable/label).
        if (len >= 2 && Char.isDigit(last) && !Char.isDigit(termAtt.charAt(len - 2)))
            return true;

        // keep stop words (do not drop them here)
        return false;
    }
    /**
     * Selects the ordinary word representation for Word2Vec training only.
     * This is the single experimental policy: return true to use the upstream
     * lemma (when available), or false to retain the normalized surface form.
     * The canonical indexing field always uses the lemma. Numeric rewriting,
     * token admission, MWE recognition and positional gaps are independent of
     * this choice.
     *
     * <p>The first experiment uses all available lemmas, including those of
     * function words. To test determiner surface forms alone, for example,
     * change the return expression to {@code pos != DET.code}.</p>
     *
     * @param pos upstream POS code, normally a coarse OpenNLP category
     * @return true to emit the available lemma, false to keep the surface form
     */
    protected boolean useLemma(final int pos)
    {
        return true;
    }

}
