package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.spans.OffsetsCollector;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Markup;

import static com.github.oeuvres.alix.common.Names.*;

/**
 * A {@link ResultsListener} that writes span search results as an HTML fragment.
 *
 * <p>Each matching document becomes an {@code <article>} element containing an
 * ordered list of span concordance lines. Each line shows a left context, one or
 * more {@code <mark>} elements (the pivot terms), the inter-term text, and a right
 * context. Contexts are stripped of markup by {@link Markup}.</p>
 *
 * <p>The fragment starts with {@code <section class="results">} and ends with
 * {@code </section>}. When traversal is stopped early by {@link #wantsMoreDocs()},
 * an anchor {@code <a class="next-page" data-docid="N"/>} is appended after the
 * closing section tag, where {@code N} is the first docId of the next page
 * ({@link #lastDocId} + 1). The caller is responsible for turning that into a
 * full URL.</p>
 */
public class HtmlResults extends ResultsListener
{
    private final Writer writer;
    private final StoredFields storedFields;
    /** Stored field providing the text content from which snippets are extracted. */
    private String contentFieldName = "content";
    /** Stored field providing the one-line title rendered as a document heading. */
    private String doclineFieldName = "docline";
    /** Number of words of context to show on each side of a span match. */
    private int wordsAround = 10;
    /** Maximum number of spans to emit per document; {@code -1} = unlimited. */
    private int spanLimit = -1;
    /** Maximum number of documents to emit; {@code -1} = unlimited. */
    private int docLimit = -1;

    /** Current Lucene document, refreshed at each {@link #startDoc}. */
    private Document doc;
    /** HTML content of the current document, source for snippet extraction. */
    private String content;
    /** ALIX document id string of the current document, used in links. */
    private String id;
    /** Number of documents emitted so far. */
    private int docCount = 0;
    /** Number of spans emitted for the current document. */
    private int spanCount;
    /** Last global docId seen; used to build the next-page cursor in {@link #end}. */
    private int lastDocId = -1;
    /** Reusable buffer for assembling each concordance line. */
    private final Chain snippet = new Chain();

    /**
     * @param field         field over which results are produced
     * @param docs          total docs having a value for this field
     * @param writer        output destination
     * @param storedFields  access to stored document fields
     * @param contentField  name of the stored field holding the document HTML content
     */
    HtmlResults(
        final String field,
        final int docs,
        final Writer writer,
        final StoredFields storedFields,
        final String contentField
    ) {
        super(field, docs);
        this.writer = writer;
        this.storedFields = storedFields;
        this.contentFieldName = contentField; // was silently dropped before
    }

    /** Sets the name of the stored field holding the document HTML content. */
    public HtmlResults contentFieldName(final String contentFieldName)
    {
        this.contentFieldName = contentFieldName;
        return this;
    }

    public String contentFieldName()
    {
        return this.contentFieldName;
    }

    /** Sets the name of the stored field used as document heading. */
    public HtmlResults doclineFieldName(final String doclineFieldName)
    {
        this.doclineFieldName = doclineFieldName;
        return this;
    }

    public String doclineFieldName()
    {
        return this.doclineFieldName;
    }

    /** Sets the maximum number of spans emitted per document; {@code -1} = unlimited. */
    public HtmlResults spanLimit(final int spanLimit)
    {
        this.spanLimit = spanLimit;
        return this;
    }

    public int spanLimit()
    {
        return this.spanLimit;
    }

    /** Sets the maximum number of documents to emit; {@code -1} = unlimited. */
    public HtmlResults docLimit(final int docLimit)
    {
        this.docLimit = docLimit;
        return this;
    }

    public int docLimit()
    {
        return this.docLimit;
    }

    @Override
    public void start(SpanQuery spanQuery, Query filterQuery, int hits) throws IOException
    {
        writer.append("<section class=\"results\">\n");
    }

    @Override
    public boolean wantsMoreDocs()
    {
        // docLimit < 0 means unlimited; otherwise stop once the limit is reached
        return docLimit < 0 || docCount < docLimit;
    }

    @Override
    public void startDoc(int docId) throws IOException
    {
        spanCount = 0;
        docCount++;
        lastDocId = docId;
        doc = storedFields.document(docId);
        content = doc.get(contentFieldName);
        id = doc.get(ALIX_ID);

        writer.append("<article id=\"").append(id)
              .append("\" data-docid=\"").append(String.valueOf(docId))
              .append("\" class=\"hit\">\n");

        if (doclineFieldName != null) {
            final String docline = doc.get(doclineFieldName);
            if (docline != null) {
                writer.append("<h2><a href=\"").append(id).append("\">")
                      .append(docline).append("</a></h2>\n");
            }
        }
    }

    @Override
    public boolean span(OffsetsCollector collector) throws IOException
    {
        // spanLimit = 0: caller explicitly requested no spans
        if (spanLimit == 0) return false;

        if (spanCount == 0) writer.append("<ol class=\"hit spans\">\n");
        spanCount++;

        final int termCount = collector.size();
        if (termCount < 1) return true;

        snippet.setLength(0);

        // Left context: walk backward from just before the first pivot term.
        // Pass startOffset - 1 so the pivot's own first character is not included.
        Markup.prependWords(content, collector.startOffset(0) - 1, snippet, wordsAround);

        // Opening tag prepended on top of the left context already in the buffer.
        snippet.prepend("<li class=\"hit span\"><a href=\"" + id + "#span" + spanCount + "\">");

        // First pivot term.
        snippet.append("<mark class=\"hit pivot\">");
        snippet.append(content, collector.startOffset(0), collector.endOffset(0));
        snippet.append("</mark>");

        // Remaining pivot terms with inter-term text between them.
        for (int t = 1; t < termCount; t++) {
            // Inter-term text from raw HTML content: strip tags, write into snippet directly.
            Markup.detag(content, collector.endOffset(t - 1), collector.startOffset(t), snippet, null);
            snippet.append("<mark class=\"hit pivot\">");
            snippet.append(content, collector.startOffset(t), collector.endOffset(t));
            snippet.append("</mark>");
        }

        // Right context: walk forward from just past the last pivot term.
        Markup.appendWords(content, collector.endOffset(termCount - 1), snippet, wordsAround);
        snippet.append("</a></li>\n");

        writer.append(snippet);

        return spanLimit < 0 || spanCount < spanLimit;
    }

    @Override
    public void endDoc(int docId) throws IOException
    {
        if (spanCount > 0) writer.append("</ol>\n");
        writer.append("</article>\n");
    }

    @Override
    public void end(boolean completed) throws IOException
    {
        writer.append("</section>\n");
        // Emit a cursor anchor for the caller to build the next-page URL.
        // The listener owns the cursor: it is lastDocId + 1 (the first unvisited doc).
        if (!completed) {
            writer.append("<a class=\"next-page\" data-docid=\"")
                  .append(String.valueOf(lastDocId + 1))
                  .append("\"/>\n");
        }
    }
}
