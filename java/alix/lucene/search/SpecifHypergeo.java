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
import org.apache.commons.math3.distribution.HypergeometricDistribution;
/**
 * Implementation of the Lafon algorithm, used to score terms
 * https://www.persee.fr/docAsPDF/mots_0243-6450_1980_num_1_1_1008.pdf
 * 
 * <li>N, population size, corpus word count, occsAll
 * <li>K, number of success, corpus form occurrences, formAll
 * <li>n, number of draws, part word count, occsPart
 * <li>k, number of observed success, part form occurrences, formPart
 * 
 * @author glorieux-f
 *
 */
public class SpecifHypergeo extends Specif
{
  final static int FLOOR = 3;
  @Override
  public int type() {
    return TYPE_PROB;
  }

  @Override
  public double prob(final long formPartOccs, final long formAllOccs)
  {
    if (formPartOccs < FLOOR) return 0;
    // (int populationSize, int numberOfSuccesses, int sampleSize)
    HypergeometricDistribution hyper = new HypergeometricDistribution((int)allOccs, (int)formAllOccs, (int)partOccs);
    // double p = - hyper.logProbability((int)formPartOccs);
    double p = hyper.probability((int)formPartOccs); // NO positive or negative infinity found proba for Rougemont
    // if (p == Double.NEGATIVE_INFINITY) return -formPartOccs; 
    // else if (p == Double.POSITIVE_INFINITY) return formPartOccs; 
    if (p == 0) return 0;
    return -Math.log10(p);
  }
  
}
