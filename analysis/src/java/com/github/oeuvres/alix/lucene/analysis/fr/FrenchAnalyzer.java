/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 * Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010 Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import com.github.oeuvres.alix.lucene.analysis.CleanupFilter;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupBoundaryFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupZoneFilter;
import com.github.oeuvres.alix.lucene.analysis.MweFilter;
import com.github.oeuvres.alix.lucene.analysis.NumberFilter;
import com.github.oeuvres.alix.lucene.analysis.PosTagger;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.ReplaceFilter;
import com.github.oeuvres.alix.lucene.analysis.UppercaseFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer.MarkupMode;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.MweLexicon;

import opennlp.tools.postag.MutableTagDictionary;
import opennlp.tools.postag.POSModel;

/**
 * French analysis scenario used by Alix.
 *
 * <p>The analyzer owns mutable lexical resources. Expressions loaded through
 * {@link #addExpressions(List)} are compiled with the normalization
 * resources present at call time. Previously compiled expressions are not
 * rebuilt when those resources are modified later. Configure normalization
 * before adding expressions, and complete all MWE additions
 * before freezing the lexicon or starting analysis.</p>
 */
public class FrenchAnalyzer extends DelegatingAnalyzerWrapper
{
    /** OpenNLP POS model resource. */
    private static final String POS_PATH =
        "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";

    /** Shared immutable POS model. */
    private static final POSModel POS_MODEL =
        LexiconHelper.loadPosModel(FrenchAnalyzer.class, POS_PATH);

    /** Shared thread-safe POS decoder for this analyzer. */
    private final PosTagger posTagger;

    /** Markup parsing mode used by every tokenizer created by this analyzer. */
    private final MarkupMode markupMode;

    /** Word2vec analyzer. */
    private final Analyzer word2vec;

    /** Tsv analyzer. */
    private final Analyzer tsv;

    /** Canonical indexing analyzer. */
    private final Analyzer canonic;

    /** Analyzer used only to compile MWE lexicon entries. */
    private final Analyzer mweEntryAnalyzer;

    /** Compiler retained for default and corpus-specific MWE declarations. */
    private final VerbalMweCompiler mweCompiler;

    /** Observation-zone analyzer. */
    private final Analyzer observation;

    /** Words with ending dots. */
    public final CharArraySet brevidots;

    /** Multi-word expressions. */
    public final MweLexicon expressions;

    /** Lemma dictionary. */
    public final LemmaLexicon lemmaLexicon;

    /** Shared numerical vocabulary. */
    private final NumberFilter.Lexicon numbers;

    /** Term normalizer. */
    public final CharsMap normalizer;

    /** Proper names protected from lower-casing lemma lookup. */
    public final CharArraySet propn;

    /** Stop-word set. */
    public final CharArraySet stopwords;

    /** Function word set. */
    public final CharArraySet gramwords;

    /** Noise tokens set. */
    public final CharArraySet noisetokens;

    /** Uppercase words protected by the uppercase filter. */
    public final CharArraySet ucwords;

    /** POS constraints loaded from word.csv. Configure before concurrent analysis. */
    public final MutableTagDictionary tagDic;

    /**
     * Builds a French analyzer with the default lexical resources.
     *
     * @throws IOException if an analyzer resource cannot be initialized
     */
    public FrenchAnalyzer() throws IOException
    {
        this(MarkupMode.XML);
    }

    /**
     * Builds a French analyzer with the default lexical resources and markup mode.
     *
     * @param markupMode markup parsing mode used by the analyzer tokenizers
     * @throws IOException if an analyzer resource cannot be initialized
     */
    public FrenchAnalyzer(final MarkupMode markupMode) throws IOException
    {
        super(PER_FIELD_REUSE_STRATEGY);
        this.markupMode = (markupMode == null) ? MarkupMode.XML : markupMode;

        numbers = FrenchLexicons.buildNumberLexicon();
        stopwords = FrenchLexicons.buildStopwords();
        gramwords = FrenchLexicons.buildGramwords();
        noisetokens = FrenchLexicons.buildNoisetokens();
        normalizer = FrenchLexicons.buildNormalizer();
        lemmaLexicon = FrenchLexicons.buildLemmaLexicon();
        brevidots = FrenchLexicons.buildBrevidots();
        propn = FrenchLexicons.buildPropn();
        ucwords = FrenchLexicons.buildUcwords();
        tagDic = FrenchLexicons.buildTagDictionary();
        posTagger = new PosTagger(POS_MODEL, tagDic);
        
        mweEntryAnalyzer = new MweEntryAnalyzer();
        mweCompiler = FrenchLexicons.buildMweCompiler(mweEntryAnalyzer);
        expressions = mweCompiler.lexicon();

        canonic = new CanonicAnalyzer();
        word2vec = new Word2vecAnalyzer();
        tsv = new TsvAnalyzer();
        observation = new ObservationAnalyzer();
    }

    /**
     * Adds abbreviation forms whose final dot must remain attached.
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addBrevidots(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadSet(
                brevidots,
                path,
                0,
                LexiconHelper.CsvHeader.SKIP
            );
        }
    }

    /**
     * Adds one MWE whose declared form is also its canonical output.
     *
     * <p>The expression is compiled with the normalization resources
     * present when this method is called.</p>
     *
     * @param expression expression to analyze and register
     * @return {@code true} if the analyzed expression contains at least two tokens
     * @throws IOException if analysis fails
     */
    public boolean addExpression(final CharSequence expression) throws IOException
    {
        return addExpression(expression, expression);
    }

    /**
     * Adds one MWE with an explicit canonical output.
     *
     * <p>The expression is compiled with the normalization resources
     * present when this method is called.</p>
     *
     * @param expression expression to analyze and register
     * @param canonical canonical form emitted when the expression is matched
     * @return {@code true} if the analyzed expression contains at least two tokens
     * @throws IOException if analysis fails
     */
    public boolean addExpression(
        final CharSequence expression,
        final CharSequence canonical)
    {
        return mweCompiler.addExpression(expression, "", canonical);
    }

    /**
     * Adds MWE files and freezes the expression lexicon.
     *
     * <p>Each entry is compiled with the current normalization resources.
     * Verbal declarations expand only their initial verbal head. Explicit
     * declarations take precedence over generated variants across all files.
     * Call once, after any individual expression additions and before analysis.</p>
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addExpressions(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            mweCompiler.loadExpressions(path);
        }
        expressions.freeze();
    }

    /**
     * Adds gram words.
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addGramwords(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadSet(gramwords, path);
        }
    }

    /**
     * Adds noise tokens.
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addNoisetokens(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadSet(noisetokens, path);
        }
    }

    /**
     * Adds normalization mappings.
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addNormalizations(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadMap(
                normalizer,
                path,
                LexiconHelper.OnDuplicate.REPLACE
            );
        }
    }

    /**
     * Adds stop words.
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addStopwords(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadSet(stopwords, path);
        }
    }

    /**
     * Adds uppercase words protected by the uppercase filter.
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addUcwords(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadSet(ucwords, path);
        }
    }
    
    /**
     * Returns the current stopwords for this analyzer.
     * @return
     */
    public CharArraySet stopwords()
    {
        return stopwords;
    }

    /**
     * Selects the analyzer used for a field.
     *
     * @param fieldName field name
     * @return analyzer assigned to the field
     */
    @Override
    protected Analyzer getWrappedAnalyzer(final String fieldName)
    {
        if (fieldName.equals("tsv")) {
            return tsv;
        }
        if (fieldName.startsWith("obs")) {
            return observation;
        }
        if (fieldName.startsWith("word2vec")) {
            return word2vec;
        }
        return canonic;
    }

    /**
     * Builds the canonical filter chain for running text.
     *
     * @param stream upstream token stream
     * @return final canonical token stream
     */
    private TokenStream canonicChain(final TokenStream stream)
    {
        stream.addAttribute(BoundaryAttribute.class);
        TokenStream ts = lexicalChain(new MarkupBoundaryFilter(stream));
        ts = new NumberFilter(ts, numbers);
        ts = new AgeFilter(ts);
        ts = new UppercaseFilter(ts, ucwords, 4);
        ts = new MweFilter(ts, expressions);
        ts = new PosTaggingFilter(ts, posTagger);
        ts = new LemmaFilter(ts, lemmaLexicon, propn);
        return ts;
    }

    /**
     * Applies the shared lexical segmentation and normalization rules.
     * Numerical recognition and case cleanup are specific to running text.
     *
     * @param stream upstream token stream
     * @return lexically normalized tokens
     */
    private TokenStream lexicalChain(final TokenStream stream)
    {
        TokenStream ts = stream;
        ts.addAttribute(BoundaryAttribute.class);
        ts = new FrenchCliticSplitFilter(ts);
        ts = new ReplaceFilter(ts, normalizer);
        return ts;
    }

    /**
     * Analyzer for vertical lemmatisation, lemmatize, but keep function words.
     */
    public class TsvAnalyzer extends Analyzer
    {
        public TsvAnalyzer()
        {
            super();
        }

        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots, markupMode);
            TokenStream ts = tokenizer;
            ts = new MarkupZoneFilter(
                ts,
                "teiHeader | head | note | listBibl | bibl | table",
                MarkupZoneFilter.Mode.EXCLUDE
            );
            ts = canonicChain(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }
    }
    
    /**
     * Analyzer for word2vec, lemmatize, but keep function words
     * (useful to separate words by pos)
     */
    public class Word2vecAnalyzer extends Analyzer
    {
        public Word2vecAnalyzer()
        {
            super();
        }

        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots, markupMode);
            TokenStream ts = tokenizer;
            ts = new MarkupZoneFilter(
                ts,
                "teiHeader | head | note | listBibl | bibl | table",
                MarkupZoneFilter.Mode.EXCLUDE
            );
            ts = canonicChain(ts);
            ts = new CleanupFilter(ts, noisetokens, null); // keep function words
            return new TokenStreamComponents(tokenizer, ts);
        }
    }

    /**
     * Analyzer for canonical French indexing fields.
     */
    public class CanonicAnalyzer extends Analyzer
    {
        /**
         * Builds a canonical analyzer.
         */
        public CanonicAnalyzer()
        {
            super();
        }

        /**
         * Creates the canonical analysis chain.
         *
         * @param fieldName field name
         * @return reusable token-stream components
         */
        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots, markupMode);
            TokenStream ts = canonicChain(tokenizer);
            ts = new CleanupFilter(ts, noisetokens, gramwords); // remove noise and function words
            return new TokenStreamComponents(tokenizer, ts);
        }
    }

    /**
     * Analyzer used to compile plain-text MWE declarations and verbal forms.
     *
     * <p>Only lexical segmentation and normalization are applied. Numerical
     * aggregation, uppercase cleanup, POS tagging and lemmatization are omitted.
     * Unconditional lemma aliases would introduce false matches for fixed
     * expressions, such as de faire for de fait.
     * Verbal inflections are supplied separately by the MWE compiler.</p>
     */
    private final class MweEntryAnalyzer extends Analyzer
    {
        /**
         * Creates the MWE-entry compilation chain.
         *
         * @param fieldName synthetic field name supplied by the loader
         * @return reusable token-stream components
         */
        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots, MarkupMode.NONE);
            return new TokenStreamComponents(tokenizer, lexicalChain(tokenizer));
        }
    }


    /**
     * Analyzer restricted to TEI observation zones.
     */
    public class ObservationAnalyzer extends Analyzer
    {
        /**
         * Builds an observation analyzer.
         */
        public ObservationAnalyzer()
        {
            super();
        }

        /**
         * Creates the observation-zone analysis chain.
         *
         * @param fieldName field name
         * @return reusable token-stream components
         */
        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots, markupMode);
            TokenStream ts = tokenizer;
            ts = new MarkupZoneFilter(
                ts,
                "@data-tei-type=\"observation\"",
                MarkupZoneFilter.Mode.INCLUDE
            );
            ts = canonicChain(ts);
            return new TokenStreamComponents(tokenizer, ts);
        }
    }
}
