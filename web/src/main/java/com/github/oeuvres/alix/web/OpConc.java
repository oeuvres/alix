package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.HtmlResults;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.spans.SpanQueryParser;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.common.Names.*;
import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Full concordance in natural docId order
 */
public class OpConc extends Op
{

    @Override
    public String name()
    {
        return "conc";
    }
    
    @Override
    protected void page(LuceneIndex index, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        final HttpPars pars = new HttpPars(req);
        resp.setContentType("text/html; charset=UTF-8");
        Writer writer = resp.getWriter();
        writer.write("""
        <!DOCTYPE html>
        <html>
          <head>
            <title>Alix, concordance</title>
          </head>
          <body>
            <form>
              <textarea name="q">%s</textarea>
              <input type="submit" />
            </form>
            <section class="hits">
        """.formatted(
            pars.getString(Q, "")
        ));
        
        html(index, req, resp); // writes the fragment directly into the same response
        writer.write("""
            </section>
          </body>
        </html>
        """);
        writer.flush();
    }
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        final long t0 = System.currentTimeMillis();
        final HttpPars pars = new HttpPars(req);
        final String q = pars.getString(Q, null);
        if (q == null) {
            // how to say there are no results?
            return;
        }
        final String content = pars.getString(F, index.content());
        final int ctx = pars.getInt(CTX, CTX_RANGE, CTX_DEFAULT, CTX);
        final String docline = pars.getString(DOCLINE, index.docline());
        final int docs = pars.getInt(DOCS, DOCS_RANGE, DOCS_DEFAULT, DOCS);
        final int from = pars.getInt(FROM, 0);
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final int spans = pars.getInt(SPANS, SPANS_RANGE, SPANS_DEFAULT, SPANS);
        // start and end
        final int start = pars.getInt(START, -1);
        final int end = pars.getInt(END, -1);
        
        SpanQuery query = new SpanQueryParser(content, slop).parse(q);
        Writer writer = resp.getWriter();
        
        HtmlResults results = new HtmlResults(
            writer, 
            index.reader().storedFields(),
            content)
            .doclineFieldName(docline)
            .docLimit(docs)
            .spanLimit(spans)
            .ctx(ctx)
            .hrefSearch("?" + pars.queryString(Q, SLOP, CTX))
        ;
        SpanWalker walker = new SpanWalker(index.searcher(), query, null, results);
        writer
            .append("<p class=\"statshits\">")
            .append(String.valueOf(walker.hits()))
            .append(" documents ")
            .append(String.valueOf(System.currentTimeMillis() - t0))
            .append("ms")
            .append("</p>\n")
        ;
        writer.flush();
        int nextDoc = walker.walk(from);
        if (nextDoc > 0 ) {
            writer
                .append("<p class=\"next-results\"><a data-from=\"")
                .append(String.valueOf(nextDoc))
                .append("\" name=\"next-results\" href=\"")
                .append("?")
                .append(pars.queryString(CTX, DOCLINE, DOCS, END, F, Q, SLOP, START))
                .append("&amp;from=" + nextDoc)
                .append("\">…</a></p>");
            ;
        }
        writer
            .append("<p class=\"statshits\">")
            .append(String.valueOf(System.currentTimeMillis() - t0))
            .append("ms")
            .append("</p>\n")
        ;
    }
}
