package com.github.oeuvres.alix.lucene.analysis.util;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.CSVReader.Row;

public class LexiconIO
{
    /**
     * Load a stop list for analysis
     * 
     * @param res resource path according to the class loader.
     */
    synchronized static public void loadList(final String res, final )
    {
        Reader reader = new InputStreamReader(Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(res), StandardCharsets.UTF_8);
        CSVReader csv = null;
        try {
            csv = new CSVReader(reader, 1, ',');
            csv.readRow(); // skip first line
            Row row;
            while ((row = csv.readRow()) != null) {
                // STOP.add(new CharsAttImpl(row.get(0)));
            }
        } catch (Exception e) {
            System.out.println("Dictionary parse error in file " + reader);
            if (csv != null) System.out.println(" line " + csv.line());
            e.printStackTrace();
        }
    }

}
