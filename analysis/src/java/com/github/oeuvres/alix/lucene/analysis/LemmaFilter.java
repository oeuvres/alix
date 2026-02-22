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

import static com.github.oeuvres.alix.common.Upos.XML;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemmaAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;


/**
 * Populates a parallel lemma channel for each token using a {@link LemmaLexicon}.
 *
 * <p>This filter does <b>not</b> replace the token text carried by
 * {@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute}.
 * Instead, it writes the resolved lemma (when available) into a custom
 * {@link LemmaAttribute}, allowing downstream consumers to choose between:
 *
 * <ul>
 *   <li>the original surface / inflected form (from {@code CharTermAttribute}), and</li>
 *   <li>the lemma form (from {@code LemmaAttribute}).</li>
 * </ul>
 *
 * <p>The lemma channel is <b>sparse</b>: {@code LemmaAttribute} is left empty when no
 * useful lemma is produced (see rules below). This preserves a clear semantic invariant:
 * an empty lemma slot means "no lemma override for this token".
 *
 * <h2>Lookup strategy</h2>
 *
 * <p>For each token, this filter:
 * <ol>
 *   <li>reads the current surface form from {@code CharTermAttribute},</li>
 *   <li>looks up the corresponding form id in the {@link LemmaLexicon},</li>
 *   <li>tries a POS-specific lemma lookup using {@link PosAttribute},</li>
 *   <li>optionally falls back to a POS-agnostic lemma lookup (filter policy),</li>
 *   <li>writes the lemma into {@code LemmaAttribute} only if it is distinct from the surface form.</li>
 * </ol>
 *
 * <h2>Tokens ignored by design</h2>
 *
 * <p>No lemma is written (the lemma channel remains empty) for:
 * <ul>
 *   <li>tokens marked as keywords ({@link org.apache.lucene.analysis.tokenattributes.KeywordAttribute}),</li>
 *   <li>punctuation tokens (as determined from {@link PosAttribute}),</li>
 *   <li>XML / markup sentinel tokens (according to the POS code policy used by this pipeline),</li>
 *   <li>tokens not found in the lexicon,</li>
 *   <li>tokens whose resolved lemma is identical to the surface form.</li>
 * </ul>
 *
 * <h2>Attribute contract</h2>
 *
 * <p>This filter requires the following attributes to be present in the stream:
 * <ul>
 *   <li>{@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute} (input token text),</li>
 *   <li>{@link PosAttribute} (POS code used for lemma disambiguation),</li>
 *   <li>{@link org.apache.lucene.analysis.tokenattributes.KeywordAttribute} (skip-lemmatization marker),</li>
 *   <li>{@link LemmaAttribute} (output lemma channel; added by this filter if absent).</li>
 * </ul>
 *
 * <p>The filter should clear or empty {@code LemmaAttribute} on every token before attempting
 * lookup, so that the lemma channel cannot accidentally retain a previous token value.
 *
 * <h2>Indexing note</h2>
 *
 * <p>{@code LemmaAttribute} is a custom analysis-time attribute. Standard Lucene indexing
 * components do not automatically persist custom attributes into the index. If the lemma channel
 * must be indexed, project it explicitly to a dedicated field (for example, by replaying tokens
 * into a second {@code TokenStream}).
 * 
 * <p>The lemma channel is sparse: LemmaAttribute is left empty for punctuation, markup/XML sentinel 
 * tokens, keywords, unknown forms.
 *
 * <h2>Performance characteristics</h2>
 *
 * <p>This filter is intended to be allocation-light:
 * lexicon forms are copied from interned storage into {@code LemmaAttribute}'s reusable
 * {@code char[]} buffer, avoiding per-token {@code String} creation in the hot path.
 *
 * <p><b>Policy note:</b> this filter intentionally keeps lookup policy explicit in the
 * implementation (for example, POS-specific lookup and fallback order).
 *
 * @see LemmaLexicon
 * @see LemmaAttribute
 * @see PosAttribute
 */
public final class LemmaFilter extends TokenFilter
{
    private final LemmaLexicon lex;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final LemmaAttribute lemAtt = addAttribute(LemmaAttribute.class);

    /**
     * Creates a lemmatization side-channel filter.
     *
     * @param input input token stream
     * @param lex lemma lexicon used to resolve forms and lemmas
     * @throws NullPointerException if {@code input} or {@code lex} is null
     */
    public LemmaFilter(TokenStream input, LemmaLexicon lex)
    {
        super(input);
        this.lex = Objects.requireNonNull(lex, "lex");
    }

    /**
     * Advances the stream by one token and populates {@link LemmaAttribute} when a distinct lemma
     * can be resolved for the current token.
     *
     * <p>The token text in {@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute}
     * is never modified by this filter.
     *
     * @return {@code true} if a token is available, {@code false} at end of stream
     * @throws IOException if the input stream throws while advancing
     */
    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken()) return false;
        
        // Invariant: lemma slot is empty unless this filter resolves and writes one.
        lemAtt.setEmpty();
        
        if (keywordAtt.isKeyword()) return true;

        final int posId = posAtt.getPos();
        if (posId == XML.code || Upos.isPunct(posId)) {
            return true;
        }
        // Surface known ?
        final int formId = lex.findFormId(termAtt);
        if (formId < 0) return true;

        // Lookup with pos
        int lemmaId = (posId >= 0) ? lex.findLemmaId(formId, posId) : -1;

        // Default lemma (pos-agnostic)
        if (lemmaId < 0) {
            lemmaId = lex.findLemmaId(formId); // returns -1 if none
        }

        // Nothing usable
        if (lemmaId < 0 || lemmaId == formId) return true;

        // Copy lemma 
        lex.copyForm(lemmaId, lemAtt);

        return true;
    }
}
