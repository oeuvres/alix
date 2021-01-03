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
package alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

/**
 * Analysis scenario for French in Alix.
 * The linguistic features of Alix are language dependent.
 */
public class FrAnalyzer extends Analyzer
{
  /** 
   * Force creation of a new token stream pipeline, for multi threads 
   * indexing.
   */
  
  @Override
  public TokenStreamComponents createComponents(String field)
  {
    int flags = FrTokenizer.XML;
    if ("search".startsWith(field)) flags = flags | FrTokenizer.QUERY;
    final Tokenizer source = new FrTokenizer(flags); // segment words
    TokenStream result = new FrLemFilter(source); // provide lemma+pos
    result = new LocutionFilter(result); // compounds: parce que
    // result = new FrPersnameFilter(result); // link unknown names, seems buggy
    boolean pun = false;
    if ("search".startsWith(field)) pun = true; // keep punctuation, ex, to parse search
    result = new FlagCloudFilter(result, pun); // select lemmas as term to index
    return new TokenStreamComponents(source, result);
  }
  

}
