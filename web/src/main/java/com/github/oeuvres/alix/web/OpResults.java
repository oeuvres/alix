package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.ScoreDoc;

import com.github.oeuvres.alix.lucene.Fluc;
import com.github.oeuvres.alix.lucene.FlucText;
import com.github.oeuvres.alix.lucene.HtmlResults;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.spans.SpanQueryParser;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Full concordance in natural docId order
 */
public class OpResults extends Op
{

    @Override
    public String name()
    {
        return "results";
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
              <textarea name="%s">%s</textarea>
              <label>
                 <input type="checkbox" name="%s" value="%s"/>
                 Sort by date
              </label>
              <input type="submit" />
            </form>
            <section class="hits">
        """.formatted(
            Q,
            pars.getString(Q, ""),
            SORTED,
            pars.getBoolean(SORTED, false, SORTED)
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
        // the filter query 
        final int start = pars.getInt(START, -1);
        final int end = pars.getInt(END, -1);
        // the tags?

        Writer writer = resp.getWriter();
        final String content = pars.getString(F, index.content());
        final FlucText fluc = index.flucText(content);
        
        // field not found
        if (fluc == null) {
            resp.setStatus(404);
            writer.append("<p class=\"error\">Field ");
            Fluc ofluc = index.fluc(content);
            if (ofluc == null) writer.append(content + " doesn’t exist.");
            else writer.append(ofluc.toString());
            writer.append("/n<br>Choose among:");
            for (Fluc f : index.flucs().values()) {
                if (!(f instanceof FlucText)) continue;
                writer
                    .append("<br/><a href=\"?")
                    .append(pars.queryString(CTX, DOCLINE, DOCS, END, Q, SLOP, START))
                    .append("&amp;f=")
                    .append(f.name())
                    .append("\">")
                    .append(f.name())
                    .append("</a>\n");
            }
            writer.append("</p>");
            return;
        }
        
        // Build an HTML results writer
        final int ctx = pars.getInt(CTX, CTX_RANGE, CTX_DEFAULT, CTX);
        final String docline = pars.getString(DOCLINE, index.docline());
        final int docs = pars.getInt(DOCS, DOCS_RANGE, DOCS_DEFAULT, DOCS);
        final int spans = pars.getInt(SPANS, SPANS_RANGE, SPANS_DEFAULT, SPANS);
        
        HtmlResults results = new HtmlResults(
            writer, 
            index.reader().storedFields(),
            content)
            .doclineFieldName(docline)
            .docLimit(docs)
            .spanLimit(spans)
            .ctx(ctx)
            .hrefSearch("?" + pars.queryString(CTX, F, Q, SLOP))
        ;
        final int from = pars.getInt(FROM, 0);

        final String q = pars.getString(Q, null);
        
        // no query, list docs
        if (q == null) {
            final int rows = pars.getInt(ROWS, ROWS_RANGE, ROWS_DEFAULT, ROWS);
            FieldStats fieldStats = fluc.fieldStats();
            int docCount = 0;
            boolean more = false;
            int docId = from;
            for (; docId < fieldStats.maxDoc(); docId++) {
                if (fieldStats.docWidth(docId) == 0) continue;
                if (docCount >= rows) {
                    more = true;
                    break;
                }
                docCount++;
                results.startDoc(docId);
                results.endDoc(0);
            }
            if (more) {
                writer
                    .append("<p class=\"next-results\"><a data-from=\"")
                    .append(String.valueOf(docId))
                    .append("\" name=\"next-results\" href=\"")
                    .append("?")
                    .append(pars.queryString(DOCLINE, END, F, ROWS, START))
                    .append("&amp;from=" + docId)
                    .append("\">…</a></p>");
                ;
            }
            return;
        }

        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        SpanQuery query = new SpanQueryParser(content, slop).parse(q);

        
        // sorted?
        final boolean sorted = pars.getBoolean(SORTED, false, SORTED);
        // relevance
        if (!sorted) {
            ScoreDoc[] hits = index.searcher().search(query, 10).scoreDocs;
            
        }
        
        
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
