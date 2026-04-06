package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.logging.Logger;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.common.Names.*;
import static com.github.oeuvres.alix.web.Pars.*;

public class OpDoc extends Op
{
    protected static final Logger LOG = Logger.getLogger(OpDoc.class.getName());
    
    @Override
    public boolean offer(
        LuceneIndex index,
        String docName,
        String format,
        HttpServletRequest request,
        HttpServletResponse response) throws IOException
    {
        final TopDocs topDocs = index.searcher().search(
                new TermQuery(new Term(ALIX_ID, docName)), 10);
        ScoreDoc[] docs = topDocs.scoreDocs;
        if (docs.length < 1) {
            return false;
        }
        // if more than one, log it
        if (docs.length > 1) {
            LOG.warning(docName + ", more than 1 doc with this name in index.");
        }
        final int docId = docs[0].doc;
        // attached the docId as a request attribute
        request.setAttribute(DOCID, docId);
        dispatch(index, format, request, response);
        return true;
    }
    
    @Override
    protected void page(LuceneIndex index, HttpServletRequest req, HttpServletResponse resp)
        throws IOException
    {
        
        resp.setContentType("text/html; charset=UTF-8");
        Writer writer = resp.getWriter();
        writer.write("""
        <!DOCTYPE html>
        <html>
          <head>
            <title>Alix, document</title>
          </head>
          <body>
        """);
        html(index, req, resp);
        writer.write("""
          </body>
        </html>
        """);
        
    }
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        Writer writer = response.getWriter();
        final HttpPars pars = new HttpPars(request);
        final int docId = pars.getInt(DOCID, -1);
        if (docId == -1) {
            response.setStatus(404);
            writer
                .append("<p class=\"error\">")
                .append(DOCID)
                .append("=")
                .append(String.valueOf(docId))
                .append(" not found</p>")
            ;

        }
        final StoredFields storedFields = index.reader().storedFields();
        Document doc = storedFields.document(docId);
        String content = doc.get(index.content());
        SpanQuery spanQuery = spanQuery(index, pars);
        writer.write(content);
    }
}
