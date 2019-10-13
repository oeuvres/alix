/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
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
import org.apache.lucene.analysis.core.LetterTokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;

/**
 * Analysis scenario for French in Alix.
 * The linguistic features of Alix are language dependant.
 * 
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
    /*
    Tokenizer tokenizer = new LetterTokenizer();
    return new TokenStreamComponents(tokenizer);
    */
    final Tokenizer source = new FrTokenizer(); // segment words
    TokenStream result = new FrTokenLem(source); // provide lemma+pos
    result = new TokenLemCloud(result); // select lemmas as term to index
    return new TokenStreamComponents(source, result);
  }
  

}
