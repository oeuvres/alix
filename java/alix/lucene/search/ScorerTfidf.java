/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
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

public class ScorerTfidf extends Scorer
{
  /** Store idf */
  double idf;
  /** A traditional coefficient */
  final double k = 0.2F;

  public ScorerTfidf()
  {
    
  }
  
  ScorerTfidf(long occsAll, int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
    double l = 0;
    this.idf = (double) (Math.log((docsAll +l ) / (double) (docsMatch + l)) );
  }

  @Override
  public double score(final int occsMatch, final long docLen)
  {
    return idf * (k + (1 - k) * (double) occsMatch / (double) docLen);
    // return idf * (1 +(float)Math.log(occsMatch));
  }

}
