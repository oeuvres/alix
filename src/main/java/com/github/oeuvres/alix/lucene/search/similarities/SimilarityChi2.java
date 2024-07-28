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
package com.github.oeuvres.alix.lucene.search.similarities;

import org.apache.lucene.search.similarities.BasicStats;
import org.apache.lucene.search.similarities.SimilarityBase;

/**
 * Implementation of a Chi2 Scoring with negative scores to get the most
 * repulsed doc from a search. Code structure taken from
 * {@link org.apache.lucene.search.similarities.DFISimilarity}
 */
public class SimilarityChi2 extends SimilarityBase
{

    @Override
    protected double score(BasicStats stats, double freq, double docLen)
    {
        // if (stats.getNumberOfFieldTokens() == 0) return 0; // ??
        final double expected = stats.getTotalTermFreq() * docLen / stats.getNumberOfFieldTokens();
        double measure = (freq - expected) * (freq - expected) / expected;
        if (measure == 0)
            return 0;
        // against theory, a log is necessary to limit effect of too common word in a
        // multi term query
        // results are less relevant than default BM25
        measure = stats.getBoost() * log2(measure);
        // if the observed frequency is less than expected, return negative (should be
        // nice in multi term search)
        if (freq < expected)
            return -measure;
        return measure;
    }

    /*
     * @Override protected Explanation explain(BasicStats stats, Explanation freq,
     * double docLen) { final double expected = (stats.getTotalTermFreq() + 1) *
     * docLen / (stats.getNumberOfFieldTokens() + 1); if
     * (freq.getValue().doubleValue() <= expected) { return Explanation.match(
     * (float) 0, "score(" + getClass().getSimpleName() + ", freq=" +
     * freq.getValue() + "), equals to 0"); } Explanation explExpected =
     * Explanation.match( (float) expected,
     * "expected, computed as (F + 1) * dl / (T + 1) from:", Explanation.match(
     * stats.getTotalTermFreq(),
     * "F, total number of occurrences of term across all docs"),
     * Explanation.match((float) docLen, "dl, length of field"), Explanation.match(
     * stats.getNumberOfFieldTokens(), "T, total number of tokens in the field"));
     * 
     * final double measure = independence.score(freq.getValue().doubleValue(),
     * expected); Explanation explMeasure = Explanation.match( (float) measure,
     * "measure, computed as independence.score(freq, expected) from:", freq,
     * explExpected);
     * 
     * return Explanation.match( (float) score(stats, freq.getValue().doubleValue(),
     * docLen), "score(" + getClass().getSimpleName() + ", freq=" + freq.getValue()
     * + "), computed as boost * log2(measure + 1) from:", Explanation.match((float)
     * stats.getBoost(), "boost, search boost"), explMeasure); }
     */

    @Override
    public String toString()
    {
        return "Chi2";
    }
}
