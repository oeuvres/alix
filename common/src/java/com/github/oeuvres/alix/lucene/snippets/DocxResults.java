package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;

import com.github.oeuvres.alix.office.Docx;
import com.github.oeuvres.alix.util.Detagger;
import com.github.oeuvres.alix.office.Html;

import static com.github.oeuvres.alix.common.Names.*;

/**
 * A {@link ResultsRenderer} that fills a {@code template.docx} through the
 * dependency-free {@link Docx} writer. Each concordance line becomes a
 * paragraph with bold pivots; an APA citation on the {@link SnippetView} becomes
 * a footnote whose runs preserve inline italics, followed by the snippet URL.
 *
 * <p>Because the servlet must stream bytes, construct this against
 * {@code response.getOutputStream()} <em>before</em> any writer is acquired, and
 * call {@link #close()} to flush the filled package.</p>
 *
 * <p>Wiring: the caller sets {@link SnippetView#citationHtml(String)} from the
 * existing HTML-APA builder. This class holds no Lucene scoring; snippet
 * selection is done upstream exactly as for the HTML renderer.</p>
 */
public final class DocxResults implements ResultsRenderer {
    private final Docx docx;
    private final OutputStream out;
    private final StoredFields storedFields;
    private final Detagger detagger;
    private final int ctx;
    private String contentField = "content";
    private String fieldDocline = "docline";
    private String urlTemplate = "";
    private int cachedDocId = -1;
    private Document doc;
    private String content;
    private String docname;

    /**
     * Creates a docx renderer bound to a template and an output stream.
     *
     * @param template     path to the {@code .docx} carrying {@code {{BODY}}}
     *                     and {@code {{NOTES}}} placeholders
     * @param out          servlet output stream, owned by the caller
     * @param storedFields stored-field access, valid for the renderer's life
     * @param detagger     context normaliser
     * @param ctx          context width in words
     */
    public DocxResults(
        final Path template,
        final OutputStream out,
        final StoredFields storedFields,
        final Detagger detagger,
        final int ctx
    ) {
        this.docx = new Docx(template);
        this.out = out;
        this.storedFields = storedFields;
        this.detagger = detagger;
        this.ctx = ctx;
    }

    /**
     * Streams the filled package. The output stream is not closed here; the
     * servlet container owns it.
     *
     * @throws IOException on write failure
     */
    @Override
    public void close() throws IOException {
        docx.write(out);
        out.flush();
    }

    /**
     * Sets the content field name.
     *
     * @param contentField stored-field name
     * @return this
     */
    public DocxResults contentField(final String contentField) {
        this.contentField = contentField;
        return this;
    }

    @Override
    public void docClose(final int docId) {
        // no per-document trailer in docx
    }

    @Override
    public void docOpen(final int docId, final String kind) throws IOException {
        ensureDoc(docId);
        if (fieldDocline == null) return;
        final String title = doc.get(fieldDocline);
        if (title == null) return;
        docx.appendXml("<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>");
        docx.append(title, false, true);
        docx.appendXml("</w:p>");
    }

    @Override
    public void docSnippets(final int docId, final DocSnippets snippets) throws IOException {
        docOpen(docId, null);
        snippets(docId, snippets);
        docClose(docId);
    }

    /**
     * Sets the docline (heading) field name; {@code null} suppresses headings.
     *
     * @param fieldDocline stored-field name or {@code null}
     * @return this
     */
    public DocxResults fieldDocline(final String fieldDocline) {
        this.fieldDocline = fieldDocline;
        return this;
    }

    @Override
    public void snippet(final int docId, final SnippetView view) throws IOException {
        docx.appendXml("<w:p>");
        for (final SnippetView.Seg s : view.segs()) {
            final boolean pivot = s.kind() == SnippetView.Kind.PIVOT;
            Html.toRuns(s.html(), (t, it, b) -> docx.append(t, it, b || pivot));
        }
        final String citation = view.citationHtml();
        if (citation != null) {
            final StringBuilder note = new StringBuilder();
            Html.toRuns(citation, (t, it, b) -> note.append(Docx.run(t, it, b)));
            if (view.url() != null) {
                note.append(Docx.run(" " + view.url(), false, false));
            }
            final int id = docx.footnote(note.toString());
            docx.appendXml(Docx.reference(id));
        }
        docx.appendXml("</w:p>");
    }

    @Override
    public void snippets(final int docId, final DocSnippets snippets) throws IOException {
        ensureDoc(docId);
        if (content == null) return;
        final int count = snippets.count();
        for (int snipOrd = 0; snipOrd < count; snipOrd++) {
            final SnippetView view = SnippetView.of(content, snippets, snipOrd, ctx, detagger)
                .url(docUrl());
            // view.citationHtml(apa.forDoc(doc));   // <-- wire the APA builder here
            snippet(docId, view);
        }
    }

    /**
     * Sets the URL template ({@code {docname}}, {@code {docid}} placeholders).
     *
     * @param urlTemplate pattern
     * @return this
     */
    public DocxResults urlTemplate(final String urlTemplate) {
        this.urlTemplate = urlTemplate;
        return this;
    }

    private String docUrl() {
        return urlTemplate.replace("{docname}", docname).replace("{docid}", Integer.toString(cachedDocId));
    }

    private void ensureDoc(final int docId) throws IOException {
        if (cachedDocId == docId) return;
        final Set<String> want = (fieldDocline != null)
            ? Set.of(ALIX_ID, contentField, fieldDocline)
            : Set.of(ALIX_ID, contentField);
        this.doc = storedFields.document(docId, want);
        this.docname = doc.get(ALIX_ID);
        this.content = doc.get(contentField);
        this.cachedDocId = docId;
    }
}
