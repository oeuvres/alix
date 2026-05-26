package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.spans.Snippets;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OpSnippets extends Op
{
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        Writer writer = response.getWriter();
        final HttpPars pars = new HttpPars(request, response);
        final String docName = pars.getString(DOCNAME, null);
        int docId = pars.getInt(DOCID, -1);
        if (docId == -1) {
            docId = AlixServlet.docIdByName(index, docName);
        }
        if (docId == -1) {
            response.setStatus(404);
            writer
                .append("<p class=\"error\">")
                .append(DOCNAME)
                .append("=")
                .append(String.valueOf(docName))
                .append("  ")
                .append(DOCID)
                .append("=")
                .append(String.valueOf(docId))
                .append(" not found</p>")
            ;
            return;
        }
        final StoredFields storedFields = index.reader().storedFields();
        Document doc = storedFields.document(docId);
        String content = doc.get(index.content());
        if (content == null || content.isBlank()) {
            response.setStatus(404);
            writer
                .append("<p class=\"error\">")
                .append(DOCNAME)
                .append("=")
                .append(String.valueOf(docName))
                .append("  ")
                .append(DOCID)
                .append("=")
                .append(String.valueOf(docId))
                .append(" empty</p>")
            ;
            return;
        }
        SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) {
            // output nothing?
            return;
        }
        
    }

}
