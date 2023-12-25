/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * A light fast csv parser without Strings, especially to load jar resources.
 * Populate a reusable predefined array of object.
 */
public class CsvReader
{
    private static final int BUFFER_SIZE = 16384; // tested
    private final char[] buf = new char[BUFFER_SIZE];
    private int bufPos;
    private int bufLen;
    private static final char LF = '\n';
    private static final char CR = '\r';
    /** The char source */
    private Reader reader;
    /** The cell delimiter char */
    private char sep;
    /** The text delimiter char */
    // private final char quote;
    /** Row to populate */
    private Row row;
    /** line number */
    private int line = -1;

    public CsvReader(final File file, final int cols) throws FileNotFoundException
    {
        this.reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        row = new Row(cols);
    }

    public CsvReader(Reader reader, final int cols)
    {
        this(reader, cols, (char) 0);
    }

    public CsvReader(Reader reader, final int cols, final char sep)
    {
        this.reader = reader;
        row = new Row(cols);
        // quote = '"';
        this.sep = sep;
    }

    public void close() throws IOException
    {
        reader.close();
    }

    public Row row()
    {
        return this.row;
    }

    public int line()
    {
        return this.line;
    }

    /**
     * Read one row (should stop at each
     * 
     * @return
     * @throws IOException
     */
    public Row readRow() throws IOException
    {
        if (this.bufPos < 0)
            return null;
        Row row = this.row.reset();
        Chain cell = row.next();
        int bufPos = this.bufPos;
        int bufMark = bufPos; // from where to start a copy
        char sep = this.sep; // localize
        boolean sep1 = (sep != 0);
        // char quote = this.quote;
        // boolean inquote;
        char lastChar = 0;
        int crlf = 0; // used to not append CR to a CRLF ending line
        while (true) {
            // fill buffer
            if (bufLen == bufPos) {
                // copy chars before erase them
                if (cell == null)
                    ;
                else if (lastChar == CR)
                    cell.append(buf, bufMark, bufLen - bufMark - 1); // do not append CR to cell
                else
                    cell.append(buf, bufMark, bufLen - bufMark);
                bufLen = reader.read(buf, 0, buf.length);
                bufMark = 0;
                // source is finished
                if (bufLen < 0) {
                    cell = null;
                    bufPos = -1; // say end of file to next call
                    break;
                }
                bufPos = 0;
            }
            final char c = buf[bufPos++];
            // escaping char ? shall we do something ?
            if (lastChar == CR) {
                if (c != LF) { // old mac line
                    bufPos--;
                    break;
                } else if ((bufPos - bufMark) > 1)
                    crlf = 1;
            }
            lastChar = c;
            if (c == LF)
                break;
            if (c == CR)
                continue;
            // exclude case of cell separator
            if (sep1) { // one char declared for cell separator
                if (c != sep)
                    continue;
            } else { // nice sugar on common separators
                if (c != '\t' && c != ',' && c != ';')
                    continue;
            }
            // here we should change of cell
            if (cell != null)
                cell.append(buf, bufMark, bufPos - bufMark - 1);
            bufMark = bufPos;
            cell = row.next();
        }
        // append pending chars to current cell
        if (cell != null)
            cell.append(buf, bufMark, bufPos - bufMark - 1 - crlf);
        this.bufPos = bufPos;
        line++;
        return row;
    }

    public class Row
    {
        /** Predefined number of cells to populate */
        private final Chain[] cells;
        /** Number of columns */
        private final int cols;
        /** Internal pointer in cells */
        int pointer;

        /** constructor */
        public Row(int cols)
        {
            cells = new Chain[cols];
            for (int i = cols - 1; i >= 0; i--) {
                cells[i] = new Chain();
            }
            this.cols = cols;
        }

        /**
         * Reset all cells
         * 
         * @return
         */
        public Row reset()
        {
            this.pointer = 0;
            for (int i = cols - 1; i >= 0; i--) {
                cells[i].reset();
            }
            return this;
        }

        /**
         * Give next cell or null if no more
         */
        public Chain get(int col)
        {
            return cells[col];
        }

        /**
         * Give next cell or null if no more
         */
        public Chain next()
        {
            if (pointer >= cols)
                return null;
            return cells[pointer++];
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            sb.append('|');
            for (int i = 0; i < cols; i++) {
                if (first)
                    first = false;
                else
                    sb.append("|\t|");
                sb.append(cells[i]);
            }
            sb.append('|');
            return sb.toString();
        }

    }

}
