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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;

/**
 * A lightweight CSV reader optimised for dictionary-like resources.
 *
 * <h3>Supported features</h3>
 * <ul>
 * <li>Configurable separator (e.g. {@code ','} or {@code ';'})</li>
 * <li>CR ({@code '\r'}), LF ({@code '\n'}), and CRLF line endings</li>
 * <li>Quoted fields</li>
 * <li>Escaped quotes inside quoted fields ({@code ""} → {@code "})</li>
 * <li>Optional UTF-8 BOM ({@code '\uFEFF'}) at start of stream</li>
 * <li>Optional column limit via {@code cellMax} (skip storing extra columns)</li>
 * </ul>
 *
 * <h3>Input</h3>
 * <p>
 * Characters are read from a {@link Reader}; byte decoding is delegated to the JDK.
 * For UTF-8 sources wrap the stream in
 * {@code new InputStreamReader(stream, StandardCharsets.UTF_8)} — which the
 * {@link Path}, classpath-resource and {@link InputStream} constructors do for you.
 * The reader does its own bulk buffering, so there is no need to add a
 * {@link java.io.BufferedReader} on top.
 * </p>
 *
 * <h3>Usage patterns</h3>
 *
 * <p><b>Reader</b></p>
 * <pre>{@code
 * Reader r = new InputStreamReader(getResourceAsStream(...), StandardCharsets.UTF_8);
 * try (CSVReader csv = new CSVReader(r, ',', 2)) {
 *   while (csv.readRow()) {
 *     CharSequence key = csv.getCell(0);
 *     CharSequence val = csv.getCell(1);
 *     // process
 *   }
 * }
 * }</pre>
 *
 * <p><b>Classpath resource</b></p>
 * <pre>{@code
 * // absolute resource path recommended: "/bench/word.csv"
 * try (CSVReader csv = new CSVReader(MyClass.class, "/bench/word.csv", ',', 2)) {
 *   while (csv.readRow()) { ... }
 * }
 * }</pre>
 *
 * <h3>Lifetime of returned cell values</h3>
 * <p>
 * The {@link CharSequence} objects returned by {@link #getCell(int)} are backed
 * by {@link StringBuilder} instances that are owned and reused by this reader.
 * Their contents are only valid until the next successful call to
 * {@link #readRow()}. If you need to keep a cell value beyond that, call
 * {@link #getCellAsString(int)} (allocates a new {@link String}), or rely on a
 * destination structure that copies the key chars (e.g. Lucene {@code CharArrayMap} copies keys).
 * </p>
 */
public final class CSVReader implements Closeable
{
    /** Default size of the character I/O buffer. */
    private static final int DEFAULT_CHAR_BUFFER_SIZE = 64 * 1024;

    /** Initial capacity of each per-cell {@link StringBuilder}. */
    private static final int CELL_INITIAL_CAPACITY = 64;

    /** Underlying character source; decoding is the {@link Reader}'s responsibility. */
    private final Reader in;

    /** Field separator character (e.g. {@code ','} or {@code ';'}); see {@link #separator(char)}. */
    private char separator = ',';

    /** Quote character used to enclose fields (typically {@code '"'}); see {@link #quote(char)}. */
    private char quote = '"';

    /**
     * If positive, limit number of columns to explore (and store).
     * <p>
     * When {@code cellMax > 0}, columns beyond {@code cellMax} are still syntactically
     * consumed but are not appended to {@link StringBuilder}s, reducing allocations
     * and writes when the CSV contains more columns than needed.
     */
    private int cellMax = -1;

    /** Internal character I/O buffer, filled in bulk from {@link #in}; allocated on first read. */
    private char[] buf;

    /** Size of {@link #buf}; see {@link #bufferSize(int)}. */
    private int bufferSize = DEFAULT_CHAR_BUFFER_SIZE;

    /** Diagnostic sink for recoverable anomalies; silent by default. See {@link #report(Report)}. */
    private Report report = Report.ReportNull.INSTANCE;

    /** Current position in {@link #buf} (index of next char to consume). */
    private int bufPos = 0;

    /** Number of valid characters currently in {@link #buf}. */
    private int bufLen = 0;

    /** Set once end-of-input has been reached. */
    private boolean eof = false;

    /** True until the first character is consumed; used to skip a leading BOM. */
    private boolean atStart = true;

    /**
     * Per-row cell buffers, grown lazily and reused across rows. Only the first
     * {@link #cellCount} elements are valid for the last row read.
     */
    private ArrayList<StringBuilder> cells;

    /**
     * Number of cells in the last row successfully read by {@link #readRow()}.
     * A blank line (no characters between terminators) is a valid row with {@code 0} cells.
     */
    private int cellCount = 0;

    /** Identifying name of the source (path or resource), {@code null} for a raw stream. */
    private String spec;

    /** Number of rows returned so far, for logging. */
    private int rowNo;

    /** 1-based physical line where the current row begins; see {@link #getLineNo()}. */
    private int lineNo = 0;

    /** Running count of physical line breaks consumed so far, used to compute {@link #lineNo}. */
    private int lineBreaks = 0;

    /** Set once the first {@link #readRow()} runs; parsing options are frozen afterwards. */
    private boolean started = false;

    /**
     * Reads UTF-8 bytes from a file, decoding through {@link InputStreamReader}.
     * Parsing options are set with the fluent methods, e.g.
     * {@code new CSVReader(path).separator(';').cellMax(2)}.
     *
     * @param path file to read; must not be {@code null}
     * @throws IOException if the file cannot be opened
     */
    public CSVReader(final Path path) throws IOException
    {
        this(openReader(path));
        this.spec = path.toString();
    }

    /**
     * Reads UTF-8 bytes from an {@link InputStream}, decoding through {@link InputStreamReader}.
     * The stream is owned by this reader and closed by {@link #close()}.
     *
     * @param utf8Stream stream delivering UTF-8 bytes; must not be {@code null}
     * @throws NullPointerException if {@code utf8Stream} is {@code null}
     */
    public CSVReader(final InputStream utf8Stream)
    {
        this(new InputStreamReader(Objects.requireNonNull(utf8Stream, "utf8Stream is null"), StandardCharsets.UTF_8));
    }

    /**
     * Opens a classpath resource (UTF-8) relative to an anchor class.
     * <p>
     * Using an anchor class is usually preferable in libraries: it avoids ambiguity
     * between context class loaders.
     *
     * @param anchor       anchor class used to resolve the resource
     * @param resourcePath classpath resource path (prefer absolute with leading '/')
     * @throws IOException if the resource cannot be found or opened
     */
    public CSVReader(final Class<?> anchor, final String resourcePath) throws IOException
    {
        this(new InputStreamReader(IOUtil.openResource(anchor, resourcePath), StandardCharsets.UTF_8));
        this.spec = resourcePath;
    }

    /**
     * Creates a reader over a character stream. This is the constructor every other one
     * delegates to; decoding is the {@link Reader}'s responsibility. Parsing options are
     * set afterwards with the fluent configuration methods.
     *
     * @param in the underlying character stream; must not be {@code null}
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public CSVReader(final Reader in)
    {
        this.in = Objects.requireNonNull(in, "Reader is null");
        this.spec = null;
    }

    /**
     * Sets the internal character buffer size, in characters. Call before the first
     * {@link #readRow()}.
     *
     * @param size buffer size, must be {@code > 0}
     * @return this reader, for chaining
     * @throws IllegalArgumentException if {@code size <= 0}
     * @throws IllegalStateException    if reading has already started
     */
    public CSVReader bufferSize(final int size)
    {
        ensureNotStarted();
        if (size <= 0) {
            throw new IllegalArgumentException("bufferSize <= 0");
        }
        this.bufferSize = size;
        return this;
    }

    /**
     * Limits the number of columns explored (and stored). Columns beyond the limit are
     * still consumed syntactically but not retained. Call before the first {@link #readRow()}.
     *
     * @param max maximum number of columns, or {@code <= 0} for no limit
     * @return this reader, for chaining
     * @throws IllegalStateException if reading has already started
     */
    public CSVReader cellMax(final int max)
    {
        ensureNotStarted();
        this.cellMax = (max > 0) ? max : -1;
        return this;
    }

    /**
     * Closes the underlying character stream (and the byte stream it wraps).
     *
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException
    {
        in.close();
    }

    /**
     * Returns the content of a cell from the last row as a reusable {@link StringBuilder}.
     * <p>
     * The returned object is owned and reused by this reader; its contents are only
     * valid until the next successful call to {@link #readRow()}.
     *
     * @param index the cell index (0-based)
     * @return the cell content
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #getCellCount()}
     */
    public StringBuilder getCell(final int index)
    {
        if (index < 0 || index >= cellCount) {
            throw new IndexOutOfBoundsException("cell index " + index + " out of bounds (count=" + cellCount + ")"
                    + ", line " + lineNo + (spec != null ? " in " + spec : ""));
        }
        return cells.get(index);
    }

    /**
     * Returns the content of a cell from the last row as a new immutable {@link String}.
     * <p>
     * Allocates; use {@link #getCell(int)} when the value is only needed before the
     * next {@link #readRow()}.
     *
     * @param index the cell index (0-based)
     * @return a {@link String} snapshot of the cell content
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #getCellCount()}
     */
    public String getCellAsString(final int index)
    {
        return getCell(index).toString();
    }

    /**
     * Returns the number of cells in the last row successfully read by {@link #readRow()}.
     * <p>
     * A blank line yields {@code 0}. Note this differs from end of input only by the
     * return value of {@link #readRow()}: a blank line is a row ({@code readRow()} returned
     * {@code true}) that happens to have no cells, whereas at EOF {@code readRow()} returns
     * {@code false}.
     *
     * @return the number of cells; {@code 0} for a blank line, or if no row has been read
     */
    public int getCellCount()
    {
        return cellCount;
    }

    /**
     * Copies a cell from the last row into a freshly allocated {@code char[]} snapshot
     * that survives subsequent {@link #readRow()} calls.
     *
     * @param index the cell index (0-based)
     * @return a newly allocated array whose length equals the cell length
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #getCellCount()}
     */
    public char[] getCellToCharArray(final int index)
    {
        return getCellToCharArray(index, null);
    }

    /**
     * Copies a cell from the last row into a caller-provided buffer, allocating a new
     * array only if {@code dst} is {@code null} or too small.
     * <p>
     * If {@code dst} is larger than the cell, only the first {@code getCell(index).length()}
     * characters are written and the remainder is left unchanged.
     *
     * @param index the cell index (0-based)
     * @param dst   an optional destination buffer to reuse
     * @return the buffer holding the copied characters (either {@code dst} or a new array)
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #getCellCount()}
     */
    public char[] getCellToCharArray(final int index, char[] dst)
    {
        final StringBuilder cell = getCell(index);
        final int len = cell.length();
        if (dst == null || dst.length < len) {
            dst = new char[len];
        }
        cell.getChars(0, len, dst, 0);
        return dst;
    }

    /**
     * Returns the 1-based physical line at which the last row read by {@link #readRow()}
     * begins. Unlike {@link #getRowNo()}, which counts logical CSV records, this counts
     * physical lines in the source and therefore accounts for newlines embedded inside
     * quoted fields.
     * <p>
     * Recognised line breaks are LF ({@code '\n'}), CRLF ({@code "\r\n"}), and a lone CR
     * ({@code '\r'}) acting as a record terminator. LF and CRLF embedded inside a quoted
     * field are counted; a lone CR embedded inside a quoted field (an old-style Mac line
     * ending within a value) is not.
     *
     * @return the 1-based starting line of the last row, or {@code 0} if no row has been read
     */
    public int getLineNo()
    {
        return lineNo;
    }

    /**
     * Returns the number of rows returned so far, for logging.
     *
     * @return the current row count
     */
    public int getRowNo()
    {
        return rowNo;
    }

    /**
     * Returns an identifying name of the source when opened from a {@link Path} or a
     * classpath resource, or {@code null} when opened directly from a {@link Reader}
     * or {@link InputStream}.
     *
     * @return the source name, or {@code null}
     */
    public String getSpec()
    {
        return spec;
    }

    /**
     * Sets the quote character used to enclose fields. Call before the first
     * {@link #readRow()}.
     *
     * @param quote quote character
     * @return this reader, for chaining
     * @throws IllegalStateException if reading has already started
     */
    public CSVReader quote(final char quote)
    {
        ensureNotStarted();
        this.quote = quote;
        return this;
    }

    /**
     * Reads the next CSV row from the underlying input.
     * <p>
     * Drives the parsing state machine until a line terminator (CR, LF, or CRLF) or
     * end of input. When {@code true} is returned, the row is available via
     * {@link #getCell(int)} and {@link #getCellCount()}, valid until the next call.
     * <p>
     * At EOF: an unterminated final row is returned as valid; if EOF is reached with
     * no characters for a new row, {@code false} is returned and no row is produced.
     * <p>
     * A blank line is a valid row carrying no cells: {@code true} is returned and
     * {@link #getCellCount()} is {@code 0}. A trailing line terminator at end of input does
     * not by itself create such a row. To skip blank lines, test {@code getCellCount() == 0}.
     *
     * @return {@code true} if a row was read; {@code false} at end of input
     * @throws IOException if an I/O error occurs while reading
     */
    public boolean readRow() throws IOException
    {
        if (!started) {
            start();
        }
        if (eof) {
            return false;
        }

        cellCount = 1;
        ensureCellCapacity(1);
        StringBuilder cell = cells.get(0);
        cell.setLength(0);

        boolean inQuotes = false;
        boolean atCellStart = true;
        boolean sawAny = false;
        boolean content = false;
        rowNo++;
        lineNo = lineBreaks + 1;

        // Handle BOM once at stream start (faster than checking per char)
        if (atStart) {
            atStart = false;
            if (bufPos >= bufLen && !fill()) {
                eof = true;
                cellCount = 0;
                rowNo--;
                return false;
            }
            if (bufLen > 0 && buf[bufPos] == '\uFEFF') {
                bufPos++;
            }
        }

        for (;;) {
            if (bufPos >= bufLen) {
                if (!fill()) {
                    eof = true;
                    if (!sawAny && atCellStart) {
                        cellCount = 0;
                        rowNo--;
                        return false;
                    }
                    return true; // last unterminated row
                }
            }

            if (inQuotes) {
                // Scan until next quote; append chunks in bulk
                int start = bufPos;
                while (true) {
                    while (bufPos < bufLen && buf[bufPos] != quote) {
                        bufPos++;
                    }

                    // count embedded LF in the consumed chunk, then append it
                    if (bufPos > start) {
                        for (int i = start; i < bufPos; i++) {
                            if (buf[i] == '\n') {
                                lineBreaks++;
                            }
                        }
                        if (cell != null) {
                            cell.append(buf, start, bufPos - start);
                        }
                    }
                    if (bufPos < bufLen) {
                        sawAny = true;
                    }

                    if (bufPos >= bufLen) {
                        // need more data
                        if (!fill()) {
                            eof = true;
                            report.warn("line " + lineNo + (spec != null ? " in " + spec : "")
                                    + ": unterminated quoted field at end of input");
                            return true; // EOF treated as end-of-field/row
                        }
                        start = bufPos;
                        continue;
                    }

                    bufPos++; // consume quote

                    // Lookahead for escaped quote
                    if (bufPos >= bufLen) {
                        if (!fill()) {
                            eof = true;
                            inQuotes = false;
                            return true;
                        }
                    }
                    if (bufPos < bufLen && buf[bufPos] == quote) {
                        // escaped quote
                        if (cell != null) {
                            cell.append(quote);
                        }
                        bufPos++; // consume second quote
                        start = bufPos;
                        continue;
                    }

                    // closing quote
                    inQuotes = false;
                    atCellStart = false;
                    break;
                }
                continue;
            }

            // Outside quotes
            char c = buf[bufPos];

            // Opening quote only if it's the first char of the cell
            if (atCellStart && c == quote) {
                inQuotes = true;
                bufPos++;
                sawAny = true;
                content = true;
                atCellStart = false;
                continue;
            }

            // Scan unquoted run until separator or newline
            int start = bufPos;
            while (bufPos < bufLen) {
                c = buf[bufPos];
                if (c == separator || c == '\n' || c == '\r') {
                    break;
                }
                bufPos++;
            }
            if (cell != null && bufPos > start) {
                cell.append(buf, start, bufPos - start);
            }
            if (bufPos > start) {
                sawAny = true;
                content = true;
            }
            atCellStart = false;

            if (bufPos >= bufLen) {
                continue; // refill and continue
            }

            // Consume delimiter
            c = buf[bufPos++];
            sawAny = true;

            if (c == separator) {
                atCellStart = true;
                content = true;

                // Enforce "return at most cellMax cells"
                if (cellMax > 0 && cellCount >= cellMax) {
                    cell = null;      // discard subsequent cells' content
                    inQuotes = false; // quote handling continues via state machine
                    continue;
                }

                ensureCellCapacity(cellCount + 1);
                cell = cells.get(cellCount);
                cell.setLength(0);
                cellCount++;
                continue;
            }

            if (c == '\n') {
                lineBreaks++;
                if (!content) {
                    cellCount = 0;
                }
                return true;
            }

            // c == '\r': handle CRLF (a lone CR or a CRLF pair is one physical line break)
            lineBreaks++;
            if (!content) {
                cellCount = 0;
            }
            if (bufPos >= bufLen) {
                if (!fill()) {
                    eof = true;
                    return true;
                }
            }
            if (bufPos < bufLen && buf[bufPos] == '\n') {
                bufPos++;
            }
            return true;
        }
    }

    /**
     * Sets the diagnostic sink used to report recoverable anomalies (for instance an
     * unterminated quoted field at end of input). The default is silent. May be set at
     * any time.
     *
     * @param report diagnostic sink; must not be {@code null}
     * @return this reader, for chaining
     */
    public CSVReader report(final Report report)
    {
        this.report = Objects.requireNonNull(report, "report is null");
        return this;
    }

    /**
     * Sets the field separator character. Call before the first {@link #readRow()}.
     *
     * @param separator separator character
     * @return this reader, for chaining
     * @throws IllegalStateException if reading has already started
     */
    public CSVReader separator(final char separator)
    {
        ensureNotStarted();
        this.separator = separator;
        return this;
    }

    /**
     * Ensures the cell-buffer list holds at least {@code minCapacity} reusable
     * {@link StringBuilder} instances.
     *
     * @param minCapacity the minimum number of cell buffers required
     */
    private void ensureCellCapacity(final int minCapacity)
    {
        while (cells.size() < minCapacity) {
            cells.add(new StringBuilder(CELL_INITIAL_CAPACITY));
        }
    }

    /**
     * Guards configuration methods: parsing options must be fixed before reading begins.
     *
     * @throws IllegalStateException if {@link #readRow()} has already been called
     */
    private void ensureNotStarted()
    {
        if (started) {
            throw new IllegalStateException("configuration must be set before the first readRow()");
        }
    }

    /**
     * Allocates the I/O buffer and per-cell builders from the configured options, on the
     * first {@link #readRow()} call.
     */
    private void start()
    {
        started = true;
        this.buf = new char[bufferSize];
        final int initialCells = (cellMax > 0) ? cellMax : 4;
        this.cells = new ArrayList<>(initialCells);
        for (int i = 0; i < initialCells; i++) {
            cells.add(new StringBuilder(CELL_INITIAL_CAPACITY));
        }
    }

    /**
     * Refills {@link #buf} from {@link #in}, tolerating a zero-length read.
     *
     * @return {@code true} if at least one character was read; {@code false} at EOF
     * @throws IOException if an I/O error occurs while reading
     */
    private boolean fill() throws IOException
    {
        int n;
        do {
            n = in.read(buf, 0, buf.length);
        } while (n == 0);
        bufPos = 0;
        if (n < 0) {
            bufLen = 0;
            return false;
        }
        bufLen = n;
        return true;
    }

    /**
     * Opens a file as a UTF-8 character stream owned by this reader.
     *
     * @param path file to open; must not be {@code null}
     * @return a UTF-8 {@link Reader} over the file
     * @throws IOException if the file cannot be opened
     */
    private static Reader openReader(final Path path) throws IOException
    {
        Objects.requireNonNull(path, "path is null");
        return new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8);
    }
}
