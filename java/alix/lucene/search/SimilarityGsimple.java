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
package alix.lucene.search;

import org.apache.lucene.search.similarities.BasicStats;
import org.apache.lucene.search.similarities.SimilarityBase;

/**
 * Implementation of a G-test Scoring with negative scores to get the 
 * most repulsed doc from a search. Code structure taken form {@link org.apache.lucene.search.similarities.DFISimilarity}
 */
public class SimilarityGsimple extends SimilarityBase {

  @Override
  protected double score(BasicStats stats, double freq, double docLen) {
    /*
    double O0 = k;
    double E0 = n * K / N;
    double O1 = N - k;
    double E1 = N - E0;
    // bad results  O0 * Math.log(O0 / E0);
    double sum = 0d;
    sum += O0 * Math.log(O0 / E0);
    sum += O1 * Math.log(O1 / E1);
    return sum * 2.0;
    */
    // if (stats.getNumberOfFieldTokens() == 0) return 0; // ??
    final long N = stats.getNumberOfFieldTokens();
    final double E0 = stats.getTotalTermFreq() * docLen / N;
    final double measure = freq * Math.log(freq / E0);
    // DFISimilarity returns log, with a 
    // return stats.getBoost() * log2(measure + 1);
    // if the observed frequency is less than expected, return negative (should be nice in multi term search)
    if (freq < E0) return -measure;
    return measure;
  }


  @Override
  public String toString() {
    return "G-test";
  }
}
