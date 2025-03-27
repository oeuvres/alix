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
package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

/**
 * Analysis scenario for French in Alix. The linguistic features of Alix are
 * language dependent.
 */
public class AnalyzerCloud extends Analyzer
{
    /**
     * Default constructor.
     */
    public AnalyzerCloud()
    {
        super();
    }

    @Override
    public TokenStreamComponents createComponents(String field)
    {
        final Tokenizer tokenizer = new TokenizerML();
        TokenStream ts = tokenizer; // segment words
        // interpret html tags as token events like para or section
        ts = new FilterHTML(ts);
        // fr split on ’ and -
        ts = new FilterAposHyphenFr(ts);
        // pos tagging before lemmatize
        ts = new FilterFrPos(ts);
        /*
        // provide lemma+pos
        ts = new FilterLemmatize(ts);
        // group compounds after lemmatization for verbal compounds
        ts = new FilterLocution(ts);
        // last filter èrepare term to index
        ts = new FilterCloud(ts);
        */
        ts = new FilterPosFin(ts);
        return new TokenStreamComponents(tokenizer, ts);
    }

}
