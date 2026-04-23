package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.BitsCollectorManager;
import com.github.oeuvres.alix.lucene.FlucNum;
import com.github.oeuvres.alix.lucene.FlucText;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermScorer;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;


/**
 * {@code /{index}/terms} — ranked term lists.
 *
 * <p>
 * Returns a JSON document with top-level {@code meta} and {@code data} keys.
 * On error, the document contains {@code errors} instead of (or alongside)
 * {@code data}.
 * </p>
 *
 * <h2>Parameters</h2>
 * <table>
 *   <tr><td>{@code field}</td><td>indexed field name; defaults to index content field</td></tr>
 *   <tr><td>{@code top}</td><td>number of results; default 50, max 500</td></tr>
 *   <tr><td>{@code idfExp}</td><td>BM25 IDF exponent; default 1.3 (theme terms only)</td></tr>
 *   <tr><td>{@code q}</td><td>query terms for co-occurrence mode (future)</td></tr>
 * </table>
 *
 * <h2>Response</h2>
 * <pre>
 * {
 *   "meta": {
 *     "QTime": 42,
 *     "params": { "field": "text", "top": 50, "idfExp": 1.3 }
 *   },
 *   "data": [
 *     { "term": "enfant", "count": 8234, "score": 12.47 },
 *     …
 *   ]
 * }
 * </pre>
 */
public final class OpTerms extends Op
{

    
    private TopTerms topTerms(final LuceneIndex index, final HttpPars pars, final OpMeta meta) throws IOException
    {
        final int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);
        
        String fieldName = pars.getString(F, index.content());
        final FlucText fluc = index.flucText(fieldName);
        if (fluc == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + fieldName + "' not found or not a text field");
            return null;
        }
        
        // Build a filter query from years and tags
        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        
        TopTerms topTerms = fluc.topTerms();
        // no queries, theme terms
        if (filterQuery == null && spanQuery == null) {
            // an http param may change idfExp
            final TermScorer scorer = new TermScorer.BM25(idfExp);
            // The weights for full field are cached if same idfExp is requested
            final double[] weights = fluc.fieldStats().termWeights(index.reader(), scorer);
            // topTerms will ask the theme terms of corpus, cached if idfExp is always the same
            return topTerms.ranking(weights, topK);
        }
        // no coocs, doc filter query, contrastive terms from a part
        else if (spanQuery == null) {
            final FixedBitSet focusDocs = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));

            final String scorerName = pars.getString(SCORER, null);
            if ("rsj".equals(scorerName)) {
                return topTerms.focus(index.reader(), focusDocs, new TermScorer.BM25(idfExp, TermScorer.BM25.Mode.RSJ), topK);
            }
            else if ("irdf".equals(scorerName)) {
                return topTerms.focus(index.reader(), focusDocs, new TermScorer.BM25(idfExp, TermScorer.BM25.Mode.IRDF), topK);
            }
            else {
                topTerms.focus(index.reader(), focusDocs);
                return topTerms.focusScore(new KeynessScorer.LogLikelihood(), topK);
            }
        }
        // coocs, with or without doc filter TODO
        else {
            pars.response().setStatus(501);
            meta.put("error", "Co-occurrence mode not yet implemented");
            return null;
        }
    }
    
    @Override
    protected void page(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        FlucNum years = index.flucNum(YEAR);
        int[] period = new int[]{(int) years.min(), (int) years.max()};
        final int start = pars.getInt(START, period, (int)years.min());
        final int end = pars.getInt(END, period, (int)years.max());
        String idfexp = String.format(Locale.US, "%.2f", pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP));
        
        
        
        
        Writer writer = response.getWriter();
        writer.write("""
        <!DOCTYPE html>
        <html>
          <head>
            <title>Alix, termes</title>
            <style>
              body {
                font-family: system-ui, sans-serif;
                font-weight: 100;
              }
            </style>
          </head>
          <body>
            <form>
                <input name="start" type="number" value="%d" id="label-start" min="%d" max="%d"/>
                <input name="end" type="number" value="%d" id="label-start" min="%d" max="%d"/>
                <label>Idf exponent
                <input name="idfexp" size="4" value="%s"/>
                </label>
                <button type="submit">Voir</button>
            </form>
            <table>
              <tr>
                <th>%s</th>
                <th>%s</th>
                <th>%s</th>
              </tr>
              <tr>
        """.formatted(start, (int)years.min(), (int)years.max(), end, (int)years.min(), (int)years.max(),
                idfexp,
                "BM25.irdf", "BM25.rsj", LOGLIKELIHOOD));
        writer.append("      <td>\n");
        request.setAttribute(SCORER, "irdf");
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("      <td>\n");
        request.setAttribute(SCORER, "rsj");
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("      <td>\n");
        request.setAttribute(SCORER, LOGLIKELIHOOD);
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("""
              </tr>
            </table>
          </body>
        </html>
        """);
    }
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final OpMeta meta = new OpMeta();
        TopTerms topTerms = topTerms(index, pars, meta);
        Writer writer = response.getWriter();
        if (topTerms != null) {
            writer.append("<table class=\"terms\">\n");
            for (TermEntry term : topTerms) {
                writer.append("  <tr>\n")
                  .append("    <td class=\"term\">%s</td>\n".formatted(term.term()))
                  .append("    <td class=\"count\" align=\"right\">%d</td>\n".formatted(term.freq()))
                  .append("    <td class=\"score\" align=\"right\">%f</td>\n".formatted(term.score()))
                  .append("  </tr>\n");
            }
            writer.append("</table>\n");
        }
        else {
            writer.append(meta.toString());
        }

    }

    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final OpMeta meta = new OpMeta();
        TopTerms topTerms = topTerms(index, pars, meta);

        // ---- serialize ----
        try (JsonWriter jw = jsonWriter(response)) {
            jw.beginObject();

            // meta
            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject(); // meta

            // data
            if (topTerms != null) {
                jw.name("data");
                jw.beginArray();
                for (TermEntry term : topTerms) {
                    jw.beginObject();
                    jw.name("term").value(term.term());
                    jw.name("count").value(term.freq());
                    jw.name("score").value(term.score());
                    jw.endObject();
                }
                jw.endArray();
            }

            jw.endObject();
        }
    }
}
