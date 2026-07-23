package com.github.oeuvres.alix.office;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimal, dependency-free writer that fills a {@code template.docx} whose
 * {@code word/document.xml} contains a literal {@code {{BODY}}} placeholder and
 * whose {@code word/footnotes.xml} contains a {@code {{NOTES}}} placeholder,
 * following the two seeded separator notes. All other package parts are copied
 * through untouched, so the styles, relationships and content-type overrides
 * authored once in Word are preserved.
 *
 * <p>Body and footnote fragments are accumulated as OOXML text, then streamed
 * into a fresh package by {@link #write(OutputStream)}. Footnote ids are handed
 * out by {@link #footnote(CharSequence)} starting at {@code 1}; ids {@code -1}
 * and {@code 0} are reserved by the template for the separator notes.</p>
 *
 * <p>This class owns format concerns only: it never inspects Lucene state. It
 * is not thread-safe.</p>
 */
public final class Docx {
    private static final String BODY_MARK = "{{BODY}}";
    private static final String NOTES_MARK = "{{NOTES}}";

    private final byte[] template;
    private final StringBuilder body = new StringBuilder();
    private final StringBuilder notes = new StringBuilder();
    private int footnoteSeq = 0;

    /**
     * Creates a writer bound to a template package held in memory.
     *
     * @param template bytes of a {@code .docx} carrying the {@code {{BODY}}} and
     *                 {@code {{NOTES}}} placeholders
     */
    public Docx(final byte[] template) {
        this.template = template;
    }

    /**
     * Reads a template package from the classpath into a byte array.
     *
     * @param resource absolute classpath resource, for example
     *                 {@code /templates/template.docx}
     * @return the package bytes
     * @throws IOException           on read failure
     * @throws IllegalStateException if the resource is absent
     */
    public static byte[] classpath(final String resource) throws IOException {
        try (InputStream in = Docx.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("classpath resource not found: " + resource);
            return in.readAllBytes();
        }
    }

    /**
     * Appends a run of body text as a single {@code <w:r>}. Whitespace is
     * preserved so concordance context keeps its boundary spaces around pivots.
     *
     * @param text   run text; illegal control characters are dropped and XML
     *               metacharacters escaped
     * @param italic emit {@code <w:i/>}
     * @param bold   emit {@code <w:b/>} (used for pivots)
     */
    public void append(final String text, final boolean italic, final boolean bold) {
        body.append(run(text, italic, bold));
    }

    /**
     * Appends raw, already-formed body OOXML (for example a paragraph the
     * caller assembled). No cleaning or escaping is applied.
     *
     * @param xml well-formed body fragment
     */
    public void appendXml(final CharSequence xml) {
        body.append(xml);
    }

    /**
     * Removes characters illegal in XML 1.0. {@code #x9}, {@code #xA} and
     * {@code #xD} are kept; every other code point below {@code #x20} is
     * dropped. These cannot be represented even as numeric entities, so they
     * must be removed rather than escaped; TEI- and OCR-derived corpus text
     * carries stray form feeds and vertical tabs that would otherwise make the
     * part non-well-formed.
     *
     * @param s raw text
     * @return text safe to place in an XML document
     */
    public static String clean(final String s) {
        final int n = s.length();
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes the five XML metacharacters. Applied after {@link #clean(String)}.
     *
     * @param s cleaned text
     * @return escaped text
     */
    public static String escape(final String s) {
        final int n = s.length();
        final StringBuilder sb = new StringBuilder(n + 8);
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Reserves the next footnote id, appends a note to the footnotes buffer,
     * and returns the id. The note opens with the auto-numbering run
     * ({@code <w:footnoteRef/>}) so Word renders the marker; the caller-supplied
     * run OOXML follows.
     *
     * @param noteRunsXml one or more {@code <w:r>} fragments (build them with
     *                    {@link #run(String, boolean, boolean)})
     * @return the footnote id to pass to {@link #reference(int)}
     */
    public int footnote(final CharSequence noteRunsXml) {
        final int id = ++footnoteSeq;
        notes.append("<w:footnote w:id=\"").append(id).append("\">")
             .append("<w:p><w:pPr><w:pStyle w:val=\"FootnoteText\"/></w:pPr>")
             .append("<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteRef/></w:r>")
             .append("<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>")
             .append(noteRunsXml)
             .append("</w:p></w:footnote>");
        return id;
    }

    /**
     * Builds the in-body reference run for a footnote id.
     *
     * @param id id returned by {@link #footnote(CharSequence)}
     * @return a {@code <w:r>} fragment carrying {@code <w:footnoteReference>}
     */
    public static String reference(final int id) {
        return "<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr>"
             + "<w:footnoteReference w:id=\"" + id + "\"/></w:r>";
    }

    /**
     * Builds one {@code <w:r>} run with {@code xml:space="preserve"}. Run
     * property order follows the schema ({@code w:b} before {@code w:i}).
     *
     * @param text   run text, cleaned and escaped here
     * @param italic emit {@code <w:i/>}
     * @param bold   emit {@code <w:b/>}
     * @return a run fragment
     */
    public static String run(final String text, final boolean italic, final boolean bold) {
        final StringBuilder sb = new StringBuilder(32 + text.length());
        sb.append("<w:r>");
        if (italic || bold) {
            sb.append("<w:rPr>");
            if (bold) sb.append("<w:b/>");
            if (italic) sb.append("<w:i/>");
            sb.append("</w:rPr>");
        }
        sb.append("<w:t xml:space=\"preserve\">").append(escape(clean(text))).append("</w:t></w:r>");
        return sb.toString();
    }

    /**
     * Streams a filled package to {@code out}: every template part is copied
     * verbatim except {@code word/document.xml} and {@code word/footnotes.xml},
     * whose placeholders are replaced by the accumulated buffers. The caller
     * owns {@code out} and its closing.
     *
     * @param out destination stream
     * @throws IOException              if reading the template or writing fails
     * @throws IllegalStateException    if a placeholder is missing from the template
     */
    public void write(final OutputStream out) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(template));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            final byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                final ByteArrayOutputStream part = new ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) != -1) part.write(buf, 0, r);
                byte[] data = part.toByteArray();
                if ("word/document.xml".equals(e.getName())) {
                    data = fill(new String(data, StandardCharsets.UTF_8), BODY_MARK, body).getBytes(StandardCharsets.UTF_8);
                } else if ("word/footnotes.xml".equals(e.getName())) {
                    data = fill(new String(data, StandardCharsets.UTF_8), NOTES_MARK, notes).getBytes(StandardCharsets.UTF_8);
                }
                final ZipEntry ne = new ZipEntry(e.getName());
                zout.putNextEntry(ne);
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    /**
     * Splits {@code xml} at {@code mark} and inserts {@code content}.
     *
     * @param xml     part text
     * @param mark    placeholder token
     * @param content replacement
     * @return filled part
     */
    private static String fill(final String xml, final String mark, final CharSequence content) {
        final int at = xml.indexOf(mark);
        if (at < 0) throw new IllegalStateException("template missing placeholder " + mark);
        return xml.substring(0, at) + content + xml.substring(at + mark.length());
    }
}
