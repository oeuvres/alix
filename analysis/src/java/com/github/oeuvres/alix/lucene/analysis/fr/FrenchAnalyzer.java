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
package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.io.InputStream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import com.github.oeuvres.alix.lucene.analysis.FinalCleanupFilter;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupBoundaryFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MarkupZoneFilter;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.SentenceStartLowerCaseFilter;

import opennlp.tools.postag.POSModel;

/**
 * Analysis scenario for French in Alix. The linguistic features of Alix are
 * language dependent.
 */
public class FrenchAnalyzer extends DelegatingAnalyzerWrapper
{
    final static String POS_PATH = "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";
    private static final POSModel POS_MODEL = loadPosModel(POS_PATH);
    final Analyzer ascii;
    final Analyzer canonic;
    final Analyzer observation;
    
    /**
     * Default constructor.
     */
    public FrenchAnalyzer()
    {
        super(PER_FIELD_REUSE_STRATEGY);
        canonic = new CanonicAnalyzer();
        ascii = new AsciiAnalyzer();
        observation = new ObservationAnalyzer();
    }
    
    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName)
    {
        if (fieldName.startsWith("obs")) {
            return observation;
        } else 
        if (fieldName.endsWith("_ascii")) {
            return ascii;
        } else {
            return canonic;
        }
    }
    
    public static class CanonicAnalyzer extends Analyzer
    {
        
        public CanonicAnalyzer()
        {
            super();
        }
        
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MarkupTokenizer();
            // segment words
            TokenStream ts = tokenizer;
            // resolve case of common word at start of senetence
            ts = new SentenceStartLowerCaseFilter(ts, FrenchLexicons.getLemmaLexicon());
            // interpret html tags as token events like para or section
            ts = new MarkupBoundaryFilter(ts);
            // fr split on ’ and -
            ts = new FrenchCliticSplitFilter(ts);
            // pos tagging before lemmatize
            ts = new PosTaggingFilter(ts, POS_MODEL, PosTaggingFilter.HYPHEN_REWRITER);
            // provide lemma
            ts = new LemmaFilter(ts, FrenchLexicons.getLemmaLexicon());
            // TODO, multi word expression
            // last filter prepare term to index
            ts = new FinalCleanupFilter(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }
        
    }
    
    public static class ObservationAnalyzer extends Analyzer
    {
        
        public ObservationAnalyzer()
        {
            super();
        }
        
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MarkupTokenizer();
            // segment words
            TokenStream ts = tokenizer;
            // keep observations only
            ts = new MarkupZoneFilter(ts, "@data-tei-type=\"observation\"", MarkupZoneFilter.Mode.INCLUDE);
            // resolve case of common word at start of senetence
            ts = new SentenceStartLowerCaseFilter(ts, FrenchLexicons.getLemmaLexicon());
            // interpret html tags as token events like para or section
            ts = new MarkupBoundaryFilter(ts);
            // fr split on ’ and -
            ts = new FrenchCliticSplitFilter(ts);
            // pos tagging before lemmatize
            ts = new PosTaggingFilter(ts, POS_MODEL, PosTaggingFilter.HYPHEN_REWRITER);
            // provide lemma
            ts = new LemmaFilter(ts, FrenchLexicons.getLemmaLexicon());
            // TODO, multi word expression
            // last filter prepare term to index
            ts = new FinalCleanupFilter(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }
        
    }

    
    public static class AsciiAnalyzer extends Analyzer
    {
        
        public AsciiAnalyzer()
        {
            super();
        }
        
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MarkupTokenizer();
            TokenStream ts = tokenizer; // segment words
            // resolve case of common word at start of senetence
            ts = new SentenceStartLowerCaseFilter(ts, FrenchLexicons.getLemmaLexicon());
            // interpret html tags as token events like para or section
            ts = new MarkupBoundaryFilter(ts);
            // fr split on ’ and -
            ts = new FrenchCliticSplitFilter(ts);
            // pos tagging before lemmatize
            ts = new PosTaggingFilter(ts, POS_MODEL, PosTaggingFilter.HYPHEN_REWRITER);
            // provide lemma
            ts = new LemmaFilter(ts, FrenchLexicons.getLemmaLexicon());
            // TODO, multi word expression
            // last filter prepare term to index
            ts = new FinalCleanupFilter(ts);
            // no accents
            ts = new ASCIIFoldingFilter(ts); // no accents
            return new TokenStreamComponents(tokenizer, ts);
        }
        
    }
    
    private static POSModel loadPosModel(String path)
    {
        try (InputStream in = FrenchAnalyzer.class.getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("Missing resource: " + path);
            return new POSModel(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
