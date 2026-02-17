package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.analysis.CharArrayMap;

import com.github.oeuvres.alix.lucene.analysis.Lexicons;
import com.github.oeuvres.alix.util.Cache;

public class FrLexicons
{
    private FrLexicons()
    {
    }

    static CharArrayMap<char[]> getTermMapping(String... localFiles)
    {
        @SuppressWarnings("unchecked") // due to CharArrayMap.class being raw (type erasure)
        CharArrayMap<char[]> m = (CharArrayMap<char[]>) Cache.get(CharArrayMap.class, FrLexicons.class, p -> {
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
        Lexicons.fillPairs(map, Lexicons.class, "/com/github/oeuvres/alix/fr/norm.csv", false);
        for (String file : localFiles) {
            Lexicons.fillPairs(map, Path.of(file), true);
        }
        return map;
    }
}
