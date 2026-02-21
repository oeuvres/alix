package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;

import com.github.oeuvres.alix.lucene.analysis.LemmaLexicon;
import com.github.oeuvres.alix.lucene.analysis.Lexicons;
import com.github.oeuvres.alix.util.Cache;

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
        Lexicons.loadSet(map, Lexicons.class, "/com/github/oeuvres/alix/fr/brevidot.csv", 0, ".");
        for (String file : localFiles) {
            Lexicons.loadSet(map, Path.of(file), 0, ".");
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
        Lexicons.loadMap(map, Lexicons.class, "/com/github/oeuvres/alix/fr/norm.csv", false);
        for (String file : localFiles) {
            Lexicons.loadMap(map, Path.of(file), true);
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
        
        return lex;
    }

}
