package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.oeuvres.alix.util.CSVReader;

public abstract class Lexicons
{
    /** Logger */
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    

    protected Lexicons() {}

    
    static public void fillMap(CharArrayMap<char[]> map, final Class<?> anchor,
            final String resourcePath, boolean replace) throws IOException
    {
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', 2)) {
            fillMap(map, csv, replace);
        }
    }
    
    static public void  fillMap(CharArrayMap<char[]> map, final Path file, final boolean replace) throws IOException
    {
        try (CSVReader csv = new CSVReader(file, ',', 2)) {
            fillMap(map, csv, replace);
        }
    }

    static public void  fillMap(CharArrayMap<char[]> map, final CSVReader csv, final boolean replace) throws IOException
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
    
    static public void fillSet(CharArraySet set, final Class<?> anchor,
            final String resourcePath, final int col, final String rtrim) throws IOException
    {
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', 2)) {
            fillSet(set, csv, col, rtrim);
        }
    }
    
    
    static public void  fillSet(CharArraySet set, final Path file, final int col, final String rtrim) throws IOException
    {
        try (CSVReader csv = new CSVReader(file, ',', 2)) {
            fillSet(set, csv, col, rtrim);
        }
    }
    
    static public void  fillSet(CharArraySet set, final CSVReader csv, final int col, final String rtrim) throws IOException
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
