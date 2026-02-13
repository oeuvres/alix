package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

class CSVReaderTest {

    @Test
    void readsSimpleUnquotedRow() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("a,b,c\n"));

        assertTrue(csv.readRow());
        assertEquals(3, csv.getCellCount());
        assertEquals("a", csv.getCellAsString(0));
        assertEquals("b", csv.getCellAsString(1));
        assertEquals("c", csv.getCellAsString(2));

        assertFalse(csv.readRow());
    }

    @Test
    void readsLastRowWithoutTrailingNewline() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("a,b,c"));

        assertTrue(csv.readRow());
        assertEquals(3, csv.getCellCount());
        assertEquals("a", csv.getCellAsString(0));
        assertEquals("b", csv.getCellAsString(1));
        assertEquals("c", csv.getCellAsString(2));

        assertFalse(csv.readRow());
    }

    @Test
    void handlesEmptyCells() throws Exception {
        CSVReader csv = new CSVReader(new StringReader(",,\n"));

        assertTrue(csv.readRow());
        assertEquals(3, csv.getCellCount());
        assertEquals("", csv.getCellAsString(0));
        assertEquals("", csv.getCellAsString(1));
        assertEquals("", csv.getCellAsString(2));
    }

    @Test
    void handlesQuotedSeparatorAndEmbeddedNewline() throws Exception {
        // second cell contains an embedded newline
        CSVReader csv = new CSVReader(new StringReader("\"a,b\",\"c\nd\"\n"));

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("a,b", csv.getCellAsString(0));
        assertEquals("c\nd", csv.getCellAsString(1));
    }

    @Test
    void handlesEscapedQuotesInsideQuotedField() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("\"a\"\"b\",x\n"));

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("a\"b", csv.getCellAsString(0));
        assertEquals("x", csv.getCellAsString(1));
    }

    @Test
    void supportsCRLFAndCRLineEndings() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("a,b\r\nc,d\re,f\n"));

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("a", csv.getCellAsString(0));
        assertEquals("b", csv.getCellAsString(1));

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("c", csv.getCellAsString(0));
        assertEquals("d", csv.getCellAsString(1));

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("e", csv.getCellAsString(0));
        assertEquals("f", csv.getCellAsString(1));

        assertFalse(csv.readRow());
    }

    @Test
    void skipsUtf8BomAtStartOfStream() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("\uFEFFa,b\n"));

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("a", csv.getCellAsString(0));
        assertEquals("b", csv.getCellAsString(1));
    }

    @Test
    void getCellAsStringIsSnapshotNotAffectedByNextReadRow() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("a,b\nc,d\n"));

        assertTrue(csv.readRow());
        String a0 = csv.getCellAsString(0); // snapshot
        assertEquals("a", a0);

        assertTrue(csv.readRow());
        assertEquals("c", csv.getCellAsString(0));

        // Snapshot must remain unchanged
        assertEquals("a", a0);
    }

    @Test
    void getCellThrowsOnInvalidIndex() throws Exception {
        CSVReader csv = new CSVReader(new StringReader("a,b\n"));

        assertTrue(csv.readRow());
        assertThrows(IndexOutOfBoundsException.class, () -> csv.getCell(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> csv.getCell(2));
    }

    @Test
    void cellMaxLimitsReturnedCells() throws Exception {
        // Intended semantics: return only first 2 cells, ignore the rest of the row.
        CSVReader csv = new CSVReader(new StringReader("a,b,c\n"), ',', 2);

        assertTrue(csv.readRow());
        assertEquals(2, csv.getCellCount());
        assertEquals("a", csv.getCellAsString(0));
        assertEquals("b", csv.getCellAsString(1));
    }
}
