package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.lucene.analysis.LemmaLexicon;
import com.github.oeuvres.alix.lucene.analysis.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.LexiconHelper.PosResolver;
import com.github.oeuvres.alix.util.Cache;
import com.github.oeuvres.alix.util.Chain;

public class FrenchLexicons
{
    private FrenchLexicons()
    {
    }
    
    public static CharArraySet getDotEndingWords(String... localFiles)
    {
        CharArraySet m = (CharArraySet) Cache.get(FrenchLexicons.class, "brevidot",
         p -> {
            try {
                return dotEndingWords(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, localFiles);
        return m;
    }

    private static CharArraySet dotEndingWords(List<String> localFiles) throws IOException
    {
        // set ignore case
        CharArraySet map = new CharArraySet(100, true);
        LexiconHelper.loadSet(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/brevidot.csv", 0, ".");
        for (String file : localFiles) {
            LexiconHelper.loadSet(map, Path.of(file), 0, ".");
        }
        return map;
    }

    static CharArrayMap<char[]> getTermMapping(String... localFiles)
    {
        @SuppressWarnings("unchecked") // due to CharArrayMap.class being raw (type erasure)
        CharArrayMap<char[]> m = (CharArrayMap<char[]>) Cache.get(FrenchLexicons.class, "norm", p -> {
            try {
                return termMapping(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, localFiles);
        return m;
    }

    private static CharArrayMap<char[]> termMapping(List<String> localFiles) throws IOException
    {
        CharArrayMap<char[]> map = new CharArrayMap<char[]>(2000, false);
        LexiconHelper.loadMap(map, LexiconHelper.class, "/com/github/oeuvres/alix/fr/norm.csv", false);
        for (String file : localFiles) {
            LexiconHelper.loadMap(map, Path.of(file), true);
        }
        return map;
    }
    
    static LemmaLexicon getLemmaLexicon(String... localFiles)
    {
        LemmaLexicon m = (LemmaLexicon) Cache.get(FrenchLexicons.class, "words", p -> {
            try {
                return lemmaLexicon(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, localFiles);
        return m;
    }

    private static LemmaLexicon lemmaLexicon(List<String> localFiles) throws IOException
    {
        LemmaLexicon lex = new LemmaLexicon(500_000);
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
        LexiconHelper.loadLemma(
            lex,
            LemmaLexicon.OnDuplicate.IGNORE,
            FrenchLexicons.class,
            "/com/github/oeuvres/alix/fr/word.csv",
            ',',
            true,
            0,
            1,
            2,
            posResolver
        );
        return lex;
    }

}
