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
 * Implementation of the Lafon algorithm, used to score terms
 * https://www.persee.fr/docAsPDF/mots_0243-6450_1980_num_1_1_1008.pdf
 * Does not work for real life corpus, too much Infinity or NaN.
 * 
 * 
 * <li>N, population size, corpus word count, allOccs
 * <li>K, number of success, corpus form occurrences, formAllOccs
 * <li>n, number of draws, part word count, partOccs
 * <li>k, number of observed success, part form occurrences, formPartOccs
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

  /**
   * Find a hypergeometric distribution.  This uses exact math, trying
   * fairly hard to avoid numeric overflow by interleaving
   * multiplications and divisions.
   * (To do: make it even better at avoiding overflow, by using loops
   * that will do either a multiple or divide based on the size of the
   * intermediate result.)
   *
   * @param k The number of black balls drawn
   * @param n The total number of balls
   * @param r The number of black balls
   * @param m The number of balls drawn
   * @return The hypergeometric value
   */
  public static double hypergeometric(int k, int n, int r, int m) {
    if (k < 0 || r > n || m > n || n <= 0 || m < 0 || r < 0) {
      throw new IllegalArgumentException("Invalid hypergeometric");
    }

    // exploit symmetry of problem
    if (m > n / 2) {
      m = n - m;
      k = r - k;
    }
    if (r > n / 2) {
      r = n - r;
      k = m - k;
    }
    if (m > r) {
      int temp = m;
      m = r;
      r = temp;
    }
    // now we have that k <= m <= r <= n/2
    
    /*
    if (k < (m + r) - n || k > m) {
      return 0.0;
    }

    // Do limit cases explicitly
    // It's unclear whether this is a good idea.  I put it in fearing
    // numerical errors when the numbers seemed off, but actually there
    // was a bug in the Fisher's exact routine.
    if (r == n) {
      if (k == m) {
        return 1.0;
      } else {
        return 0.0;
      }
    } else if (r == n - 1) {
      if (k == m) {
        return (n - m) / (double) n;
      } else if (k == m - 1) {
        return m / (double) n;
      } else {
        return 0.0;
      }
    } else if (m == 1) {
      if (k == 0) {
        return (n - r) / (double) n;
      } else if (k == 1) {
        return r / (double) n;
      } else {
        return 0.0;
      }
    } else if (m == 0) {
      if (k == 0) {
        return 1.0;
      } else {
        return 0.0;
      }
    } else if (k == 0) {
      double ans = 1.0;
      for (int m0 = 0; m0 < m; m0++) {
        ans *= ((n - r) - m0);
        ans /= (n - m0);
      }
      return ans;
    }
    */

    double ans = 1.0;
    // do (n-r)x...x((n-r)-((m-k)-1))/n x...x (n-((m-k-1)))
    // leaving rest of denominator to get to multiply by (n-(m-1))
    // that's k things which goes into next loop
    for (int nr = n - r, n0 = n; nr > (n - r) - (m - k); nr--, n0--) {
      // System.out.println("Multiplying by " + nr);
      ans *= nr;
      // System.out.println("Dividing by " + n0);
      ans /= n0;
    }
    // System.out.println("Done phase 1");
    for (int k0 = 0; k0 < k; k0++) {
      ans *= (m - k0);
      // System.out.println("Multiplying by " + (m-k0));
      ans /= ((n - (m - k0)) + 1);
      // System.out.println("Dividing by " + ((n-(m+k0)+1)));
      ans *= (r - k0);
      // System.out.println("Multiplying by " + (r-k0));
      ans /= (k0 + 1);
      // System.out.println("Dividing by " + (k0+1));
    }
    return ans;
  }
  
  @Override
  public double prob(final double formPartOccs, final double formAllOccs)
  {
    if (formAllOccs < 4) return 0;
    double mean = formAllOccs / allOccs;
    
    double p = hypergeometric((int)formPartOccs, (int)allOccs, (int)formAllOccs, (int)partOccs);
    // if (p == Double.NEGATIVE_INFINITY) return -formPartOccs; 
    // else if (p == Double.POSITIVE_INFINITY) return formPartOccs; 
    // if (p == 0) return 758.25;
    // return -Math.log10(p);
    if (p == 0) {
      if ((formPartOccs / partOccs) > mean) return Double.POSITIVE_INFINITY;
      else return Double.NEGATIVE_INFINITY;
    }
    if ((formPartOccs / partOccs) > mean) return -Math.log10(p);
    else return Math.log10(p);
  }
  
}
