package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.analysis.CharArrayMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.oeuvres.alix.util.CSVReader;

public abstract class Lexicons
{
    /** Logger */
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    

    protected Lexicons() {}

    
    static public void fillPairs(CharArrayMap<char[]> map, final Class<?> anchor,
            final String resourcePath, boolean replace) throws IOException
    {
        try (CSVReader csv = new CSVReader(anchor, resourcePath, ',', 2)) {
            fillPairs(map, csv, replace);
        }
    }
    
    static public void  fillPairs(CharArrayMap<char[]> map, final Path file, final boolean replace) throws IOException
    {
        try (CSVReader csv = new CSVReader(file, ',', 2)) {
            fillPairs(map, csv, replace);
        }
    }

    static public void  fillPairs(CharArrayMap<char[]> map, final CSVReader csv, final boolean replace) throws IOException
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

}
