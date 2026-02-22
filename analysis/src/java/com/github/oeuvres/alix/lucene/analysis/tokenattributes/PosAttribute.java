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

package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

/**
 * Part-of-speech (POS) attribute for a token.
 *
 * <p>This attribute is intended to carry a Universal Dependencies "UPOS" tag (or a project-specific
 * POS code) as an integer. It deliberately does <em>not</em> reuse {@link org.apache.lucene.analysis.tokenattributes.FlagsAttribute}
 * so that structural/control flags (sentence boundary, paragraph boundary, synthetic tokens, etc.)
 * remain independent from linguistic tags (UPOS values such as {@code PUNCT}).
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #UNKNOWN} means "unset / unknown / not applicable".</li>
 *   <li>Otherwise the value is an application-defined POS code (typically {@code com.github.oeuvres.alix.common.Upos#code()}).</li>
 * </ul>
 *
 * <h2>Why a dedicated attribute?</h2>
 * <p>Lucene's analysis chain is frequently composed of multiple TokenFilters. Overloading an existing
 * attribute with unrelated semantics tends to create collisions and makes downstream consumption fragile.
 * A dedicated attribute provides an explicit contract for POS tagging.</p>
 */
public interface PosAttribute extends Attribute {

    /**
     * Sentinel value for "unknown / unset / not applicable".
     */
    int UNKNOWN = 0;

    /**
     * Return the POS code for the current token.
     *
     * @return POS code, or {@link #UNKNOWN} if unset/unknown.
     */
    int getPos();

    /**
     * Set the POS code for the current token.
     *
     * @param pos POS code, or {@link #UNKNOWN} to clear.
     */
    void setPos(int pos);
}
