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
 * Implementation of a G-test scorer
 * <br/>Oi = Observation i
 * <br/>Ei = Expectation i
 * <br/>ΣOi = ΣEi = N (total of observation)
 * <br/>G = 2 Σ(Oi.ln(Oi/Ei))
 * https://en.wikipedia.org/wiki/G-test
 * 
 * @author glorieux-f
 *
 */
public class SpecifG extends Specif
{
  @Override
  public int type() {
    return TYPE_PROB;
  }


  /**
   * A G-score for 2 observations
   *
   * @param N The total number of balls
   * @param K The number of black balls
   * @param n The number of balls drawn
   * @param k The number of black balls drawn
   * @return The Fisher's exact p-value
   */
  public static double g(double N, double K, double n, double k) {
    
    double O0 = k;
    double E0 = n * K / N;
    double O1 = N - k;
    double E1 = N - E0;
    
    double sum = 0d;
    sum += O0 * Math.log(O0 / E0);
    sum += O1 * Math.log(O1 / E1);
    return sum * 2.0;
  }

  @Override
  public double prob(final double formPartOccs, final double formAllOccs)
  {
    if (formAllOccs < 4) return 0;

    // if (formPartOccs < FLOOR) return 0;
    double p = g(allOccs, formAllOccs,  partOccs, formPartOccs);
    // System.out.println("N="+ (int)allOccs +" K="+ (int)formAllOccs +" n="+(int)partOccs + " k="+(int)formPartOccs+ " p="+p);
    double mean = formAllOccs / allOccs;
    if ((formPartOccs / partOccs) > mean) return p;
    else return -p;
  }

}
