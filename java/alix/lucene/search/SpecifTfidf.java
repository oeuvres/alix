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
 * <p>“tf-idf” used as an algorithm to find specific search of one or more
 * documents (a part), compared to the whole corpus.
 * 
 * <p>“tf-idf” is a family of algorithms, quite fast to calculate,
 * commonly used for information retrieval
 * to score relevant documents according to a search 
 * (one or a few search against texts to find most relevant texts).
 * In such formulas, a very common word among a corpus 
 * (ex : a function word, “de”) will have a light effect to score a document.
 * The score should be affected by a term less common, and more specific.
 * It is the idea of the “idf” (inverse document frequency), to weight more
 * the uncommon search.
 * The total score for a document is the sum of scores of each term of the search
 * against this document.
 * Nice tuning is coming from coefficients and order of magnitudes
 * (log, power, square root…)
 * 
 * 
 * <p>Using it to find most relevant search from a part (a few documents),
 * compared to a whole (complete corpora), is less common, but produce
 * interesting results. Idea is to use the same score 
 * between a term and a document, and adding them for each term 
 * (instead of by document for document scoring).
 * Information retrieval engines are not optimized for such loops,
 * but nowadays more memory makes it easy.
 * 
 */

public class SpecifTfidf extends Specif
{
  /** Store idf */
  double idf;
  /** A traditional coefficient */
  final double k = 0.2F;

  @Override
  public int type() {
    return TYPE_TFIDF;
  }
  @Override
  public double idf(final double formAllOccs, final double formAllDocs)
  {
    this.formAllOccs = formAllOccs;
    this.formAllDocs = formAllDocs;
    final double l = 1d; // 
    final double toPow = 1d + Math.log((allDocs +l ) / (formAllDocs + l));
    this.idf =  toPow * toPow;
    return idf;
  }

  @Override
  public double tf(final double formDocOccs, final double docOccs)
  {
    return idf * (k + (1 - k) * (double) formDocOccs / (double) docOccs);
  }

}
