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
package alix.web;

/**
 * Some algorithms to score co-occurrency.
 * 
 * @author glorieux-f
 */
public enum MI implements Option
{
  occs("Occurrences", "Oab") {
    @Override
    public double score(final double Oab, final double Oa, final double Ob, final double N)
    {
      return Oab;
    }
  },
  g("G-test (log-likelihood)", "G = 2 Σ(Oi.ln(Oi/Ei))") {
    public double score(final double Oab, final double Oa, final double Ob, final double N)
    {
      double[] E = E(Oab, Oa, Ob, N);
      double[] O = O(Oab, Oa, Ob, N);
      int n = 4;
      double sum = 0d;
      for (int i = 0; i < n; i++) {
        if (O[i] == 0) continue; // is it gut ?
        sum += O[i] * Math.log(O[i]/E[i]);
      }
      if (Oab < E[0]) return sum * -2;
      return sum * 2;
    }  
  },
  jaccard("Jaccard", "m11 / (m10 + m01 + m11)") {
    @Override
    public double score(final double Oab, final double Oa, final double Ob, final double N)
    {
      double m10 = N - Oa - Oab;
      double m01 = N - Ob - Oab;
      return 100000 * Oab / ( m10 + m01 + Oab);
    }  
  },
  dice("Dice", "2*m11 / (m10² + m01²)") {
    @Override
    public double score(final double Oab, final double Oa, final double Ob, final double N)
    {
      double m10 = N - Oa - Oab;
      double m01 = N - Ob - Oab;
      return 100000 * 2 * Oab / (m10 * m10 + m01 * m01);
    }
  },
  chi2("Chi2", "Chi2 = Σ(Oi - Ei)²/Ei") {
    public double score(final double Oab, final double Oa, final double Ob, final double N)
    {
      double[] E = E(Oab, Oa, Ob, N);
      double[] O = O(Oab, Oa, Ob, N);
      int n = 4;
      double sum = 0d;
      for (int i = 0; i < n; i++) {
        if (O[i] == 0) continue; // is it gut ?
        double O_E = O[i] - E[i];
        sum += O_E * O_E / E[i];
      }
      if (Oab < E[0]) return -sum;
      return sum;
    }
  },
  /*
  fisher("Fisher (hypergéométrique)", "p = (AB + A¬B)!(¬AB + ¬A¬B)!(AB + ¬AB)!(A¬B + ¬A¬B) / (AB!A¬B!¬AB!¬A¬B!)") {
    public double score(final double Oab, final double Oa, final double Ob, final double N)
    {
      double[] res = Fisher.test((int)Oab, (int)(Oa - Oab), (int)(Ob - Oab), (int)(N - Oa - Ob + Oab));
      return - Math.log(res[0]);
    }  
  },
  */
  ;

  abstract public double score(final double Oab, final double Oa, final double Ob, final double N);

  protected double[] E(final double Oab, final double Oa, final double Ob, final double N)
  {
    double[] E = {Oa*Ob/N, (N-Oa)*Ob/N, Oa*(N-Ob)/N, (N-Oa)*(N-Ob)/N};
    return E;
  }
  protected double[] O(final double Oab, final double Oa, final double Ob, final double N)
  {
    double[] O = {Oab, Ob-Oab, Oa-Oab, N-Oa-Ob+Oab};
    return O;
  }

  final public String label;
  public String label() { return label; }
  final public String hint;
  public String hint() { return hint; }
  private MI(final String label, final String hint)
  {
    this.label = label;
    this.hint = hint;
  }
  
  


}