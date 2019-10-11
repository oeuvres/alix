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

public class ScorerBM25 extends Scorer
{

  /** Classical BM25 param */
  private final double k1 = 1.2f;
  /** Classical BM25 param */
  private final double b = 0.75f;
  /** Store idf */
  double idf;

  public ScorerBM25()
  {
    
  }

  public ScorerBM25(final long occsAll, final int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  
  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
    this.idf = (double) Math.log(1.0 + (docsAll - docsMatch + 0.5D) / (docsMatch + 0.5D));
  }

  @Override
  public double score(final int occsDoc, final long docLen)
  {
    return idf * (occsDoc * (k1 + 1)) / (occsDoc + k1 * (1 - b + b * docLen / docAvg));
  }

}
