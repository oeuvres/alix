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

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.Fluc;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.spans.ResultsSnippets;
import com.github.oeuvres.alix.lucene.spans.Snippets;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.IdfTermScorer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.util.BitsCollectorManager;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Full concordance in natural docId order.
 */
public class OpResults extends Op {

    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        final long t0 = System.currentTimeMillis();

        final HttpPars pars = new HttpPars(request, response);
        final Query filterQuery = filterQuery(index, pars);
        final MetaUtil meta = new MetaUtil();
        final Writer writer = response.getWriter();

        
        
        final String contentFname = pars.getString(FTEXT, index.content());
        final FlucText contentFluc = index.flucText(contentFname);

        // field not found
        if (contentFluc == null) {
            response.setStatus(404);
            writer.append("<p class=\"error\">Field ");
            Fluc ofluc = index.fluc(contentFname);
            if (ofluc == null)
                writer.append(contentFname + " doesn’t exist.");
            else
                writer.append(ofluc.toString());
            writer.append("\n<br>Choose among:");
            for (Fluc f : index.flucs().values()) {
                if (!(f instanceof FlucText))
                    continue;
                writer.append("<br/><a href=\"?").append(pars.queryString(CTX, DOCLINE, DOCS, END, Q, SLOP, START))
                        .append("&amp;f=").append(f.name()).append("\">").append(f.name()).append("</a>\n");
            }
            writer.append("</p>");
            return;
        }

        final int ctx = pars.getInt(CTX, CTX_RANGE, CTX_DEFAULT, CTX);
        final String docline = pars.getString(DOCLINE, index.docline());
        final int docs = pars.getInt(DOCS, DOCS_RANGE, DOCS_DEFAULT, DOCS);
        // transmit the slop parameter explicitly; cookie may not be transmitted in some contexts
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final int from = pars.getInt(FROM, 0);

        FixedBitSet bits = null;
        if (filterQuery != null) {
            bits = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));
        }
        final SpanQuery spanQuery = spanQuery(index, pars);



        // Snippet-scoring resources, prepared once.
        // No-query branch only emits article shells: snippet scoring is disabled.
        // Query branches use BM25-weighted term scores for in-document snippet ranking.
        final TermRail rail = contentFluc.termRail();
        final double[] termWeights;
        final int snipLimit;
        if (spanQuery == null) {
            termWeights = new double[0];
            snipLimit = 0;
        } else {
            meta.put("spanQuery", spanQuery.toString(contentFname));
            final TermStats fieldStats = contentFluc.termStats();
            final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);
            termWeights = fieldStats.termWeights(index.reader(), new IdfTermScorer.BM25(idfExp));
            snipLimit = pars.getInt(SNIPPETS, SNIPPETS_RANGE, SNIPPETS_DEFAULT, SNIPPETS);
        }
        
        
        writer.append("<!-- \n");
        meta.toString(writer, pars);
        writer.append("\n-->\n");
        
        // MAYBE, get a locale from lang param

        final ResultsSnippets results = new ResultsSnippets(
            writer, 
            index.reader().storedFields(), 
            snipLimit,
            index.locale()
        ).fieldContent(contentFname)
         .fieldDocline(docline)
         .ctx(ctx)
         .rail(rail)
         .termWeights(termWeights)
         .urlTemplate("{docname}?" + pars.queryString(FTEXT, Q, CTX) + "&amp;slop=" + slop);

        // no query, list docs
        if (spanQuery == null) {
            final int rows = pars.getInt(ROWS, ROWS_RANGE, ROWS_DEFAULT, ROWS);
            final TermStats fieldStats = contentFluc.termStats();
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
                results.docOpen(docId, "result-row");
                results.docClose(docId);
            }
            if (more) {
                writer.append("<p class=\"next-results\"><a data-from=\"").append(String.valueOf(docId))
                        .append("\" name=\"next-results\" href=\"").append("?")
                        .append(pars.queryString(DOCLINE, END, FTEXT, ROWS, START)).append("&amp;from=" + docId)
                        .append("\">…</a></p>\n");
            }
            return;
        }

        Snippets snippets = new Snippets(Snippets.Usage.OFFSETS, slop);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            snippets,
            filterQuery
        );
        int nextDoc = 0;

        final String sort = pars.getString(SORT, SCORE, Set.of(SCORE, DATE), SORT);
        if (DATE.equals(sort)) {
            // linear walk in docId order
            writer.append("<p class=\"statshits\">");
            final int hitsCount = walker.hits();
            if (docs < hitsCount) {
                writer.append(String.valueOf(docs)).append("/");
            }
            writer.append(String.valueOf(hitsCount)).append(" textes ").append("</p>\n");
            writer.flush();
            nextDoc = walker.walk(from, docs, results);
        } else {
            // relevance
            final Query query;
            if (filterQuery != null) {
                query = new BooleanQuery.Builder()
                        .add(filterQuery, BooleanClause.Occur.FILTER)
                        .add(spanQuery, BooleanClause.Occur.MUST).build();
            } else {
                query = spanQuery;
            }
            final int hitsCount = index.searcher().count(query);
            final ScoreDoc[] hits = index.searcher().search(query, docs).scoreDocs;

            writer.append("<p class=\"statshits\">");
            if (docs < hitsCount)
                writer.append(String.valueOf(docs)).append("/");
            writer.append(String.valueOf(hitsCount)).append(" documents ").append("</p>\n");
            writer.flush();

            for (ScoreDoc sd : hits) {
                walker.visit(sd.doc);
                // list all snippets in document order
                results.docSnippets(sd.doc, snippets);
            }
        }

        
        if (nextDoc > 0) {
            writer.append("<p class=\"next-results\"><a data-from=\"").append(String.valueOf(nextDoc))
                    .append("\" name=\"next-results\" href=\"").append("?")
                    .append(pars.queryString(Q, SLOP, END, START, CTX, DOCS, FTEXT, DOCLINE))
                    .append("&amp;from=" + nextDoc).append("\">…</a></p>");
        }
        writer.append("<p class=\"statshits\">").append(String.valueOf(System.currentTimeMillis() - t0)).append("ms")
                .append("</p>\n");
    }

    @Override
    protected void page(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final Writer writer = response.getWriter();
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
        final FlucNum years = index.flucNum(YEAR);
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
                """.formatted(Q, pars.getString(Q, ""), SORT, DATE, SORT, SCORE));

        html(index, request, response); // writes the fragment directly into the same response
        writer.write("""
                    </section>
                  </body>
                </html>
                """);
        writer.flush();
    }
}
