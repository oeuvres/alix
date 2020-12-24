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
 * Interface for a scorer, similar to tf-idf. Some counts that can
 * be captures
 * 
 * — wcAll : corpus, global word count
 * — docsAll : corpus, global document count
 * — formAll : corpus, one form count
 * — wcPart : part, word count
 * — formPart : count of occurrences for a form in a corpus

 * @author fred
 *
 */
public abstract class Specif
{
  /** Total count of occurrences in the base (big word count) ; as double to avoid casting */
  protected double occsAll;
  /** Total count of documents in the base */
  protected double docsAll;
  /** Average of document length */
  protected double docAvg;
  /** Word count for part */
  protected double occsPart;
  /** Document count for part */
  protected double docsPart;
  

  /**
   * Set colletion stats, not modified by queries.
   * @param occsAll Total count of occurrences in the collection.
   * @param docsAll Total count of documents in the collection.
   */
  public void setAll(final long occsAll, final int docsAll) {
    this.occsAll = occsAll;
    this.docsAll = docsAll;
    this.docAvg = (double) occsAll / docsAll;
  }
  
  public long occsAll() {
    return (long)occsAll;
  }
  public int docsAll() {
    return (int)docsAll;
  }
  /**
   * Set variables common to a part of corpus (a “query”), with no reference to a form
   * (like the Independant Document Frequency in the tf-idf).
   * The word count for the part is not used in classical tf-idf but is used in lexicometry.
   * 
   * @param occsPart, the small word count
   * @param docsPart count of documents in the part
   */
  public void weight(final long occsPart, final int docsPart) {
    this.occsPart = occsPart;
    this.docsPart = docsPart;
  }

  /**
   * Calculate score with form specific variables (like the “Term Frequency” in tf-idf).
   * The size of a relevant document is classical in tf-idf but assumes it is the only
   * relevant unit.
   * 
   * 
   * @param formPart count of matching occurrences in a document (or a section of corpus).
   * @param docLen Total count of occurrences for this document.
   * @return
   */
  abstract public double score(final long formPart, final long formAll);
  

}
