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
 * Interface for a scorer to calculate specific search from a corpus or a part.
 * There are two approaches :
 * <li>Classical probabilities, considering a part compared to a whole corpus
 * <li>Tf-idf like, considering corpus divided in documents
 * 
 * <p>These approaches needs different strategy to cache values to have a result.
 * An implementation should answer if it is tf() like, or prob() like.
 * 
 * <p>Common variables
 * <li>allOccs : corpus, global word count
 * <li>allDocs : corpus, global document count
 * <li>formAllOccs : one form, all occurrences
 * <li>partOccs : part, word count
 * <li>formPart : count of occurrences for a form in a corpus
 *
 * @author glorieux-f
 */
public abstract class Specif
{
  /** Total count of occurrences in the base (big word count) ; as double to avoid casting */
  protected double allOccs;
  /** Total count of documents in the base */
  protected double allDocs;
  /** Total count of occurrences for a form */
  protected double formAllOccs;
  /** Total count of documents for a form */
  protected double formAllDocs;
  /** Average of document length */
  protected double docAvg;
  /** Word count for a part */
  protected double partOccs;
  /** Document count for a part */
  protected double partDocs;
  
  /**
   * Set colletion stats, not modified by queries.
   * @param occsAll Total count of occurrences in the collection.
   * @param docsAll Total count of documents in the collection.
   */
  public void all(final double allOccs, final double allDocs) {
    this.allOccs = allOccs;
    this.allDocs = allDocs;
    this.docAvg = (double) allOccs / allDocs;
  }
  
  public long allOccs() {
    return (long)allOccs;
  }
  public int allDocs() {
    return (int)allDocs;
  }
  
  /**
   * Set variables useful to calculate an idf (inverse document frequency) for a form.
   * Are also used by classical prob metrics.
   */
  public double idf(final double formAllOccs, final double formAllDocs) {
    this.formAllOccs = formAllOccs;
    this.formAllDocs = formAllDocs;
    return 0;
  }

  /**
   * Returns a score for a term frequency in a document (tf)
   */
  public abstract double tf(final double formDocOccs, final double docOccs);

  /**
   * Set variables common to a part of a corpus, with no reference to a form.
   * These variables are not used in classical tf-idf, but in lexicometry.
   * 
   * @param partOccs, word count for the part
   * @param partDocs, count of documents in the part
   */
  public double part(final double partOccs, final double partDocs) {
    this.partOccs = partOccs;
    this.partDocs = partDocs;
    return 0;
  }

  /**
   * Calculate the probability of a form in a part, according to a distribution 
   * inferred from all corpus. The tfSum maybe used in some formulas and should
   * be maintained by caller.
   * 
   * @param tfSum
   * @param formPartOccs 
   * @param formAllOccs
   * @return
   */
  public abstract double prob(final double tfSum, final double formPartOccs, final double formAllOccs);
  

}
