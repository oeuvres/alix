package com.github.oeuvres.alix.office;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimal streaming XLSX writer in one class, no dependency. Values are stored in
 * the locale-independent OOXML representation: text as UTF-8 inline strings, never
 * parsed; numbers with the invariant decimal point; dates as serial numbers with
 * the locale-sensitive built-in short date format (numFmtId 14). Display
 * localization is left to the spreadsheet application. Rows are written through
 * to the output stream and cannot be revisited; workbook-level parts are flushed
 * by {@link #close()}, without which the file is invalid.
 *
 * <pre>
 * try (Xlsx xlsx = new Xlsx(out)) {
 *     xlsx.sheet("contexte");
 *     xlsx.row("forme", "freq", "score");
 *     xlsx.row("liberté", 1024, 12.667);
 * }
 * </pre>
 *
 * An optional template, given at construction, supplies presentation parts only:
 * its {@code xl/styles.xml} and {@code xl/theme/theme1.xml} are copied verbatim,
 * everything else is ignored. The writer always owns the structural parts. A
 * template must respect the style contract: {@code cellXfs} entry 0 is the
 * default format, entry 1 a date format; otherwise dates display as raw serial
 * numbers.
 */
public class Xlsx implements Closeable
{
    /** Day mapped to serial number 0 by the spreadsheet date system. */
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    /** Default styles part: cellXfs entry 0 general, entry 1 locale-sensitive short date. */
    private static final String STYLES =
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        + "<fonts count=\"1\"><font/></fonts>"
        + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
        + "<borders count=\"1\"><border/></borders>"
        + "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
        + "<cellXfs count=\"2\"><xf numFmtId=\"0\" xfId=\"0\"/><xf numFmtId=\"14\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
        + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
        + "</styleSheet>";
    /** Common XML declaration of all parts. */
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    /** Guard against double close. */
    private boolean closed;
    /** Character writer on the worksheet entry currently open, null before the first sheet. */
    private Writer sheet;
    /** Names of the sheets written so far, in order. */
    private final List<String> sheets = new ArrayList<>();
    /** True if a template provided a theme part. */
    private final boolean theme;
    /** Underlying zip container of the workbook. */
    private final ZipOutputStream zip;

    /**
     * Open a workbook with the embedded minimal presentation parts.
     *
     * @param out destination of the xlsx bytes (file, servlet response…), closed by {@link #close()}.
     * @throws IOException from the underlying stream.
     */
    public Xlsx(final OutputStream out) throws IOException
    {
        this(out, null);
    }

    /**
     * Open a workbook taking presentation parts from a template workbook. The
     * template stream is fully read but not closed; the caller remains its owner.
     *
     * @param out destination of the xlsx bytes, closed by {@link #close()}.
     * @param template an xlsx workbook, or null for the embedded defaults.
     * @throws IOException from either stream.
     */
    public Xlsx(final OutputStream out, final InputStream template) throws IOException
    {
        zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        byte[] styles = null;
        byte[] themePart = null;
        if (template != null) {
            final ZipInputStream zin = new ZipInputStream(template, StandardCharsets.UTF_8);
            for (ZipEntry entry; (entry = zin.getNextEntry()) != null;) {
                if ("xl/styles.xml".equals(entry.getName())) styles = zin.readAllBytes();
                else if ("xl/theme/theme1.xml".equals(entry.getName())) themePart = zin.readAllBytes();
            }
        }
        theme = (themePart != null);
        part("[Content_Types].xml", (XML
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + (theme ? "<Override PartName=\"/xl/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" : "")
            + "</Types>").getBytes(StandardCharsets.UTF_8));
        part("_rels/.rels", (XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        part("xl/styles.xml", (styles != null) ? styles : (XML + STYLES).getBytes(StandardCharsets.UTF_8));
        if (theme) part("xl/theme/theme1.xml", themePart);
    }

    /**
     * Finish the current sheet, write the workbook parts, close the container and
     * the underlying stream. Idempotent.
     *
     * @throws IOException from the underlying stream.
     * @throws IllegalStateException if no sheet was created; such a workbook would be invalid.
     */
    @Override
    public void close() throws IOException
    {
        if (closed) return;
        closed = true;
        if (sheets.isEmpty()) {
            zip.close();
            throw new IllegalStateException("A workbook without sheet is invalid, call sheet() before close()");
        }
        endSheet();
        final StringBuilder book = new StringBuilder(XML);
        final StringBuilder rels = new StringBuilder(XML);
        book.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"")
            .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets.size(); i++) {
            book.append("<sheet name=\"").append(escape(sheets.get(i - 1)))
                .append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"")
                .append(" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        book.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        if (theme) {
            rels.append("<Relationship Id=\"rId").append(sheets.size() + 2)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>");
        }
        rels.append("</Relationships>");
        part("xl/workbook.xml", book.toString().getBytes(StandardCharsets.UTF_8));
        part("xl/_rels/workbook.xml.rels", rels.toString().getBytes(StandardCharsets.UTF_8));
        zip.close();
    }

    /**
     * Append one row to the current sheet; one argument, one cell, type decided by
     * the runtime class. String → text, never parsed, leading zeros safe; Integer,
     * Long, Short, Byte, BigDecimal, Double, Float → number; Boolean → boolean
     * cell; LocalDate → date; null → empty cell. Any other class, as well as NaN
     * and infinities, illegal in cell values, are an error, not a toString() guess.
     *
     * @param cells values in column order.
     * @throws IOException from the underlying stream.
     * @throws IllegalArgumentException on an unsupported type or a non-finite floating value.
     * @throws IllegalStateException if no sheet is open.
     */
    public void row(final Object... cells) throws IOException
    {
        if (sheet == null) throw new IllegalStateException("No sheet, call sheet() before row()");
        sheet.write("<row>");
        for (final Object cell : cells) {
            if (cell == null) {
                sheet.write("<c/>");
            }
            else if (cell instanceof String text) {
                sheet.write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(text) + "</t></is></c>");
            }
            else if (cell instanceof Double || cell instanceof Float) {
                final double value = ((Number) cell).doubleValue();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite number: " + value);
                sheet.write("<c><v>" + value + "</v></c>");
            }
            else if (cell instanceof Integer || cell instanceof Long || cell instanceof Short || cell instanceof Byte) {
                sheet.write("<c><v>" + cell + "</v></c>");
            }
            else if (cell instanceof BigDecimal decimal) {
                sheet.write("<c><v>" + decimal.toPlainString() + "</v></c>");
            }
            else if (cell instanceof Boolean bool) {
                sheet.write("<c t=\"b\"><v>" + (bool ? 1 : 0) + "</v></c>");
            }
            else if (cell instanceof LocalDate date) {
                sheet.write("<c s=\"1\"><v>" + ChronoUnit.DAYS.between(EPOCH, date) + "</v></c>");
            }
            else {
                throw new IllegalArgumentException("Unsupported cell type: " + cell.getClass().getName());
            }
        }
        sheet.write("</row>\n");
    }

    /**
     * Append many rows, convenience over {@link #row(Object...)}.
     *
     * @param rows each array is one row.
     * @throws IOException from the underlying stream.
     */
    public void rows(final Iterable<Object[]> rows) throws IOException
    {
        for (final Object[] cells : rows) row(cells);
    }

    /**
     * Close the current sheet if any and start a new one after it.
     *
     * @param sheetName tab label, 1–31 chars, without the reserved : \ / ? * [ ].
     * @throws IOException from the underlying stream.
     * @throws IllegalArgumentException on an invalid name.
     */
    public void sheet(final String sheetName) throws IOException
    {
        if (sheetName == null || sheetName.isEmpty() || sheetName.length() > 31
            || sheetName.chars().anyMatch(c -> ":\\/?*[]".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Invalid sheet name: " + sheetName);
        }
        endSheet();
        sheets.add(sheetName);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheets.size() + ".xml"));
        sheet = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        sheet.write(XML
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>\n");
    }

    /**
     * Close the worksheet entry currently open, if any.
     *
     * @throws IOException from the underlying stream.
     */
    private void endSheet() throws IOException
    {
        if (sheet == null) return;
        sheet.write("</sheetData></worksheet>");
        sheet.flush();
        zip.closeEntry();
        sheet = null;
    }

    /**
     * Escape XML special characters and strip control characters illegal in
     * XML 1.0 (everything below U+0020 except tab, line feed, carriage return).
     *
     * @param text raw value.
     * @return XML-safe value for element content and attributes.
     */
    private static String escape(final String text)
    {
        final StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0, len = text.length(); i < len; i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Write a complete zip entry.
     *
     * @param name entry path inside the container.
     * @param bytes full content of the part.
     * @throws IOException from the underlying stream.
     */
    private void part(final String name, final byte[] bytes) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
