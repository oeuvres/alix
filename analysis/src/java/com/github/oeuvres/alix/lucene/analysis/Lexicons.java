package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.util.CSVReader;

public abstract class Lexicons
{
    /** Logger */
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    

    protected Lexicons() {}

    
    static public void loadMap(CharArrayMap<char[]> map, final Class<?> anchor,
            final String resourcePath, boolean replace) throws IOException
    {
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', 2)) {
            loadMap(map, csv, replace);
        }
    }
    
    static public void  loadMap(CharArrayMap<char[]> map, final Path file, final boolean replace) throws IOException
    {
        try (CSVReader csv = new CSVReader(file, ',', 2)) {
            loadMap(map, csv, replace);
        }
    }

    static public void  loadMap(CharArrayMap<char[]> map, final CSVReader csv, final boolean replace) throws IOException
    {
        final int cols = 2;
        // what Exception to send if map is null?
        // pass first line
        if(!csv.readRow()) return;
        while (csv.readRow()) {
            if (csv.getCellCount() < cols)
                continue;
            StringBuilder key = csv.getCell(0);
            if (key.length() < 1) continue;
            if (key.charAt(0) == '#') continue;
            if (!replace && map.containsKey(key)) continue;
            map.put(key, csv.getCellToCharArray(1));
        }
    }
    
    static public void loadSet(CharArraySet set, final Class<?> anchor,
            final String resourcePath, final int col, final String rtrim) throws IOException
    {
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', 2)) {
            loadSet(set, csv, col, rtrim);
        }
    }
    
    
    static public void  loadSet(CharArraySet set, final Path file, final int col, final String rtrim) throws IOException
    {
        try (CSVReader csv = new CSVReader(file, ',', 2)) {
            loadSet(set, csv, col, rtrim);
        }
    }
    
    static public void  loadSet(CharArraySet set, final CSVReader csv, final int col, final String rtrim) throws IOException
    {
        // pass first line
        if(!csv.readRow()) return;
        while (csv.readRow()) {
            if (csv.getCellCount() < col + 1)
                continue;
            StringBuilder word = csv.getCell(col);
            if (word.length() < 1) continue;
            if (word.charAt(0) == '#') continue;
            rtrim(word, rtrim);
            set.add(word);
        }
    }
    
    static public void loadLemma(
        LemmaLexicon lex,
        LemmaLexicon.OnDuplicate policy,
        final Class<?> anchor,
        final String spec,
        char sep,
        int inflectedCol,
        int posCol,
        int lemmaCol
    ) throws IOException
    {
        int maxCol = Math.max(Math.max(inflectedCol, posCol), lemmaCol) +1;
        try (CSVReader csv = new CSVReader(anchor, spec, sep, maxCol)) {
            loadLemma(lex, policy, csv, inflectedCol, posCol, lemmaCol);
        }
    }
    
    static public void loadLemma(
        LemmaLexicon lex,
        LemmaLexicon.OnDuplicate policy,
        CSVReader csv,
        int inflectedCol,
        int posCol,
        int lemmaCol
    ) throws IOException
    {
        int maxCol = Math.max(Math.max(inflectedCol, posCol), lemmaCol) +1;
        // pass first line
        if(!csv.readRow()) return;
        while (csv.readRow()) {
            if (csv.getCellCount() < maxCol)
                continue;
            StringBuilder prefix = csv.getCell(0);
            if (prefix.length() < 1) continue;
            if (prefix.charAt(0) == '#') continue;
            // specific to this loader
            
            String posName = csv.getCellAsString(posCol);
            int posId = Upos.code(posName);
            // Here I would like a logger System to show the unknow tags
            lex.putEntry(csv.getCell(inflectedCol), posId, csv.getCell(lemmaCol), policy);
        }

    }

    
    public static void rtrim(StringBuilder sb, String stripChars) {
        if (stripChars == null || stripChars.length() < 1) return;
        int len = sb.length();
        while (len > 0) {
            char c = sb.charAt(len - 1);
            if (stripChars.indexOf(c) < 0) break;
            len--;
        }
        sb.setLength(len);
    }

}
