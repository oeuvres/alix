package com.github.oeuvres.alix.lucene.analysis.util;

import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.CsvHeader.KEEP;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.CsvHeader.SKIP;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.OnDuplicate.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.CharsMap;
import com.github.oeuvres.alix.util.Report;

/** Tests CSV-reader-based lexical loading and shared row filtering. */
public class LexiconHelperTest
{
    /**
     * Checks selected columns, header handling, blank keys, comments, short rows
     * and caller-configured separators in both map implementations.
     * @throws IOException if a reader cannot be closed
     */
    @Test
    public void maps() throws IOException
    {
        final String data = "id;form;norm;extra\n"
            + "1;chevaux;cheval;x\n"
            + "2;;ignored;x\n"
            + "3;  ;ignored;x\n"
            + "#comment;ignored;ignored;x\n"
            + "4;short\n"
            + "5;chevaux;replacement;x\n"
            + "6;empty-value;;x\n";
        final CharsMap chars = new CharsMap(16, false);
        final CharArrayMap<char[]> lucene = new CharArrayMap<>(16, false);
        try (CSVReader csv = new CSVReader(new StringReader(data)).separator(';')) {
            LexiconHelper.loadMap(chars, csv, REPLACE, SKIP, 1, 2, null);
        }
        try (CSVReader csv = new CSVReader(new StringReader(data)).separator(';')) {
            LexiconHelper.loadMap(lucene, csv, IGNORE, SKIP, 1, 2, null);
        }
        assertEquals("replacement", chars.get("chevaux"));
        assertArrayEquals("cheval".toCharArray(), lucene.get("chevaux"));
        for (String skipped : new String[] { "", "  ", "ignored", "short", "form" }) {
            assertTrue(chars.valueOrd(skipped) < 0, skipped);
            assertFalse(lucene.containsKey(skipped), skipped);
        }
        assertTrue(chars.valueOrd("empty-value") >= 0);
        assertNull(chars.get("cheval"));
        assertArrayEquals(new char[0], lucene.get("empty-value"));
    }

    /**
     * Checks duplicate diagnostics and the error policy.
     * @throws IOException if a reader cannot be closed
     */
    @Test
    public void reports() throws IOException
    {
        final CharsMap map = new CharsMap(16, false);
        final Report.ReportList report = new Report.ReportList();
        final String data = "a,b\na,c\nshort\n";
        try (CSVReader csv = new CSVReader(new StringReader(data))) {
            LexiconHelper.loadMap(map, csv, REPORT, KEEP, 0, 1, report);
        }
        assertEquals("b", map.get("a"));
        assertEquals(2, report.entries().size());
        try (CSVReader csv = new CSVReader(new StringReader("a,d\n"))) {
            assertThrows(RuntimeException.class,
                () -> LexiconHelper.loadMap(map, csv, ERROR, KEEP, 0, 1, null));
        }
    }

    /**
     * Checks headerless sets and verifies that the loader does not close its reader.
     * @throws IOException if reading or closing fails
     */
    @Test
    public void sets() throws IOException
    {
        final CharArraySet set = new CharArraySet(16, true);
        final StringReader input = new StringReader("1;Alpha\n2; \n#note;Ignored\n3;Beta\n");
        try (CSVReader csv = new CSVReader(input).separator(';')) {
            LexiconHelper.loadSet(set, csv, 1, KEEP);
            assertFalse(csv.readRow());
            assertTrue(input.ready());
        }
        assertThrows(IOException.class, input::ready);
        assertEquals(2, set.size());
        assertTrue(set.contains("alpha"));
        assertTrue(set.contains("BETA"));
    }
}
