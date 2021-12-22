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
 * Implementation of a Chi2 scorer (Pearson) like described by C. Muller (1968)
 * <br/>Oi = Observation i
 * <br/>Ei = Expectation i
 * <br/>ΣOi = ΣEi = N (total of events)
 * <br/>Chi2 = Σ(Oi - Ei)²/Ei
 *
 * https://en.wikipedia.org/wiki/Chi-squared_test
 * 
 * 
 * @author glorieux-f
 */
public class SpecifChi2tf extends Specif
{

  @Override
  public double tf(final double Oi, final double docOccs) {
    double Ei = docOccs * formAllOccs / allOccs;
    return (Oi - Ei) * (Oi - Ei) / Ei;
  }

  
  @Override
  public double prob(final double tfSum, final double formPartOccs, final double formAllOccs)
  {
    // minimum 
    if (formAllOccs < 4) return 0;
    double Oz = allOccs - formPartOccs;
    double Ez = allOccs - (formAllOccs * partOccs / allOccs);
    double p = tfSum + (Oz - Ez) * (Oz - Ez) / Ez;

    
    double mean = formAllOccs / allOccs;
    if ((formPartOccs / partOccs) > mean) return p;
    else return -p;
  }


}
