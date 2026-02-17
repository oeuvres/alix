/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
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

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Rewrite tokens by applying an exact, dictionary-based term mapping.
 *
 * <p>This filter performs an <em>in-place</em> replacement of the current token term
 * (the {@link CharTermAttribute}) when it matches a key in the provided
 * {@link CharArrayMap}. It emits <strong>exactly one</strong> token for each input token:
 * no synonym expansion, no alternate paths, and no token graphs.</p>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Match scope:</strong> whole-token match only (single token → single token).</li>
 *   <li><strong>Replacement:</strong> replaces the term text only; other attributes are untouched.</li>
 *   <li><strong>Offsets/positions:</strong> not modified (offsets remain those of the original text).</li>
 *   <li><strong>Tokenizer visibility:</strong> only terms that actually appear as tokens can be mapped;
 *       characters removed/merged by the tokenizer will never match (use a CharFilter for those).</li>
 * </ul>
 *
 * <h2>Configuration notes</h2>
 * <ul>
 *   <li>Case-sensitivity depends on how the map is built and where this filter sits in the chain.
 *       If you lowercase upstream, store lowercase keys/values.</li>
 *   <li>The provided map should be treated as immutable for the lifetime of the analyzer/filter
 *       instance (do not mutate it concurrently).</li>
 * </ul>
 *
 * @see org.apache.lucene.analysis.charfilter.MappingCharFilter
 * @see org.apache.lucene.analysis.synonym.SynonymGraphFilter
 */
public final class TermMappingFilter extends TokenFilter {

    /**
     * Term rewrite table. Keys are matched against the current token term; values are copied
     * into the {@link CharTermAttribute} when a match is found.
     */
    private final CharArrayMap<char[]> map;

    /** The current token term. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /**
     * Create a new {@code TermMappingFilter}.
     *
     * @param input the upstream {@link TokenStream} (tokenizer or previous filter)
     * @param map the rewrite table mapping surface forms to replacement forms
     */
    public TermMappingFilter(final TokenStream input, final CharArrayMap<char[]> map) {
        super(input);
        this.map = map;
    }

    /**
     * Advance to the next token, rewriting its term if a mapping exists.
     *
     * @return {@code true} if a token is available; {@code false} if end of stream
     * @throws IOException if the upstream stream throws an {@link IOException}
     */
    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) return false;

        final char[] replacement = map.get(termAtt.buffer(), 0, termAtt.length());
        if (replacement != null) {
            termAtt.copyBuffer(replacement, 0, replacement.length);
        }
        return true;
    }
}


