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
import java.io.Reader;
import java.util.ArrayList;

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
 * <h3>Input modes</h3>
 * <ul>
 * <li><b>Reader mode</b>: use a {@link Reader} (classic usage)</li>
 * <li><b>UTF-8 byte mode</b>: use an {@link InputStream} opened on UTF-8 data;
 *     the reader decodes bytes directly into its internal char buffer.
 *     This avoids {@link java.io.InputStreamReader} and includes an ASCII fast path.</li>
 * </ul>
 *
 * <h3>Usage patterns</h3>
 *
 * <p><b>Reader mode</b></p>
 * <pre>{@code
 * Reader r = ...; // e.g. new InputStreamReader(getResourceAsStream(...), StandardCharsets.UTF_8)
 * try (CSVReader csv = new CSVReader(r, ',', 2)) {
 *   while (csv.readRow()) {
 *     CharSequence key = csv.getCell(0);
 *     CharSequence val = csv.getCell(1);
 *     // process
 *   }
 * }
 * }</pre>
 *
 * <p><b>Classpath resource (UTF-8 byte mode)</b></p>
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
    // -------------------------------------------------------------------------
    // Input sources (exactly one is non-null)
    // -------------------------------------------------------------------------

    /**
     * Underlying character source (Reader mode).
     * <p>
     * This is typically an {@link java.io.InputStreamReader}, possibly wrapped
     * around a JAR resource or a file input stream.
     */
    private final Reader in;

    /**
     * Underlying byte source (UTF-8 byte mode).
     * <p>
     * When this is non-null, {@link #in} is null and the reader decodes UTF-8 bytes
     * directly into {@link #buf} via {@link #fillUtf8()}.
     */
    private final InputStream bin;

    /**
     * Byte buffer used in UTF-8 byte mode.
     * <p>
     * Data is read from {@link #bin} into this buffer, then decoded into the char buffer.
     */
    private final byte[] bbuf;

    /**
     * Current read position in {@link #bbuf}.
     */
    private int bPos = 0;

    /**
     * Number of valid bytes currently in {@link #bbuf}.
     */
    private int bLen = 0;

    // -------------------------------------------------------------------------
    // CSV configuration
    // -------------------------------------------------------------------------

    /**
     * Field separator character (e.g. {@code ','} or {@code ';'}).
     */
    private final char separator;

    /**
     * Quote character used to enclose fields (typically {@code '"'}).
     */
    private final char quote;

    /**
     * If positive, limit number of columns to explore (and store).
     * <p>
     * When {@code cellMax > 0}, columns beyond {@code cellMax} are still syntactically
     * consumed but are not appended to {@link StringBuilder}s, reducing allocations
     * and writes when the CSV contains more columns than needed.
     */
    private int cellMax = -1;

    // -------------------------------------------------------------------------
    // Character buffer (used by both modes)
    // -------------------------------------------------------------------------

    /**
     * Internal I/O buffer.
     * <p>
     * In Reader mode, characters are read from {@link #in} into this buffer in bulk.
     * In UTF-8 byte mode, decoded characters are written here by {@link #fillUtf8()}.
     */
    private final char[] buf;

    /**
     * Current position in {@link #buf} (index of next char to consume).
     */
    private int bufPos = 0;

    /**
     * Number of valid characters currently in {@link #buf}.
     */
    private int bufLen = 0;

    /**
     * Flag indicating that end-of-file has been reached and no more data is available.
     */
    private boolean eof = false;

    /**
     * Flag indicating that the next character read is the very first character of the stream.
     * <p>
     * Used to skip a UTF-8 BOM ({@code '\uFEFF'}) if present.
     */
    private boolean atStart = true;

    // -------------------------------------------------------------------------
    // Per-row cell buffers
    // -------------------------------------------------------------------------

    /**
     * Per-row cell buffers.
     * <p>
     * Each element corresponds to a cell in the current row. The list is grown
     * lazily and reused across rows. Only the first {@link #cellCount} elements are
     * valid for the last row read.
     */
    private final ArrayList<StringBuilder> cells;

    /**
     * Number of cells in the last row successfully read by {@link #readRow()}.
     */
    private int cellCount = 0;

    // -------------------------------------------------------------------------
    // Constructors (Reader mode)
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code CSVReader} with default settings:
     * <ul>
     * <li>Separator: {@code ','}</li>
     * <li>Quote: {@code '"'}</li>
     * <li>I/O buffer size: 8192 characters</li>
     * <li>Initial cell capacity: 1 cell per row (grown lazily)</li>
     * <li>Initial capacity per cell: 64 characters</li>
     * </ul>
     *
     * @param in the underlying character stream to read from; must not be {@code null}
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public CSVReader(final Reader in)
    {
        this(in, ',', -1, '"', 8192);
    }

    /**
     * Creates a {@code CSVReader} with default settings:
     * <ul>
     * <li>Quote: {@code '"'}</li>
     * <li>I/O buffer size: 8192 characters</li>
     * <li>Initial cell capacity: 1 cell per row (grown lazily)</li>
     * <li>Initial capacity per cell: 64 characters</li>
     * </ul>
     *
     * @param in        the underlying character stream to read from; must not be {@code null}
     * @param separator field separator character (e.g. {@code ','} or {@code ';'})
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public CSVReader(final Reader in, final char separator)
    {
        this(in, separator, -1, '"', 8192);
    }

    /**
     * Creates a {@code CSVReader} with default settings:
     * <ul>
     * <li>Quote: {@code '"'}</li>
     * <li>I/O buffer size: 64 KiB characters</li>
     * <li>Initial cell capacity: 1 cell per row (grown lazily)</li>
     * <li>Initial capacity per cell: 64 characters</li>
     * </ul>
     *
     * @param in        the underlying character stream to read from; must not be {@code null}
     * @param separator field separator character (e.g. {@code ','} or {@code ';'})
     * @param cellMax   limit number of columns to explore (and store)
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public CSVReader(final Reader in, final char separator, final int cellMax)
    {
        this(in, separator, cellMax, '"', 64 * 1024);
    }

    /**
     * Creates a {@code CSVReader} with fully configurable parameters (Reader mode).
     *
     * @param in         the underlying character stream to read from; must not be {@code null}
     * @param separator  field separator character (e.g. {@code ','} or {@code ';'})
     * @param cellMax    limit number of columns to explore (and store)
     * @param quote      quote character for fields (typically {@code '"'})
     * @param bufferSize internal char buffer size (characters), must be {@code > 0}
     *
     * @throws NullPointerException     if {@code in} is {@code null}
     * @throws IllegalArgumentException if {@code bufferSize <= 0}
     */
    public CSVReader(final Reader in,
                     final char separator,
                     final int cellMax,
                     final char quote,
                     final int bufferSize)
    {
        if (in == null) {
            throw new NullPointerException("Reader is null");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize <= 0");
        }

        this.in = in;
        this.bin = null;
        this.bbuf = null;

        this.separator = separator;
        this.quote = quote;
        this.buf = new char[bufferSize];

        if (cellMax > 0) this.cellMax = cellMax;

        // Minimal initial size; grown lazily.
        final int initialCells = 1;
        this.cells = new ArrayList<>(initialCells);
        for (int i = 0; i < initialCells; i++) {
            cells.add(new StringBuilder(64));
        }
    }

    // -------------------------------------------------------------------------
    // Constructors (UTF-8 byte mode, resource helpers)
    // -------------------------------------------------------------------------

    /**
     * Convenience constructor: open a classpath resource using {@code CSVReader.class}
     * as the anchor and parse it as UTF-8 bytes.
     *
     * <p>For absolute classpath resources, pass a leading '/': {@code "/bench/word.csv"}.</p>
     *
     * @param resourcePath classpath resource path
     * @throws IOException if the resource cannot be found or opened
     */
    public CSVReader(final String resourcePath) throws IOException
    {
        this(CSVReader.class, resourcePath, ',', -1);
    }

    /**
     * Open a classpath resource relative to an anchor class and parse it as UTF-8 bytes.
     * <p>
     * Using an anchor class is usually preferable in libraries: it avoids ambiguity
     * between context class loaders.
     * </p>
     *
     * @param anchor       anchor class used to resolve the resource
     * @param resourcePath classpath resource path (prefer absolute with leading '/')
     * @param separator    field separator character
     * @param cellMax      limit number of columns to explore (and store)
     * @throws IOException if the resource cannot be found or opened
     */
    public CSVReader(final Class<?> anchor,
                     final String resourcePath,
                     final char separator,
                     final int cellMax) throws IOException
    {
        this(openResource(anchor, resourcePath), separator, cellMax, '"', 64 * 1024, 64 * 1024);
    }

    /**
     * Parse UTF-8 bytes from an already opened {@link InputStream}.
     * <p>
     * The stream is owned by this {@code CSVReader} instance and will be closed
     * by {@link #close()}.
     * </p>
     *
     * @param utf8Stream     an {@link InputStream} delivering UTF-8 bytes; must not be {@code null}
     * @param separator      field separator character
     * @param cellMax        limit number of columns to explore (and store)
     * @param quote          quote character for fields (typically {@code '"'})
     * @param charBufferSize internal char buffer size (characters), must be {@code > 0}
     * @param byteBufferSize internal byte buffer size (bytes), must be {@code > 0}
     */
    public CSVReader(final InputStream utf8Stream,
                     final char separator,
                     final int cellMax,
                     final char quote,
                     final int charBufferSize,
                     final int byteBufferSize)
    {
        if (utf8Stream == null) {
            throw new NullPointerException("InputStream is null");
        }
        if (charBufferSize <= 0) {
            throw new IllegalArgumentException("charBufferSize <= 0");
        }
        if (byteBufferSize <= 0) {
            throw new IllegalArgumentException("byteBufferSize <= 0");
        }

        this.in = null;
        this.bin = utf8Stream;
        this.bbuf = new byte[byteBufferSize];

        this.separator = separator;
        this.quote = quote;
        this.buf = new char[charBufferSize];

        if (cellMax > 0) this.cellMax = cellMax;

        // Minimal initial size; grown lazily.
        final int initialCells = 1;
        this.cells = new ArrayList<>(initialCells);
        for (int i = 0; i < initialCells; i++) {
            cells.add(new StringBuilder(64));
        }
    }

    /**
     * Resolve and open a classpath resource as an {@link InputStream}.
     *
     * @param anchor       anchor class used to resolve the resource
     * @param resourcePath classpath resource path
     * @return an open {@link InputStream} for the resource
     * @throws IOException if the resource cannot be found
     */
    private static InputStream openResource(final Class<?> anchor, final String resourcePath) throws IOException
    {
        if (anchor == null) throw new NullPointerException("anchor is null");
        if (resourcePath == null) throw new NullPointerException("resourcePath is null");

        final InputStream is = anchor.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        return is;
    }

    // -------------------------------------------------------------------------
    // Main CSV parsing API
    // -------------------------------------------------------------------------

    /**
     * Reads the next CSV row from the underlying input.
     * <p>
     * This method drives the CSV parsing state machine until it reaches:
     * <ul>
     * <li>A line terminator (CR, LF, or CRLF), or</li>
     * <li>End of stream (EOF).</li>
     * </ul>
     * When the method returns {@code true}, the contents of the row are available
     * via {@link #getCell(int)} and {@link #getCellCount()}. Their values remain
     * valid until the next call to {@code readRow()}.
     *
     * <p>
     * Behaviour at EOF:
     * <ul>
     * <li>If the last row is not terminated by a newline, it is returned as a valid row.</li>
     * <li>If EOF is reached without reading any characters for a new row, the method returns {@code false}
     * and no row is produced.</li>
     * </ul>
     *
     * @return {@code true} if a row was successfully read; {@code false} if EOF was reached
     *         and no further rows are available.
     * @throws IOException if an I/O error occurs while reading from the underlying input
     */
    public boolean readRow() throws IOException
    {
        if (eof) return false;

        // Ensure we have at least one cell buffer
        cellCount = 1;
        ensureCellCapacity(1);
        StringBuilder cell = cells.get(0);
        cell.setLength(0);

        boolean inQuotes = false;
        boolean atCellStart = true;
        boolean sawAny = false;

        // Handle BOM once at stream start (faster than checking per char)
        if (atStart) {
            atStart = false;
            if (bufPos >= bufLen && !fill()) {
                eof = true;
                cellCount = 0;
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

                    // append chunk before quote
                    if (cell != null && bufPos > start) {
                        cell.append(buf, start, bufPos - start);
                    }
                    if (bufPos < bufLen) sawAny = true; // we saw something in this buffer

                    if (bufPos >= bufLen) {
                        // need more data
                        if (!fill()) {
                            eof = true;
                            // treat EOF as end-of-field/row
                            return true;
                        }
                        start = bufPos;
                        continue;
                    }

                    // buf[bufPos] is quote
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
                        if (cell != null) cell.append(quote);
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
                atCellStart = false;
                continue;
            }

            // Scan unquoted run until separator or newline
            int start = bufPos;
            while (bufPos < bufLen) {
                c = buf[bufPos];
                if (c == separator || c == '\n' || c == '\r') break;
                bufPos++;
            }
            if (cell != null && bufPos > start) {
                cell.append(buf, start, bufPos - start);
            }
            if (bufPos > start) sawAny = true;
            atCellStart = false;

            if (bufPos >= bufLen) continue; // refill and continue

            // Consume delimiter
            c = buf[bufPos++];
            sawAny = true;

            if (c == separator) {
                atCellStart = true;

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
                return true;
            }

            // c == '\r': handle CRLF
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
     * Returns the number of cells in the last row successfully read by {@link #readRow()}.
     * <p>
     * Only indices in the range {@code [0, getCellCount() - 1]} are valid for
     * {@link #getCell(int)} and {@link #getCellAsString(int)}.
     *
     * @return the number of cells in the last row; {@code 0} if no row has been read yet
     *         or if EOF was reached without any data for a row
     */
    public int getCellCount()
    {
        return cellCount;
    }

    /**
     * Returns the content of a cell from the last row as a {@link StringBuilder}.
     * <p>
     * The returned object is a {@link StringBuilder} managed and reused by this reader.
     * Its contents are only valid until the next successful call to {@link #readRow()}.
     *
     * @param index the cell index (0-based)
     * @return a {@link StringBuilder} representing the cell content
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #getCellCount()}
     */
    public StringBuilder getCell(final int index)
    {
        if (index < 0 || index >= cellCount) {
            throw new IndexOutOfBoundsException("cell index " + index + " out of bounds (count=" + cellCount + ")");
        }
        return cells.get(index);
    }

    /**
     * Returns the content of a cell from the last row as an immutable {@link String}.
     * <p>
     * Unlike {@link #getCell(int)}, this method creates a new {@link String} and is
     * therefore more expensive in terms of memory allocations. Use it only if you
     * need the cell value beyond the next call to {@link #readRow()}, or if you
     * need an immutable snapshot.
     *
     * @param index the cell index (0-based)
     * @return a {@link String} representing the cell content
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #getCellCount()}
     */
    public String getCellAsString(final int index)
    {
        return getCell(index).toString();
    }

    // -------------------------------------------------------------------------
    // Resource management
    // -------------------------------------------------------------------------

    /**
     * Closes the underlying input (Reader mode or InputStream mode).
     *
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException
    {
        // Exactly one is non-null by construction.
        if (in != null) in.close();
        if (bin != null) bin.close();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Fills the internal I/O buffer from the underlying input.
     * <p>
     * In Reader mode, characters are read from {@link #in} into {@link #buf}.
     * In UTF-8 byte mode, bytes are read from {@link #bin} and decoded into {@link #buf}.
     * This method overwrites {@link #buf} with new data and resets {@link #bufPos}
     * and {@link #bufLen} accordingly.
     *
     * @return {@code true} if at least one character was produced; {@code false} if EOF was reached
     *         and no data was produced
     * @throws IOException if an I/O error occurs while reading from the underlying input
     */
    private boolean fill() throws IOException
    {
        if (in != null) {
            bufLen = in.read(buf, 0, buf.length);
            bufPos = 0;
            return bufLen > 0;
        }
        return fillUtf8();
    }

    /**
     * Decode UTF-8 bytes from {@link #bin} into {@link #buf}.
     *
     * <h4>Why this exists</h4>
     * <p>
     * For startup dictionary loading, a common pipeline is:
     * {@code InputStream -> InputStreamReader(UTF-8) -> char[] -> parse}.
     * This method removes the {@code InputStreamReader} step and decodes directly.
     * </p>
     *
     * <h4>Performance characteristics</h4>
     * <ul>
     * <li>Fast path for ASCII runs (0x00..0x7F) which are common in CSV syntax and many dictionaries.</li>
     * <li>Supports 2/3/4-byte UTF-8 sequences; invalid sequences are replaced with U+FFFD.</li>
     * <li>Handles UTF-8 BOM by decoding it to U+FEFF, which is then skipped once by {@link #readRow()}.</li>
     * </ul>
     *
     * @return {@code true} if at least one character was decoded; {@code false} if EOF was reached
     *         and no characters were produced
     * @throws IOException if an I/O error occurs while reading from {@link #bin}
     */
    private boolean fillUtf8() throws IOException
    {
        int out = 0;
        bufPos = 0;

        while (out < buf.length) {
            if (bPos >= bLen) {
                final int n = bin.read(bbuf, 0, bbuf.length);
                if (n < 0) break;
                bPos = 0;
                bLen = n;
            }

            int b0 = bbuf[bPos] & 0xFF;

            // -----------------------------------------------------------------
            // Fast ASCII run
            // -----------------------------------------------------------------
            if (b0 < 0x80) {
                final int start = bPos;
                int limit = bLen;

                // Do not exceed output capacity
                final int max = start + (buf.length - out);
                if (limit > max) limit = max;

                // Consume contiguous ASCII bytes
                while (bPos < limit) {
                    if ((bbuf[bPos] & 0x80) != 0) break;
                    bPos++;
                }

                final int n = bPos - start;
                for (int i = 0; i < n; i++) {
                    buf[out + i] = (char) (bbuf[start + i] & 0xFF);
                }
                out += n;
                continue;
            }

            // -----------------------------------------------------------------
            // 2-byte sequence: 110xxxxx 10xxxxxx
            // -----------------------------------------------------------------
            if (b0 >= 0xC2 && b0 <= 0xDF) {
                if (!ensureBytes(2)) {
                    buf[out++] = '\uFFFD';
                    bPos = bLen;
                    break;
                }
                final int b1 = bbuf[bPos + 1] & 0xFF;
                if ((b1 & 0xC0) != 0x80) {
                    buf[out++] = '\uFFFD';
                    bPos++; // resync
                    continue;
                }
                buf[out++] = (char) (((b0 & 0x1F) << 6) | (b1 & 0x3F));
                bPos += 2;
                continue;
            }

            // -----------------------------------------------------------------
            // 3-byte sequence: 1110xxxx 10xxxxxx 10xxxxxx
            // Includes overlong and surrogate checks.
            // -----------------------------------------------------------------
            if (b0 >= 0xE0 && b0 <= 0xEF) {
                if (!ensureBytes(3)) {
                    buf[out++] = '\uFFFD';
                    bPos = bLen;
                    break;
                }
                final int b1 = bbuf[bPos + 1] & 0xFF;
                final int b2 = bbuf[bPos + 2] & 0xFF;

                if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80) {
                    buf[out++] = '\uFFFD';
                    bPos++;
                    continue;
                }
                // Overlong check
                if (b0 == 0xE0 && b1 < 0xA0) {
                    buf[out++] = '\uFFFD'; bPos++; continue;
                }
                // Surrogate range check (U+D800..U+DFFF)
                if (b0 == 0xED && b1 > 0x9F) {
                    buf[out++] = '\uFFFD'; bPos++; continue;
                }

                final int cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                buf[out++] = (char) cp;
                bPos += 3;
                continue;
            }

            // -----------------------------------------------------------------
            // 4-byte sequence: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
            // Produces a surrogate pair in UTF-16.
            // -----------------------------------------------------------------
            if (b0 >= 0xF0 && b0 <= 0xF4) {
                // Need room for 2 chars
                if (out >= buf.length - 1) {
                    // Defer: do not consume bytes; next fill will have space.
                    break;
                }
                if (!ensureBytes(4)) {
                    buf[out++] = '\uFFFD';
                    bPos = bLen;
                    break;
                }
                final int b1 = bbuf[bPos + 1] & 0xFF;
                final int b2 = bbuf[bPos + 2] & 0xFF;
                final int b3 = bbuf[bPos + 3] & 0xFF;

                if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                    buf[out++] = '\uFFFD';
                    bPos++;
                    continue;
                }
                // Overlong / range checks
                if (b0 == 0xF0 && b1 < 0x90) {
                    buf[out++] = '\uFFFD'; bPos++; continue;
                }
                if (b0 == 0xF4 && b1 > 0x8F) {
                    buf[out++] = '\uFFFD'; bPos++; continue;
                }

                int cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                cp -= 0x10000;
                buf[out++] = (char) (0xD800 | (cp >>> 10));
                buf[out++] = (char) (0xDC00 | (cp & 0x3FF));
                bPos += 4;
                continue;
            }

            // -----------------------------------------------------------------
            // Illegal leading byte (0x80..0xC1 or 0xF5..0xFF)
            // -----------------------------------------------------------------
            buf[out++] = '\uFFFD';
            bPos++;
        }

        bufLen = out;
        return out > 0;
    }

    /**
     * Ensure at least {@code needed} bytes are available in {@link #bbuf} from {@link #bPos}.
     * <p>
     * If the current remaining byte count is insufficient, this method compacts
     * any remaining bytes to the start of the buffer and reads more from {@link #bin}.
     * </p>
     *
     * @param needed number of bytes needed
     * @return {@code true} if {@code needed} bytes are available; {@code false} on EOF before enough bytes could be read
     * @throws IOException if an I/O error occurs while reading from {@link #bin}
     */
    private boolean ensureBytes(final int needed) throws IOException
    {
        if (bLen - bPos >= needed) return true;

        final int rem = bLen - bPos;
        if (rem > 0) {
            System.arraycopy(bbuf, bPos, bbuf, 0, rem);
        }
        bPos = 0;
        bLen = rem;

        while (bLen < needed) {
            final int n = bin.read(bbuf, bLen, bbuf.length - bLen);
            if (n < 0) return false;
            if (n == 0) continue;
            bLen += n;
        }
        return true;
    }

    /**
     * Ensures that the internal list of cell buffers has at least {@code minCapacity} elements,
     * growing the list and adding new {@link StringBuilder} instances as needed.
     * <p>
     * Newly created {@link StringBuilder} instances are initialised with a small
     * default capacity (64 characters), which is usually sufficient for many CSV files.
     * Adjust as necessary for your data characteristics.
     * </p>
     *
     * @param minCapacity the minimum number of cell buffers required
     */
    private void ensureCellCapacity(final int minCapacity)
    {
        // Grow the list lazily as needed
        while (cells.size() < minCapacity) {
            cells.add(new StringBuilder(64));
        }
    }
}
