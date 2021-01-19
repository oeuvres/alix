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

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;

import alix.lucene.search.SimilarityChi2;
import alix.lucene.search.SimilarityChi2inv;
import alix.lucene.search.SimilarityG;
import alix.lucene.search.SimilarityOccs;

public enum Sim implements Option {
  occs("Occurrences") {
    private Similarity similarity = new SimilarityOccs();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  g("G-Test") {
    private Similarity similarity = new SimilarityG();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  chi2("Chi2") {
    private Similarity similarity = new SimilarityChi2();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  chi2inv("Chi2 inverse") {
    private Similarity similarity = new SimilarityChi2inv();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  bm25("BM25") {
    private Similarity similarity = new BM25Similarity();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  



  
  ;

  abstract public Similarity similarity();

  
  private Sim(final String label) {  
    this.label = label ;
  }

  // Repeating myself
  final public String label;
  public String label() { return label; }
  public String hint() { return null; }
}
