/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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

import static com.github.oeuvres.alix.common.Upos.*;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.CsvHeader.SKIP;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.OnDuplicate.ERROR;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.OnDuplicate.REPLACE;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.util.Objects;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.NumberFilter;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.Report;

import opennlp.tools.postag.MutableTagDictionary;
import opennlp.tools.postag.POSDictionary;

/**
 * Factory for the default French lexical resources.
 */
public final class FrenchLexicons
{
    /** Classpath directory of the French lexical resources. */
    private static final String DIRECTORY = "/com/github/oeuvres/alix/fr/";

    /** Resource loader using the caller's diagnostic sink. */
    private final LexiconHelper lexicons;

    /**
     * Creates a factory for French lexical resources.
     * @param report non-null diagnostic sink
     */
    public FrenchLexicons(final Report report)
    {
        lexicons = new LexiconHelper(FrenchLexicons.class, DIRECTORY,
            Objects.requireNonNull(report, "report"));
    }

    /**
     * Resolves a source lexical tag without discarding its specialization.
     * @param posName trimmed source tag
     * @return lexical UPOS, or null for unsupported tags
     */
    static Upos lexicalPos(final String posName)
    {
        return switch (posName) {
            case "ADP+DET" -> ADP_DET;
            case "ADP+PRON" -> ADP_PRON;
            case "ADVconj" -> ADV;
            default -> Upos.get(posName);
        };
    }

    /**
     * Projects lexical tags onto the standard UPOS used by the model.
     * @param posName trimmed source tag
     * @return standard UPOS, or null for unsupported tags
     */
    private static Upos wordPos(final String posName)
    {
        final Upos tag = lexicalPos(posName);
        return tag == null ? null : tag.base();
    }

    /**
     * Builds the default abbreviation set.
     *
     * @return case-insensitive abbreviation set
     */
    public CharArraySet buildBrevidots()
    {
        return lexicons.buildSet(300, true, "brevidots.csv");
    }

    /**
     * Builds the default stop-word set.
     *
     * @return case-insensitive stop-word set
     */
    public CharArraySet buildGramwords()
    {
        return lexicons.buildSet(1200, true, "gramwords.csv");
    }

    /**
     * Builds the default French lemma lexicon.
     *
     * @return populated lemma lexicon
     */
    public LemmaLexicon buildLemmaLexicon()
    {
        final LemmaLexicon lexicon = new LemmaLexicon(500_000);
        lexicon.onDuplicate(LemmaLexicon.OnDuplicate.IGNORE);
        lexicons.loadResources((csv, report) -> LexiconHelper.loadLemma(lexicon, csv, SKIP,
            0, 1, 2, FrenchLexicons::wordPos, report), "word.csv");
        return lexicon;
    }

    /**
     * Builds the default tokens list that are not words, even function words.
     *
     * @return case-insensitive noise tokens set
     */
    public CharArraySet buildNoisetokens()
    {
        return lexicons.buildSet(1500, true, "noisetokens.csv");
    }

    /**
     * Builds the default French normalization map.
     *
     * @return populated normalization map
     */
    public CharsMap buildNormalizer()
    {
        final CharsMap map = new CharsMap(2000, false);
        lexicons.loadResources((csv, report) -> LexiconHelper.loadMap(map, csv, REPLACE,
            SKIP, 0, 1, report), "norm-variants.csv", "norm-aeoe.csv", "norm-maj-noacc.csv",
            "norm-forenames.csv", "norm-names.csv", "norm-misc.csv");
        return map;
    }

    /**
     * Lazily loads the canonical Hunspell dictionary once per class loader.
     * The immutable dictionary is shared; each MWE compiler owns its generator.
     */
    private static final class HunspellResources
    {
        /** Unrestricted French lexical resource, independent of any index. */
        private static final Dictionary DICTIONARY = loadHunspell();
    }

    /**
     * Loads the existing canonical French Hunspell resources.
     * Temporary storage and resource streams are closed after construction.
     *
     * @return immutable Hunspell dictionary
     * @throws UncheckedIOException if a resource cannot be read or parsed
     */
    private static Dictionary loadHunspell()
    {
        try (Directory tmp = new ByteBuffersDirectory();
                InputStream aff = FrenchLexicons.class.getResourceAsStream(DIRECTORY + "fr-alix.aff");
                InputStream dic = FrenchLexicons.class.getResourceAsStream(DIRECTORY + "fr-alix.dic")) {
            if (aff == null || dic == null) {
                throw new IOException("Missing French Hunspell resources: fr-alix.aff or fr-alix.dic");
            }
            return new Dictionary(tmp, "fr-hunspell", aff, dic);
        } catch (ParseException e) {
            throw new UncheckedIOException(new IOException("Cannot parse French Hunspell resources", e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds a compiler populated with the default MWE declarations.
     * Keep this compiler for subsequent corpus-specific additions so that
     * automatically generated variants cannot overwrite explicit entries.
     *
     * @param analyzer normalized surface-form entry analyzer
     * @return compiler with a mutable lexicon
     */
    VerbalMweCompiler buildMweCompiler(final Analyzer analyzer)
    {
        final VerbalMweCompiler compiler = new VerbalMweCompiler(analyzer, HunspellResources.DICTIONARY);
        lexicons.loadResources(compiler::loadExpressions,
            "mwe-words.csv", "mwe-propn.csv");
        return compiler;
    }

    /**
     * Builds the French numeric lookup table with this factory's diagnostics.
     * @return compiled immutable numerical vocabulary
     */
    public NumberFilter.Lexicon buildNumberLexicon()
    {
        return new NumberFilter.Lexicon(
            lexicons.buildMap(5000, true, ERROR, "numbers-fr.csv"));
    }

    /**
     * Builds the default proper-name set.
     *
     * @return case-insensitive proper-name set
     */
    public CharArraySet buildPropn()
    {
        return lexicons.buildSet(2000, true, "propn-words.csv");
    }

    /**
     * Builds the case-sensitive unit dictionary.
     * @return unit names mapped to canonical dimension lemmas
     */
    public CharArrayMap<char[]> buildQuantities()
    {
        return lexicons.buildMap(160, false, ERROR, "quantities-fr.csv");
    }

    /**
     * Builds the localized reference introducer dictionary.
     * @return case-insensitive reference introducers
     */
    public CharArraySet buildReferences()
    {
        return lexicons.buildSet(32, true, "references-fr.csv");
    }

    /**
     * Builds the default stop-word set.
     *
     * @return case-insensitive stop-word set
     */
    public CharArraySet buildStopwords()
    {
        return lexicons.buildSet(4000, true, "gramwords.csv", "noisetokens.csv");
    }
    
    /**
     * Builds the default French OpenNLP tag dictionary from word.csv. Only
     * rows carrying a FREQLIVRES value are eligible; the default threshold is
     * zero.
     *
     * @return populated mutable tag dictionary
     * @throws UncheckedIOException if a resource cannot be read
     */
    public MutableTagDictionary buildTagDictionary()
    {
        return buildTagDictionary(0.0);
    }

    /**
     * Builds the French OpenNLP tag dictionary from word.csv using a minimum
     * FREQLIVRES evidence threshold. Rows with a blank frequency are ignored.
     *
     * @param freqMin minimum FREQLIVRES value accepted
     * @return populated mutable tag dictionary
     * @throws UncheckedIOException if a resource cannot be read
     */
    public MutableTagDictionary buildTagDictionary(final double freqMin)
    {
        // true = case sensitive, false = case insensitive
        final MutableTagDictionary tagDic = new POSDictionary(true);
        lexicons.loadResources((csv, report) -> LexiconHelper.loadTags(tagDic, csv, SKIP,
            0, 1, 3, freqMin, FrenchLexicons::wordPos, report), "word.csv");
        lexicons.loadResources((csv, report) -> LexiconHelper.loadTags(tagDic, csv, SKIP,
            0, 1, -1, -1, FrenchLexicons::wordPos, report), "mwe-words.csv");
        lexicons.loadResources((csv, report) -> LexiconHelper.loadPROPN(tagDic, csv, report),
            "propn-words.csv");
        return tagDic;
    }


    /**
     * Builds the default protected-uppercase set.
     *
     * @return case-sensitive uppercase-word set
     */
    public CharArraySet buildUcwords()
    {
        return lexicons.buildSet(200, false, "ucwords.csv");
    }
}
