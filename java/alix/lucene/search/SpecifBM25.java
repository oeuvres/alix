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
 * 
 * @author glorieux-f
 */
public class SpecifBM25 extends Specif
{

  /** Classical BM25 param */
  private final double k1 = 1.2f;
  /** Classical BM25 param */
  private final double b = 0.75f;
  /** Cache idf for a term */
  private double idf;
  @Override
  public int type() {
    return TYPE_TFIDF;
  }
  
  @Override
  public double idf(final long formAllOccs, final int formAllDocs)
  {
    this.formAllOccs = formAllOccs;
    this.formAllDocs = formAllDocs;
    double l = 1; // 
    this.idf = Math.log(1.0 + (allDocs - formAllDocs + 0.5D) / (formAllDocs + 0.5D));
    return idf;
  }

  @Override
  public double tf(final int formDocOccs, final int docOccs)
  {
    return idf * (formDocOccs * (k1 + 1)) / (formDocOccs + k1 * (1 - b + b * docOccs / docAvg));
  }

}
