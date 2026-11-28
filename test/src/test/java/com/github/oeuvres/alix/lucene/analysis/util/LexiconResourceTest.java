package com.github.oeuvres.alix.lucene.analysis.util;

import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.CsvHeader.SKIP;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.OnDuplicate.ERROR;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.OnDuplicate.REPLACE;
import static com.github.oeuvres.alix.lucene.analysis.util.LexiconHelper.OnDuplicate.REPORT;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.oeuvres.alix.util.Report;

import opennlp.tools.postag.MutableTagDictionary;
import opennlp.tools.postag.POSDictionary;

/** Tests classpath and external lexical resource loading and located diagnostics. */
public class LexiconResourceTest
{
    /**
     * Checks file order, header handling, case sensitivity, duplicate reporting,
     * malformed rows and fatal duplicate locations in resource builders.
     */
    @Test
    public void classpathResources()
    {
        final Report.ReportList report = new Report.ReportList();
        final LexiconHelper helper = new LexiconHelper(getClass(), "lexicon-fixture", report);
        final CharArrayMap<char[]> map = helper.buildMap(16, false, REPORT, "one.csv", "two.csv");
        assertArrayEquals("one".toCharArray(), map.get("Alpha"));
        assertArrayEquals("metre".toCharArray(), map.get("m"));
        assertArrayEquals("mega".toCharArray(), map.get("M"));
        assertArrayEquals("three".toCharArray(), map.get("Beta"));
        assertFalse(map.containsKey("form"));
        assertFalse(map.containsKey("short"));
        assertEquals(2, report.entries().size());
        assertTrue(report.summary().contains("one.csv:6"));
        assertTrue(report.summary().contains("two.csv:2"));
        final CharArraySet set = helper.buildSet(16, true, "one.csv", "two.csv");
        assertTrue(set.contains("alpha"));
        assertTrue(set.contains("BETA"));
        assertFalse(set.contains("form"));
        final RuntimeException duplicate = assertThrows(RuntimeException.class,
            () -> helper.buildMap(16, false, ERROR, "one.csv", "two.csv"));
        assertTrue(duplicate.getMessage().contains("two.csv:2"));
    }

    /**
     * Checks located external-file diagnostics and the checked I/O contract.
     * @param directory temporary test directory
     * @throws IOException if a test file cannot be created or loaded
     */
    @Test
    public void externalFiles(@TempDir final Path directory) throws IOException
    {
        final Path path = directory.resolve("normalizations.csv");
        Files.writeString(path, "form,lemma\nAlpha,one\nAlpha,two\nshort\n");
        final Report.ReportList report = new Report.ReportList();
        final CharArrayMap<char[]> map = new CharArrayMap<>(16, false);
        LexiconHelper.loadFiles(List.of(path), report,
            (csv, diagnostics) -> LexiconHelper.loadMap(map, csv, REPLACE, SKIP, 0, 1, diagnostics));
        assertArrayEquals("two".toCharArray(), map.get("Alpha"));
        assertEquals(1, report.entries().size());
        assertTrue(report.summary().contains("normalizations.csv:4"));
        final Path missing = directory.resolve("missing.csv");
        final IOException error = assertThrows(IOException.class, () ->
            LexiconHelper.loadFiles(List.of(missing), report, (csv, diagnostics) -> {}));
        assertTrue(error.getMessage().contains(missing.toString()));
    }

    /** Checks that a missing classpath resource produces a located fatal exception. */
    @Test
    public void missingResource()
    {
        final LexiconHelper helper = new LexiconHelper(getClass(), "lexicon-fixture", null);
        final UncheckedIOException error = assertThrows(UncheckedIOException.class,
            () -> helper.buildSet(16, false, "missing.csv"));
        assertTrue(error.getMessage().contains("lexicon-fixture/missing.csv"));
    }

    /**
     * Checks invalid numeric frequencies, unsupported POS summary locations,
     * and propagation of diagnostics into the same report.
     * @param directory temporary test directory
     * @throws IOException if a test file cannot be created or loaded
     */
    @Test
    public void posDiagnostics(@TempDir final Path directory) throws IOException
    {
        final Path path = directory.resolve("tags.csv");
        Files.writeString(path, "form,pos,freq\nalpha,NOUN,1\nbeta,NOUN,NaN\n"
            + "gamma,BOGUS,1\ndelta,BOGUS,2\nepsilon,NOUN,not-a-number\n");
        final MutableTagDictionary tags = new POSDictionary(true);
        final Report.ReportList report = new Report.ReportList();
        LexiconHelper.loadFiles(List.of(path), report, (csv, diagnostics) ->
            LexiconHelper.loadTags(tags, csv, SKIP, 0, 1, 2, 0, null, diagnostics));
        assertArrayEquals(new String[] {"NOUN"}, tags.getTags("alpha"));
        assertNull(tags.getTags("beta"));
        assertNull(tags.getTags("gamma"));
        assertTrue(report.summary().contains("tags.csv:3 invalid frequency=NaN"));
        assertTrue(report.summary().contains("tags.csv:4 unknown POS=BOGUS (2 occurrence(s))"));
        assertTrue(report.summary().contains("tags.csv:6 invalid frequency=not-a-number"));
    }
}
