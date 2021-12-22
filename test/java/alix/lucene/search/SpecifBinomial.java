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

/**
 * Implementation of a binomial scorer. Crash number limits.
 * 
 * @author glorieux-f
 */
public class SpecifBinomial extends Specif
{
  @Override
  public int type() {
    return TYPE_PROB;
  }

  static double binomialCoef(final double n, double k) 
  { 
      double res = 1.0; 

      // Since C(n, k) = C(n, n-k) 
      if (k > n - k) k = n - k; 

      // Calculate value of 
      // [n * (n-1) *---* (n-k+1)] / [k * (k-1) *----* 1] 
      for (int i = 0; i < k; ++i) { 
          res *= (n - i); 
          res /= (i + 1); 
      } 

      return res; 
  } 
  
  /**
   * Find a one tailed exact binomial test probability.  Finds the chance
   * of this or a higher result
   *
   * @param k number of successes
   * @param n Number of trials
   * @param p Probability of a success
   */
  public static double binomial(final double n, final double k, double p) {
    // maybe cached if we use the same in a loop
    double coef = binomialCoef(n, k);
    return coef * Math.pow(p, k) * Math.pow(1.0 - p, n - k);
  }

  @Override
  public double prob(final double formPartOccs, final double formAllOccs)
  {
    double mean = (double)formAllOccs / allOccs;
    double p = binomial((long)partOccs, formPartOccs, mean);
    if (((float)formPartOccs / partOccs) > mean) return p;
    else return -p;
  }

}
