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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * A low-allocation CSV reader that parses character data from a {@link Reader}
 * into reusable cell buffers.
 * <p>
 * Design goals:
 * <ul>
 * <li>No external dependencies</li>
 * <li>Minimal garbage creation per row (no {@link String} per cell)</li>
 * <li>Support for large CSV files</li>
 * </ul>
 *
 * <h3>Features</h3>
 * <ul>
 * <li>Reads directly from a {@link Reader} using an internal {@code char[]}
 * buffer (no {@link java.io.BufferedReader} required).</li>
 * <li>Stores each row's cells in an {@link ArrayList} of reusable
 * {@link StringBuilder} instances. The same instances are reused for subsequent
 * rows.</li>
 * <li>Supports:
 * <ul>
 * <li>Configurable separator (default: {@code ','})</li>
 * <li>Configurable quote character (default: {@code '"'})</li>
 * <li>CR ({@code '\r'}), LF ({@code '\n'}), and CRLF line endings</li>
 * <li>Quoted fields</li>
 * <li>Escaped quotes inside quoted fields ({@code ""} → {@code "})</li>
 * <li>Optional UTF-8 BOM ({@code '\uFEFF'}) at start of stream</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h3>Usage pattern</h3>
 * 
 * <pre>{@code
 * Reader r = ...; // e.g. InputStreamReader(getResourceAsStream(...), StandardCharsets.UTF_8)
 * CSVReader csv = new CSVReader(r);
 *
 * while (csv.readRow()) {
 *     int cells = csv.getCellCount();
 *     for (int i = 0; i < cells; i++) {
 *         CharSequence cell = csv.getCell(i); // backed by an internal StringBuilder
 *         // process cell
 *     }
 * }
 * }</pre>
 *
 * <h3>Lifetime of returned cell values</h3>
 * <p>
 * The {@link CharSequence} objects returned by {@link #getCell(int)} are backed
 * by {@link StringBuilder} instances that are owned and reused by this reader.
 * Their contents are only valid until the next successful call to
 * {@link #readRow()}. If you need to keep a cell value beyond that, call
 * {@link #getCellAsString(int)} and store the resulting {@link String}.
 */
public final class CSVReader {

	/**
	 * Underlying character source.
	 * <p>
	 * This is typically an {@link java.io.InputStreamReader}, possibly wrapped
	 * around a JAR resource or a file input stream.
	 */
	private final Reader in;

	/**
	 * Field separator character (e.g. {@code ','} or {@code ';'}).
	 */
	private final char separator;

	/**
	 * Quote character used to enclose fields (typically {@code '"'}).
	 */
	private final char quote;

	/**
	 * Internal I/O buffer for reading from {@link #in}.
	 * <p>
	 * Characters are read from the underlying {@link Reader} into this buffer in
	 * bulk, then consumed one by one by the CSV parsing state machine.
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
	 * Flag indicating that end-of-file has been reached and no more data is
	 * available.
	 */
	private boolean eof = false;

	/**
	 * Flag indicating that the next character read is the very first character of
	 * the stream.
	 * <p>
	 * Used to skip a UTF-8 BOM ({@code '\uFEFF'}) if present.
	 */
	private boolean atStart = true;

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

	/**
	 * If fixed number of cells
	 */
	private int cellMax = -1;

	/**
	 * Creates a {@code CSVReader} with default settings:
	 * <ul>
	 * <li>Separator: {@code ','}</li>
	 * <li>Quote: {@code '"'}</li>
	 * <li>I/O buffer size: 8192 characters</li>
	 * <li>Initial cell capacity: 16 cells per row</li>
	 * <li>Initial capacity per cell: 64 characters</li>
	 * </ul>
	 *
	 * @param in the underlying character stream to read from; must not be
	 *           {@code null}
	 * @throws NullPointerException if {@code in} is {@code null}
	 */
	public CSVReader(Reader in) {
		this(in, ',', -1, '"', 8192);
	}

	/**
	 * Creates a {@code CSVReader} with default settings:
	 * <ul>
	 * <li>Separator: {@code ','}</li>
	 * <li>Quote: {@code '"'}</li>
	 * <li>I/O buffer size: 8192 characters</li>
	 * <li>Initial cell capacity: 16 cells per row</li>
	 * <li>Initial capacity per cell: 64 characters</li>
	 * </ul>
	 *
	 * @param in        the underlying character stream to read from; must not be
	 *                  {@code null}
	 * @param separator field separator character (e.g. {@code ','} or {@code ';'})
	 * @throws NullPointerException if {@code in} is {@code null}
	 */
	public CSVReader(Reader in, char separator) {
		this(in, separator, -1, '"', 8192);
	}

	/**
	 * Creates a {@code CSVReader} with fully configurable parameters.
	 *
	 * @param in              the underlying character stream to read from; must not
	 *                        be {@code null}
	 * @param separator       field separator character (e.g. {@code ','} or
	 *                        {@code ';'})
	 * @param cellMax         limit number of columns to explore
	 * @param quote           quote character for fields (typically {@code '"'})
	 * 
	 * @throws NullPointerException     if {@code in} is {@code null}
	 * @throws IllegalArgumentException if {@code bufferSize <= 0}
	 */
	public CSVReader(final Reader in, final char separator, final int cellMax, final char quote, final int bufferSize) {

		if (in == null) {
			throw new NullPointerException("Reader is null");
		}
		if (bufferSize <= 0) {
			throw new IllegalArgumentException("bufferSize <= 0");
		}
		

		this.in = in;
		this.separator = separator;
		this.quote = quote;
		this.buf = new char[bufferSize];
		if (cellMax > 0) this.cellMax = cellMax;

		final int initialCells = 1;
		this.cells = new ArrayList<>(initialCells);
		for (int i = 0; i < initialCells; i++) {
			cells.add(new StringBuilder());
		}
	}

	/**
	 * Reads the next CSV row from the underlying {@link Reader}.
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
	 * <li>If the last row is not terminated by a newline, it is returned as a valid
	 * row.</li>
	 * <li>If EOF is reached without reading any characters for a new row, the
	 * method returns {@code false} and no row is produced.</li>
	 * </ul>
	 *
	 * @return {@code true} if a row was successfully read; {@code false} if EOF was
	 *         reached and no further rows are available.
	 * @throws IOException if an I/O error occurs while reading from the underlying
	 *                     {@link Reader}
	 */
	public boolean readRow() throws IOException {
		if (eof) {
			return false;
		}

		// Reset row state
		cellCount = 1;
		ensureCellCapacity(1);
		StringBuilder cell = cells.get(0);
		cell.setLength(0);

		boolean inQuotes = false;
		boolean atCellStart = true;
		boolean sawAny = false;

		for (;;) {

			if (bufPos >= bufLen) {
				if (!fill()) {
					// Reached EOF
					eof = true;
					if (!sawAny && atCellStart) {
						// No data at all for a new row
						cellCount = 0;
						return false;
					}
					// Return last (possibly unterminated) row
					return true;
				}
			}

			char c = buf[bufPos++];

			// Handle optional BOM only on first character of stream
			if (atStart) {
				atStart = false;
				if (c == '\uFEFF') {
					// Skip BOM and continue with the next character
					continue;
				}
			}

			sawAny = true;

			if (inQuotes) {
				// Inside a quoted field
				if (c == quote) {
					// Possible closing quote or escaped quote ""
					if (bufPos >= bufLen && !fill()) {
						// Treat as closing quote at EOF
						inQuotes = false;
						continue;
					}
					if (bufPos < bufLen && buf[bufPos] == quote) {
						// Escaped double-quote: "" -> "
						cell.append(quote);
						bufPos++;
					} else {
						// Closing quote
						inQuotes = false;
					}
				} else {
					cell.append(c);
				}
				continue;
			}

			// Outside quotes
			if (c == '\n') {
				// Linefeed: end of row
				return true;
			} else if (c == '\r') {
				// Carriage return: may be CRLF
				if (bufPos >= bufLen && !fill()) {
					// CR at EOF
					eof = true;
					return true;
				}
				if (bufPos < bufLen && buf[bufPos] == '\n') {
					// Consume the LF in CRLF
					bufPos++;
				}
				return true;
			} else if (c == quote && atCellStart) {
				// Opening quote at the start of a cell
				inQuotes = true;
				atCellStart = false;
			} 
			if (cellMax > 0 && cellMax > cellCount) {
				// fixed count of cells, no more cells needed, let loop till endOfLine
				continue;
			}
			else if (c == separator) {
                // End of current cell; start a new cell
                ensureCellCapacity(cellCount + 1);
                cell = cells.get(cellCount);
                cell.setLength(0);
                cellCount++;
                atCellStart = true;
            }  
			else {
				// Regular character in an unquoted cell
				cell.append(c);
				atCellStart = false;
			}
		}
	}

	/**
	 * Returns the number of cells in the last row successfully read by
	 * {@link #readRow()}.
	 * <p>
	 * Only indices in the range {@code [0, getCellCount() - 1]} are valid for
	 * {@link #getCell(int)} and {@link #getCellAsString(int)}.
	 *
	 * @return the number of cells in the last row; {@code 0} if no row has been
	 *         read yet or if EOF was reached without any data for a row
	 */
	public int getCellCount() {
		return cellCount;
	}

	/**
	 * Returns the content of a cell from the last row as a {@link CharSequence}.
	 * <p>
	 * The returned object is a {@link StringBuilder} managed and reused by this
	 * reader. Its contents are only valid until the next successful call to
	 * {@link #readRow()}.
	 *
	 * @param index the cell index (0-based)
	 * @return a {@link CharSequence} representing the cell content
	 * @throws IndexOutOfBoundsException if {@code index} is negative or not less
	 *                                   than {@link #getCellCount()}
	 */
	public CharSequence getCell(int index) {
		if (index < 0 || index >= cellCount) {
			throw new IndexOutOfBoundsException("cell index " + index + " out of bounds (count=" + cellCount + ")");
		}
		return cells.get(index);
	}

	/**
	 * Returns the content of a cell from the last row as an immutable
	 * {@link String}.
	 * <p>
	 * Unlike {@link #getCell(int)}, this method creates a new {@link String} and is
	 * therefore more expensive in terms of memory allocations. Use it only if you
	 * need the cell value beyond the next call to {@link #readRow()}, or if you
	 * need an immutable snapshot.
	 *
	 * @param index the cell index (0-based)
	 * @return a {@link String} representing the cell content
	 * @throws IndexOutOfBoundsException if {@code index} is negative or not less
	 *                                   than {@link #getCellCount()}
	 */
	public String getCellAsString(int index) {
		return getCell(index).toString();
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	/**
	 * Fills the internal I/O buffer from the underlying {@link Reader}.
	 * <p>
	 * This method overwrites {@link #buf} with new data and resets {@link #bufPos}
	 * and {@link #bufLen} accordingly.
	 *
	 * @return {@code true} if at least one character was read; {@code false} if EOF
	 *         was reached and no data was read
	 * @throws IOException if an I/O error occurs while reading from the underlying
	 *                     {@link Reader}
	 */
	private boolean fill() throws IOException {
		bufLen = in.read(buf, 0, buf.length);
		bufPos = 0;
		return bufLen > 0;
	}

	/**
	 * Ensures that the internal list of cell buffers has at least
	 * {@code minCapacity} elements, growing the list and adding new
	 * {@link StringBuilder} instances as needed.
	 * <p>
	 * Newly created {@link StringBuilder} instances are initialised with a small
	 * default capacity (64 characters), which is usually sufficient for many CSV
	 * files. Adjust as necessary for your data characteristics.
	 *
	 * @param minCapacity the minimum number of cell buffers required
	 */
	private void ensureCellCapacity(int minCapacity) {
		// Grow the list lazily as needed
		while (cells.size() < minCapacity) {
			cells.add(new StringBuilder(64));
		}
	}
}
