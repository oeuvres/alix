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

import java.util.Map;

import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.lucene.analysis.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.LexiconHelper.PosResolver;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.LemmaLexicon;
import com.github.oeuvres.alix.util.MweLexicon;
import com.github.oeuvres.alix.util.WordTokenizer;
import com.github.oeuvres.alix.util.fr.FrenchCliticTokenizer;

public class FrenchLexicons
{
    private FrenchLexicons()
    {
    }

    public static CharArraySet buildBrevidots()
    {
        // keep case. Mm. != mm.
        CharArraySet set = new CharArraySet(100, false);
        LexiconHelper.loadSet(set, LexiconHelper.class, "/com/github/oeuvres/alix/fr/brevidots.csv", 0, LexiconHelper.CsvHeader.SKIP, ".");
        return set;
    }

    public static LemmaLexicon buildLemmaLexicon()
    {
        LemmaLexicon lexicon = new LemmaLexicon(500_000);
        final Map<String, String> posList = Map.ofEntries(
            Map.entry("VERB", "VERB"), // 305193
            Map.entry("NOUN", "NOUN"), // 110474
            Map.entry("ADJ", "ADJ"), // 67833
            Map.entry("VERBpartpast", "VERB"), // 29612
            Map.entry("VERBpartpres", "VERB"), // 8207
            Map.entry("ADV", "ADV"), // 2331
            // Map.entry("VERBaux2", "VERB"), // 639
            // Map.entry("VERBexpr", "VERB"), // 270
            Map.entry("NUM", "NUM"), // 254
            Map.entry("INTJ", "INTJ"), // 166
            Map.entry("AUX", "AUX"), // 132
            // Map.entry("VERBmod", "VERB"), // 91
            Map.entry("ADP", "ADP"), // 73
            Map.entry("PRONprs", "PRON"), // 59
            Map.entry("ADVsit", "ADV"), // 32
            // Map.entry("DETposs", "DET"), // 31
            Map.entry("PRONdem", "PRON"), // 21
            Map.entry("ADVasp", "ADV"), // 24
            Map.entry("ADVdeg", "ADV"), // 23
            Map.entry("PRONind", "PRON"), // 22
            Map.entry("DETind", "DET"), // 22
            // Map.entry("ADVconj", "ADV"), // 20
            Map.entry("PRONrel", "PRON"), // 18
            Map.entry("PRONint", "PRON"), // 16
            Map.entry("SCONJ", "SCONJ"), // 16
            Map.entry("DETprs", "DET"), // 15
            Map.entry("DETart", "DET"), // 11
            Map.entry("CCONJ", "CCONJ"), // 10
            Map.entry("DETneg", "DET"), // 15
            Map.entry("DETdem", "DET"), // 10
            Map.entry("ADVneg", "ADV"), // 9
            Map.entry("ADP+DET", "ADP_DET"), // 7
            Map.entry("ADP+PRON", "ADP_PRON"), // 6
            Map.entry("PRONneg", "PRONneg"), // 5
            // Map.entry("DETdem", "DETdem"), // 4
            Map.entry("ADVint", "ADV"), // 4
            Map.entry("PRON", "PRON"), // 2
            Map.entry("DET", "DET"), // 1
            Map.entry("", "")
        );
    
        PosResolver posResolver = new PosResolver() {
            protected String posRewrite(String posName)
            {
                return posList.get(posName);
            }
        };
        // for main dic, ignore on duplicate
        lexicon.onDuplicate(LemmaLexicon.OnDuplicate.IGNORE);
        LexiconHelper.loadLemma(
            lexicon,
            FrenchLexicons.class,
            "/com/github/oeuvres/alix/fr/word.csv",
            ',',
            LexiconHelper.CsvHeader.SKIP,
            0,
            1,
            2,
            posResolver
        );
        return lexicon;
    }
    
    public static MweLexicon buildMweLexicon()
    {
        MweLexicon lexicon = new MweLexicon(2000);
        WordTokenizer tokenizer = new FrenchCliticTokenizer();
        LexiconHelper.loadExpressions(lexicon, tokenizer, LexiconHelper.class, "/com/github/oeuvres/alix/fr/mwe-words.csv");
        LexiconHelper.loadExpressions(lexicon, tokenizer, LexiconHelper.class, "/com/github/oeuvres/alix/fr/mwe-propn.csv");
        return lexicon;
    }


    public static CharsMap buildNormalizer()
    {
        CharsMap map = new CharsMap(2000, false); // KEEP case! problems of caps to normalize
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm-variants.csv", LexiconHelper.OnDuplicate.REPLACE);
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm-aeoe.csv", LexiconHelper.OnDuplicate.REPLACE);
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm-maj-noacc.csv", LexiconHelper.OnDuplicate.REPLACE);
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm-forenames.csv", LexiconHelper.OnDuplicate.REPLACE);
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm-names.csv", LexiconHelper.OnDuplicate.REPLACE);
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm-misc.csv", LexiconHelper.OnDuplicate.REPLACE);
        return map;
    }

    public static CharArraySet buildStopwords()
    {
        // set ignore case
        CharArraySet set = new CharArraySet(1500, true);
        LexiconHelper.loadSet(set, FrenchLexicons.class, "/com/github/oeuvres/alix/fr/stopwords.csv");
        return set;
    }
    
    public static CharArraySet buildUcwords()
    {
        // keep case
        CharArraySet set = new CharArraySet(200, false);
        LexiconHelper.loadSet(set, FrenchLexicons.class, "/com/github/oeuvres/alix/fr/ucwords.csv");
        return set;
    }
    
    public static CharArraySet buildPropn()
    {
        // set ignore case
        CharArraySet set = new CharArraySet(2000, true);
        LexiconHelper.loadSet(set, FrenchLexicons.class, "/com/github/oeuvres/alix/fr/propn-words.csv");
        return set;
    }

}
