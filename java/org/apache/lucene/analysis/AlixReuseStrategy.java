/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache licence.
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
package org.apache.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Analyzer.ReuseStrategy;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.sinks.TeeSinkTokenFilter;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.index.IndexWriter;

import alix.lucene.SAXIndexer;

/**
 * <p>
 * Reuse strategy of {@link TokenStreamComponents} in Alix: 
 * no cache for indexation, reuse for query parsing.
 * </p>
 * 
 * <p>
 * Alix is designed to index real text documents (articles, book chapters…).
 * Indexing is multi-threaded,
 * and add documents by blocks (books) {@link IndexWriter#addDocuments(Iterable)}.
 * Life cycle of {@link TokenStream} components is hard to predict, the most robust
 * solution is then to not cache {@link TokenStreamComponents},
 * especially because {@link SAXIndexer} needs some advanced indexing features 
 * ({@link CachingTokenFilter} or a {@link TeeSinkTokenFilter}).
 * </p>
 * 
 * <p>
 * The default behavior of Analyzer with Strings was successfully tested with
 * {@link Field#Field(String, CharSequence, org.apache.lucene.index.IndexableFieldType)},
 * but problems came with {@link Field#Field(String, TokenStream, IndexableFieldType)}.
 * The default caching of components were confused 
 * (documents disappears, contract violation {@link TokenStream#reset()} 
 * and {{@link TokenStream#close()}).
 * <p/>
 * 
 * 
 */

public class AlixReuseStrategy extends ReuseStrategy
{

  @Override
  public TokenStreamComponents getReusableComponents(Analyzer analyzer, String fieldName)
  {
    if ("query".startsWith(fieldName)) return (TokenStreamComponents) getStoredValue(analyzer);
    return analyzer.createComponents(fieldName);
  }

  @Override
  public void setReusableComponents(Analyzer analyzer, String fieldName, TokenStreamComponents components)
  {
    setStoredValue(analyzer, components);
  }

}
