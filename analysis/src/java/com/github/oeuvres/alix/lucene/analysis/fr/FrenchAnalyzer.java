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
import java.nio.file.Path;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import com.github.oeuvres.alix.lucene.analysis.CleanupFilter;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.LemmaLexicon;
import com.github.oeuvres.alix.lucene.analysis.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.MarkupBoundaryFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MarkupZoneFilter;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.SentenceStartLowerCaseFilter;
import com.github.oeuvres.alix.lucene.analysis.TermReplaceFilter;

import opennlp.tools.postag.POSModel;

/**
 * Analysis scenario for French in Alix. The linguistic features of Alix are
 * language dependent.
 */
public class FrenchAnalyzer extends DelegatingAnalyzerWrapper
{
    final static String POS_PATH = "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";
    private static final POSModel POS_MODEL = LexiconHelper.loadPosModel(FrenchAnalyzer.class, POS_PATH);

    final Analyzer ascii;
    final Analyzer canonic;
    final Analyzer observation;
    /** Stopwords list */
    public final CharArraySet stopwords;
    /** Words with ending dots */
    public final CharArraySet brevidots;
    /** Term normalizer */
    public final CharArrayMap<char[]> normalizer;
    /** Big dic */
    public final LemmaLexicon lemmaLexicon;
    
    /**
     * Default constructor.
     * @throws IOException 
     */
    public FrenchAnalyzer() throws IOException
    {
        super(PER_FIELD_REUSE_STRATEGY);
        stopwords = FrenchLexicons.buildStopwords();
        normalizer = FrenchLexicons.buildNormalizer();
        lemmaLexicon = FrenchLexicons.buildLemmaLexicon();
        brevidots = FrenchLexicons.buildBrevidots();
        
        canonic = new CanonicAnalyzer();
        ascii = new AsciiAnalyzer();
        observation = new ObservationAnalyzer();
    }
    
    public void addStopWords(Path... files) throws IOException {
        for (Path path: files) {
            LexiconHelper.loadSet(stopwords, path);
        }
    }
    public void addNormalizations(Path... files) throws IOException {
        for (Path path: files) {
            LexiconHelper.loadMap(normalizer, path, LexiconHelper.OnDuplicate.REPLACE);
        }
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
    
    public class CanonicAnalyzer extends Analyzer
    {
        
        public CanonicAnalyzer()
        {
            super();
        }
        
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
            TokenStream ts = canonicChain(tokenizer);
            return new TokenStreamComponents(tokenizer, ts);
        }
        
    }
    
    public class ObservationAnalyzer extends Analyzer
    {
        
        public ObservationAnalyzer()
        {
            super();
        }
        
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
            TokenStream ts = tokenizer;
            // keep observations only
            ts = new MarkupZoneFilter(ts, "@data-tei-type=\"observation\"", MarkupZoneFilter.Mode.INCLUDE);
            ts = canonicChain(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }
        
    }

    
    public class AsciiAnalyzer extends Analyzer
    {
        
        public AsciiAnalyzer()
        {
            super();
        }
        
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
            TokenStream ts = canonicChain(tokenizer);
            ts = new ASCIIFoldingFilter(ts); // no accents
            return new TokenStreamComponents(tokenizer, ts);
        }
        
    }
    
    /**
     * Build the shared canonic filter chain starting from an arbitrary upstream.
     * Optionally fold accents for _ascii fields.
     */
    private TokenStream canonicChain(TokenStream ts)
    {
        // resolve case of common word at start of sentence
        ts = new SentenceStartLowerCaseFilter(ts, lemmaLexicon);
        // interpret html tags as token events like para or section
        ts = new MarkupBoundaryFilter(ts);
        // fr split on ’ and -
        ts = new FrenchCliticSplitFilter(ts);
        // normalizations
        ts = new TermReplaceFilter(ts, normalizer);
        // pos tagging before lemmatize
        ts = new PosTaggingFilter(ts, POS_MODEL, PosTaggingFilter.HYPHEN_REWRITER);
        // provide lemma
        ts = new LemmaFilter(ts, lemmaLexicon);
        // TODO, multi word expression
        
        // delete positions of xml tags and punctuation
        ts = new CleanupFilter(ts);
        // clean stop words but keep positions
        ts = new StopFilter(ts, stopwords);
        return ts;
    }
}
