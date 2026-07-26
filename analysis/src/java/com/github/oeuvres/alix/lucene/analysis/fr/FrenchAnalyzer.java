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
import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.CleanupFilter;
import com.github.oeuvres.alix.lucene.analysis.LemmaFilter;
import com.github.oeuvres.alix.lucene.analysis.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.MarkupBoundaryFilter;
import com.github.oeuvres.alix.lucene.analysis.MarkupTokenizer;
import com.github.oeuvres.alix.lucene.analysis.MarkupZoneFilter;
import com.github.oeuvres.alix.lucene.analysis.MweFilter;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.ReplaceFilter;
import com.github.oeuvres.alix.lucene.analysis.UppercaseFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.MweLexicon;

import opennlp.tools.postag.POSModel;

/**
 * French analysis scenario used by Alix.
 *
 * <p>The analyzer owns mutable lexical resources. Expressions loaded through
 * {@link #addExpressions(List)} are compiled with the normalization and lemma
 * resources present at call time. Previously compiled expressions are not
 * rebuilt when those resources are modified later.</p>
 */
public class FrenchAnalyzer extends DelegatingAnalyzerWrapper
{
    /** OpenNLP POS model resource. */
    private static final String POS_PATH =
        "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";

    /** Shared immutable POS model. */
    private static final POSModel POS_MODEL =
        LexiconHelper.loadPosModel(FrenchAnalyzer.class, POS_PATH);

    /** ASCII-folding analyzer. */
    private final Analyzer ascii;

    /** Canonical indexing analyzer. */
    private final Analyzer canonic;

    /** Analyzer used only to compile MWE lexicon entries. */
    private final Analyzer mweEntryAnalyzer;

    /** Observation-zone analyzer. */
    private final Analyzer observation;

    /** Words with ending dots. */
    public final CharArraySet brevidots;

    /** Multi-word expressions. */
    public final MweLexicon expressions;

    /** Lemma dictionary. */
    public final LemmaLexicon lemmaLexicon;

    /** Term normalizer. */
    public final CharsMap normalizer;

    /** Proper names protected from lower-casing lemma lookup. */
    public final CharArraySet propn;

    /** Stop-word set. */
    public final CharArraySet stopwords;

    /** Uppercase words protected by the uppercase filter. */
    public final CharArraySet ucwords;

    /**
     * Builds a French analyzer with the default lexical resources.
     *
     * @throws IOException if an analyzer resource cannot be initialized
     */
    public FrenchAnalyzer() throws IOException
    {
        super(PER_FIELD_REUSE_STRATEGY);

        stopwords = FrenchLexicons.buildStopwords();
        normalizer = FrenchLexicons.buildNormalizer();
        lemmaLexicon = FrenchLexicons.buildLemmaLexicon();
        brevidots = FrenchLexicons.buildBrevidots();
        propn = FrenchLexicons.buildPropn();
        ucwords = FrenchLexicons.buildUcwords();

        mweEntryAnalyzer = new MweEntryAnalyzer();
        expressions = FrenchLexicons.buildMweLexicon(mweEntryAnalyzer);

        canonic = new CanonicAnalyzer();
        ascii = new AsciiAnalyzer();
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
     * Adds and freezes MWE files.
     *
     * <p>Each entry is compiled with the current normalization and lemma
     * resources. The expression's analyzed surface sequence and its
     * lemma-or-surface sequence are both added when they differ.</p>
     *
     * @param files CSV files to load
     * @throws IOException if a file cannot be read
     */
    public void addExpressions(final List<Path> files) throws IOException
    {
        for (Path path : files) {
            LexiconHelper.loadExpressions(expressions, mweEntryAnalyzer, path);
        }
        expressions.freeze();
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
     * Selects the analyzer used for a field.
     *
     * @param fieldName field name
     * @return analyzer assigned to the field
     */
    @Override
    protected Analyzer getWrappedAnalyzer(final String fieldName)
    {
        if (fieldName.startsWith("obs")) {
            return observation;
        }
        if (fieldName.endsWith("_ascii")) {
            return ascii;
        }
        return canonic;
    }

    /**
     * Builds the shared canonical filter chain.
     *
     * @param stream upstream token stream
     * @return final canonical token stream
     */
    private TokenStream canonicChain(final TokenStream stream)
    {
        TokenStream ts = stream;
        ts = new MarkupBoundaryFilter(ts);
        ts = new FrenchCliticSplitFilter(ts);
        ts = new ReplaceFilter(ts, normalizer);
        ts = new UppercaseFilter(ts, ucwords, 4);
        ts = new PosTaggingFilter(
            ts,
            POS_MODEL,
            PosTaggingFilter.HYPHEN_REWRITER
        );
        ts = new LemmaFilter(ts, lemmaLexicon, propn);
        ts = new MweFilter(ts, expressions);
        ts = new CleanupFilter(ts);
        ts = new StopFilter(ts, stopwords);
        return ts;
    }

    /**
     * Analyzer for the ASCII-folded canonical field.
     */
    public class AsciiAnalyzer extends Analyzer
    {
        /**
         * Builds an ASCII analyzer.
         */
        public AsciiAnalyzer()
        {
            super();
        }

        /**
         * Creates the ASCII analysis chain.
         *
         * @param fieldName field name
         * @return reusable token-stream components
         */
        @Override
        protected TokenStreamComponents createComponents(final String fieldName)
        {
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
            TokenStream ts = canonicChain(tokenizer);
            ts = new ASCIIFoldingFilter(ts);
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
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
            return new TokenStreamComponents(tokenizer, canonicChain(tokenizer));
        }
    }

    /**
     * Analyzer used to compile one MWE declaration from a CSV cell.
     *
     * <p>The chain deliberately omits POS tagging, MWE recognition, cleanup,
     * and stop-word removal. {@link LemmaFilter} therefore resolves through the
     * POS-independent lemma mapping when no more specific mapping is available.</p>
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
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
            TokenStream ts = tokenizer;
            ts = new FrenchCliticSplitFilter(ts);
            ts = new ReplaceFilter(ts, normalizer);
            ts = new LemmaFilter(ts, lemmaLexicon, propn);
            return new TokenStreamComponents(tokenizer, ts);
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
            final Tokenizer tokenizer = new MarkupTokenizer(brevidots);
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
