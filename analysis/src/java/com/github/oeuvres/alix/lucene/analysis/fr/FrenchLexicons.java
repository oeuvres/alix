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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.NumberFilter;
import com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;

import opennlp.tools.postag.MutableTagDictionary;
import opennlp.tools.postag.POSDictionary;

/**
 * Factory for the default French lexical resources.
 */
public final class FrenchLexicons
{
    /**
     * Utility class; no instance.
     */
    private FrenchLexicons()
    {
    }

    /**
     * Opens a CSV resource from the default French dictionary directory.
     * @param filename resource filename
     * @return unread CSV reader owned by the caller
     * @throws IOException if the resource cannot be opened
     */
    private static CSVReader csv(final String filename) throws IOException
    {
        return new CSVReader(FrenchLexicons.class, "/com/github/oeuvres/alix/fr/" + filename);
    }

    /**
     * Builds a set from the first columns of headered French resources.
     * @param capacity expected set size
     * @param ignoreCase whether matching ignores case
     * @param filenames resources in loading order
     * @return populated set
     * @throws UncheckedIOException if a resource cannot be read
     */
    private static CharArraySet buildSet(final int capacity, final boolean ignoreCase,
        final String... filenames)
    {
        final CharArraySet set = new CharArraySet(capacity, ignoreCase);
        for (String filename : filenames) {
            try (CSVReader csv = csv(filename)) {
                LexiconHelper.loadSet(set, csv, 0, SKIP);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return set;
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
    public static CharArraySet buildBrevidots()
    {
        return buildSet(300, true, "brevidots.csv");
    }

    /**
     * Builds the default stop-word set.
     *
     * @return case-insensitive stop-word set
     */
    public static CharArraySet buildGramwords()
    {
        return buildSet(1200, true, "gramwords.csv");
    }

    /**
     * Builds the default French lemma lexicon.
     *
     * @return populated lemma lexicon
     */
    public static LemmaLexicon buildLemmaLexicon()
    {
        final LemmaLexicon lexicon = new LemmaLexicon(500_000);
        lexicon.onDuplicate(LemmaLexicon.OnDuplicate.IGNORE);
        try (CSVReader csv = csv("word.csv")) {
            LexiconHelper.loadLemma(lexicon, csv, SKIP, 0, 1, 2, FrenchLexicons::wordPos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lexicon;
    }

    /** Lazy initialization for the immutable numerical vocabulary. */
    private static final class NumberResources
    {
        private static final NumberFilter.Lexicon LEXICON = loadNumbers();
    }

    /**
     * Loads the two-column numeric resource using the existing CSV loader.
     * @return compiled numerical lexicon
     * @throws UncheckedIOException if the resource cannot be read
     */
    private static NumberFilter.Lexicon loadNumbers()
    {
        final CharArrayMap<char[]> map = new CharArrayMap<>(5000, true);
        try (CSVReader csv = csv("numbers-fr.csv")) {
            LexiconHelper.loadMap(map, csv, ERROR,
                SKIP, 0, 1, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new NumberFilter.Lexicon(map);
    }

    /**
     * Builds the default tokens list that are not words, even function words.
     *
     * @return case-insensitive noise tokens set
     */
    public static CharArraySet buildNoisetokens()
    {
        return buildSet(1500, true, "noisetokens.csv");
    }

    /**
     * Builds the default French normalization map.
     *
     * @return populated normalization map
     */
    public static CharsMap buildNormalizer()
    {
        final CharsMap map = new CharsMap(2000, false);
        for (String filename : new String[] {
                "norm-variants.csv", "norm-aeoe.csv", "norm-maj-noacc.csv",
                "norm-forenames.csv", "norm-names.csv", "norm-misc.csv" }) {
            try (CSVReader csv = csv(filename)) {
                LexiconHelper.loadMap(map, csv, REPLACE, SKIP, 0, 1, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
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
        final String base = "/com/github/oeuvres/alix/fr/";
        try (Directory tmp = new ByteBuffersDirectory();
                InputStream aff = FrenchLexicons.class.getResourceAsStream(base + "fr-alix.aff");
                InputStream dic = FrenchLexicons.class.getResourceAsStream(base + "fr-alix.dic")) {
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
    static VerbalMweCompiler buildMweCompiler(final Analyzer analyzer)
    {
        final VerbalMweCompiler compiler = new VerbalMweCompiler(analyzer, HunspellResources.DICTIONARY);
        for (String filename : new String[] { "mwe-words.csv", "mwe-propn.csv" }) {
            try (CSVReader csv = csv(filename)) {
                compiler.loadExpressions(csv);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return compiler;
    }

    /**
     * Returns the shared French numeric lookup table. Loaded lazily once per class loader.
     * @return compiled immutable numerical vocabulary
     */
    public static NumberFilter.Lexicon buildNumberLexicon()
    {
        return NumberResources.LEXICON;
    }

    /**
     * Builds the default proper-name set.
     *
     * @return case-insensitive proper-name set
     */
    public static CharArraySet buildPropn()
    {
        return buildSet(2000, true, "propn-words.csv");
    }

    /**
     * Builds the default stop-word set.
     *
     * @return case-insensitive stop-word set
     */
    public static CharArraySet buildStopwords()
    {
        return buildSet(4000, true, "gramwords.csv", "noisetokens.csv");
    }
    
    /**
     * Builds the default French OpenNLP tag dictionary from word.csv. Only
     * rows carrying a FREQLIVRES value are eligible; the default threshold is
     * zero.
     *
     * @return populated mutable tag dictionary
     * @throws IOException 
     */
    public static MutableTagDictionary buildTagDictionary() throws IOException
    {
        return buildTagDictionary(0.0);
    }

    /**
     * Builds the French OpenNLP tag dictionary from word.csv using a minimum
     * FREQLIVRES evidence threshold. Rows with a blank frequency are ignored.
     *
     * @param freqMin minimum FREQLIVRES value accepted
     * @return populated mutable tag dictionary
     * @throws IOException 
     */
    public static MutableTagDictionary buildTagDictionary(final double freqMin) throws IOException
    {
        // true = case sensitive, false = case insensitive
        final MutableTagDictionary tagDic = new POSDictionary(true);
        try (CSVReader csv = csv("word.csv")) {
            LexiconHelper.loadTags(tagDic, csv, SKIP, 0, 1, 3, freqMin, FrenchLexicons::wordPos, null);
        }
        try (CSVReader csv = csv("mwe-words.csv")) {
            LexiconHelper.loadTags(tagDic, csv, SKIP, 0, 1, -1, -1, FrenchLexicons::wordPos, null);
        }
        try (CSVReader csv = csv("propn-words.csv")) {
            LexiconHelper.loadPROPN(tagDic, csv, null);
        }
        return tagDic;
    }


    /**
     * Builds the default protected-uppercase set.
     *
     * @return case-sensitive uppercase-word set
     */
    public static CharArraySet buildUcwords()
    {
        return buildSet(200, false, "ucwords.csv");
    }
}
