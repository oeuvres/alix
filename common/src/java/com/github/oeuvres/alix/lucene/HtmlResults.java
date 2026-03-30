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

public class HtmlResults extends ResultsListener
{
    Writer writer;
    StoredFields storedFields;
    Document doc;
    /** Stored field from which get the text content of the document */
    private String contentFieldName = "content";
    /** The content from which extract snippets */
    private String content;
    /** Stored field from which get a title line for a document */
    private String doclineFieldName = "docline";
    /** A mutable char array with prepend to write spans */
    private Chain snippet = new Chain();
    /** Count of spans outputed */
    private int spanCount;
    /** Limit of spans to output by docs */
    private int spanLimit = -1;
    /** Limit of docs to output */
    private int docLimit = -1;
    /** Current doc count */
    private int docCount = 0;
    /** Count of words to take around a span */
    private int wordsAround = 10;
    /** Current document id */
    private String id;

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
    }
    
    public HtmlResults contentFieldName(final String contentFieldName)
    {
        this.contentFieldName = contentFieldName;
        return this;
    }
    
    public String contentFieldName()
    {
        return this.contentFieldName;
    }

    public HtmlResults doclineFieldName(final String doclineFieldName)
    {
        this.doclineFieldName = doclineFieldName;
        return this;
    }
    
    public String doclineFieldName()
    {
        return this.doclineFieldName;
    }

    public int spanLimit()
    {
        return this.spanLimit;
    }

    public HtmlResults spanLimit(final int spanLimit)
    {
        this.spanLimit = spanLimit;
        return this;
    }

    @Override
    public void start(SpanQuery spanQuery, Query filterQuery, int hits) throws IOException
    {
        writer.append("<section class=\"results\">\n");
        // no need to write the queries here for the user, will be shown by the UI
        // show the results stats here is nice, but there is a problem in localization
    }

    @Override
    public boolean wantsMoreDocs()
    {
        if (docCount >= docLimit) return false;
        return true;
    }

    @Override
    public void startDoc(int docId) throws IOException
    {
        spanCount = 0;
        docCount++;
        doc = storedFields.document(docId); // take all fields, content will be the bigger
        content = doc.get(contentFieldName);
        id = doc.get(ALIX_ID);
        
        writer.append("<article id=\"" + doc.get(ALIX_ID) + "\" data-docid=\"" + docId + "\" class=\"hit\">\n");
        if (doclineFieldName != null) {
            writer.append("<h2><a href=\""+ id + "\">" + doc.get(doclineFieldName) + "</a></h2>");
        }
        
    }

    @Override
    public boolean span(OffsetsCollector collector) throws IOException
    {
        if (spanLimit == 0) return false;
        
        // first span, open list
        if (spanCount == 0) writer.append("<ul class=\"hit spans\">\n");
        spanCount++;
        // spanId, should be unique by doc, allow a link to original doc
        String spanId = "span" + spanCount;
        
        final int termCount = collector.size();
        if (termCount < 1) return true; // bug?
        // clear the mutable char array, but keep left and right capacity
        snippet.setLength(0);
        // works but may be confusing, ask Claude if he has equally efficient but more obvious
        Markup.prependWords(content, collector.startOffset(0), snippet, wordsAround);
        snippet.prepend("<li class=\"hit span\"><a href=\"" + id + "#" + spanId + "\">");
        // append always first term
        snippet.append("<mark class=\"hit pivot\">"
            + content.substring(collector.startOffset(0), collector.endOffset(0))
            + "</mark>");
        for (int termOrd = 1; termOrd < termCount; termOrd++) {
            snippet.append(Markup.detag(content.substring(collector.endOffset(termOrd - 1), collector.startOffset(termOrd))));
            snippet.append("<mark class=\"hit pivot\">"
            + content.substring(collector.startOffset(termOrd), collector.endOffset(termOrd))
            + "</mark>");
        }
        Markup.appendWords(content, collector.endOffset(termCount -1), snippet, wordsAround);
        snippet.append("</a></li>\n");
        writer.append(snippet);
        if (spanLimit > 0 && spanCount >= spanLimit) return false;
        return true;
    }

    @Override
    public void endDoc() throws IOException
    {
        if (spanCount > 0) writer.append("</ul>\n");
        writer.append("</article>\n");
    }

    @Override
    public void end(int nextDocId) throws IOException
    {
        
        writer.append("</section>\n");
        // it is not the job of this results writer to write the link for more results, it is app dependant
        // should be given by a getter
        if (nextDocId > 0) {
            writer.append("<a class=\"next-docid\" data-docid=\"" + nextDocId +"\"/>");
        }
    }

}
