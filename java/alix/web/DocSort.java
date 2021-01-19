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

import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;

import alix.lucene.Alix;
import alix.web.Option;

public enum DocSort implements Option
{
  score("Pertinence", null),
  year("Année (+ ancien)", new Sort(new SortField("year", SortField.Type.INT))),
  year_inv("Année (+ récent)", new Sort(new SortField("year", SortField.Type.INT, true))),
  author("Auteur (A-Z)", new Sort(new SortField(Alix.ID, SortField.Type.STRING))),
  author_inv("Auteur (Z-A)", new Sort(new SortField(Alix.ID, SortField.Type.STRING, true))),
  //freq("Fréquence"),
  // "tf-idf", "bm25", "dfi_chi2", "dfi_std", "dfi_sat", 
  // "lmd", "lmd0.1", "lmd0.7", "dfr", "ib"
  // "tf-idf", "BM25", "DFI chi²", "DFI standard", "DFI saturé", 
  // "LMD", "LMD λ=0.1", "LMD λ=0.7", "DFR", "IB"
  ;
  public final Sort sort;
  final public String label;
  private DocSort(final String label, final Sort sort)
  {
    this.label = label;
    this.sort = sort;
  }
  public Sort sort()
  {
    return sort;
  }
  public String label() { return label; }
  public String hint() { return ""; }
}
