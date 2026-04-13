package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.BitsCollectorManager;
import com.github.oeuvres.alix.lucene.Fluc;
import com.github.oeuvres.alix.lucene.FlucText;
import com.github.oeuvres.alix.lucene.FlucYear;
import com.github.oeuvres.alix.lucene.HtmlResults;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.spans.SpanVisitor;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.lucene.terms.TermScorer;
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
    protected void page(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        response.setContentType("text/html; charset=UTF-8");
        Writer writer = response.getWriter();
        writer.write("""
        <!DOCTYPE html>
        <html>
          <head>
            <title>Alix, concordance</title>
            <link rel="stylesheet"
                href="https://cdnjs.cloudflare.com/ajax/libs/noUiSlider/15.8.1/nouislider.min.css">
            <style>
              body {
                font-family: sans-serif;
              }
              .hit a {
                 text-decoration: none;
                 color:inherit;
              }
              #slider {
                display:flex;
                justify-content: space-between;
              }
              #slider input {
                width: 6ex;
                text-align: center;
              }
              #slider-cell {
                width: 100%;
                padding-bottom: 1em;
              }
              .noUi-pips-horizontal {
                height: auto;
                padding-top: 0;
              }
              .noUi-value-sub {
                color: #000;
              }
              #slider-year {
                margin: 0 0.5em;
              }
            </style>
          </head>
          <body>
        """);
        // NoUiSlider
        FlucYear years = index.flucYear(YEAR);
        if (years != null) {
            writer.write("""
            <div id="slider">
              <div>
                <input name="start" id="label-start" autocomplete="hidden" for="search-form"/>
              </div>
              <div id="slider-cell">
                <div id="slider-year"></div>
              </div>
              <div>
                <input name="end" id="label-end" autocomplete="hidden" for="search-form"/>
              </div>
            </div>
                
              <script
                src="https://cdnjs.cloudflare.com/ajax/libs/noUiSlider/15.8.1/nouislider.min.js">
              </script>
              <script>
              (function () {
                // Corpus bounds injected by the server
                const MIN = %d;
                const MAX = %d;
                
                // Read current URL params to restore slider position
                const params  = new URLSearchParams(location.search);
                const initStart = parseInt(params.get('start')) || MIN;
                const initEnd   = parseInt(params.get('end'))   || MAX;
                
                const slider = document.getElementById('slider-year');
                
                noUiSlider.create(slider, {
                  start:   [initStart, initEnd],
                  connect: true,
                  step:    1,
                  range:   { min: MIN, max: MAX },
                  format: {
                    to:   v => Math.round(v),
                    from: v => parseInt(v)
                  },
                  // Scale / pips
                  pips: {
                    mode: "steps",
                    density: 3,
                    filter: (value, type) => {
                      // Keep only decade ticks (small) and labels on decades.
                      if (value %% 10 === 0) return 2;
                      if (value %% 5 === 0) return 0;
                      return -1;
                    },
                  },
                });
                
                const labelStart = document.getElementById('label-start');
                const labelEnd   = document.getElementById('label-end');
                
                slider.noUiSlider.on('update', function (values) {
                  labelStart.value = values[0];
                  labelEnd.value   = values[1];
                });
                
                // Submit on release — fires the search
                slider.noUiSlider.on('change', function (values) {
                  const url = new URLSearchParams(location.search);
                  url.set('start', values[0]);
                  url.set('end',   values[1]);
                  location.search = url.toString();
                });
              }());
              </script>
        """.formatted((int) years.min(), (int) years.max()));
        }
        
        writer.write("""
            <form id="search-form">
              <textarea name="%s">%s</textarea>
              <button type="submit" name="%s" value="%s">by date</button>
              <button type="submit" name="%s" value="%s">by score</button>
            </form>
            <section class="hits">
        """.formatted(
                Q,
                pars.getString(Q, ""),
                SORT,
                DATE,
                SORT,
                SCORE
        ));
        
        html(index, request, response); // writes the fragment directly into the same response
        writer.write("""
            </section>
          </body>
        </html>
        """);
        writer.flush();
    }
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        final long t0 = System.currentTimeMillis();
        
        final HttpPars pars = new HttpPars(request, response);
        // Build a filter query from years and tags
        Query filterQuery = filterQuery(index, pars);
        
        Writer writer = response.getWriter();
        final String content = pars.getString(F, index.content());
        final FlucText fluc = index.flucText(content);
        
        // field not found
        if (fluc == null) {
            response.setStatus(404);
            writer.append("<p class=\"error\">Field ");
            Fluc ofluc = index.fluc(content);
            if (ofluc == null)
                writer.append(content + " doesn’t exist.");
            else
                writer.append(ofluc.toString());
            writer.append("/n<br>Choose among:");
            for (Fluc f : index.flucs().values()) {
                if (!(f instanceof FlucText))
                    continue;
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
                .hrefSearch("?" + pars.queryString(CTX, F, Q, SLOP));
        final int from = pars.getInt(FROM, 0);
        
        // final String q = pars.getString(Q, null);
        
        FixedBitSet bits = null;
        if (filterQuery != null) {
            bits = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));
        }
        final SpanQuery spanQuery = spanQuery(index, pars);
        // no query, list docs
        if (spanQuery == null) {
            final int rows = pars.getInt(ROWS, ROWS_RANGE, ROWS_DEFAULT, ROWS);
            FieldStats fieldStats = fluc.fieldStats();
            int docCount = 0;
            boolean more = false;
            int docId = from;
            for (; docId < fieldStats.maxDoc(); docId++) {
                if (fieldStats.docWidth(docId) == 0)
                    continue;
                if (bits != null && !bits.get(docId))
                    continue;
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
                        .append("\">…</a></p>\n");
                ;
            }
            return;
        }
        
        int nextDoc = 0;
        // sorted?
        String sort = pars.getString(SORT, SCORE, Set.of(SCORE, DATE), SORT);
        // relevance
        if (DATE.equals(sort)) {
            SpanWalker walker = new SpanWalker(index.searcher(), spanQuery, filterQuery, results);
            writer
                    .append("<p class=\"statshits\">")
                    .append(String.valueOf(walker.hits()))
                    .append(" documents ")
                    .append(String.valueOf(System.currentTimeMillis() - t0))
                    .append("ms")
                    .append("</p>\n");
            writer.flush();
            nextDoc = walker.walk(from);
        } else {
            Query query;
            if (filterQuery != null) {
                query = new BooleanQuery.Builder()
                        .add(filterQuery, BooleanClause.Occur.FILTER)
                        .add(spanQuery, BooleanClause.Occur.MUST)
                        .build();
            } else {
                query = spanQuery;
            }
            ScoreDoc[] hits = index.searcher().search(query, docs).scoreDocs;
            
            final FieldStats fieldStats = fluc.fieldStats();
            final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);
            fieldStats.buildWeights(index.reader(), new TermScorer.BM25(idfExp));
            
            final SpanVisitor visitor = new SpanVisitor(
                    index.searcher(),
                    spanQuery,
                    results,
                    fieldStats,
                    fluc.termRail(),
                    spans,
                    ctx);
            
            writer
                    .append("<p class=\"statshits\">")
                    .append(String.valueOf(hits.length))
                    .append(" documents ")
                    .append(String.valueOf(System.currentTimeMillis() - t0))
                    .append("ms</p>\n");
            writer.flush();
            
            for (ScoreDoc sd : hits) {
                results.startDoc(sd.doc);
                visitor.visit(sd.doc);
                results.endDoc(visitor.spanTotal());
            }
        }
        
        if (nextDoc > 0) {
            writer
                    .append("<p class=\"next-results\"><a data-from=\"")
                    .append(String.valueOf(nextDoc))
                    .append("\" name=\"next-results\" href=\"")
                    .append("?")
                    .append(pars.queryString(Q, SLOP, END, START, CTX, DOCS, F, DOCLINE))
                    .append("&amp;from=" + nextDoc)
                    .append("\">…</a></p>");
            ;
        }
        writer
                .append("<p class=\"statshits\">")
                .append(String.valueOf(System.currentTimeMillis() - t0))
                .append("ms")
                .append("</p>\n");
    }
}
