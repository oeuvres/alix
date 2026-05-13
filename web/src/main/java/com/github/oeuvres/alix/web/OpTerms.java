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

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.spans.CoocListener;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.PartScorer;
import com.github.oeuvres.alix.lucene.terms.Partition;
import com.github.oeuvres.alix.lucene.terms.PartitionScorer;
import com.github.oeuvres.alix.lucene.terms.IdfTermScorer;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.util.BitsCollectorManager;
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
        
        String textField = pars.getString(F, index.content());
        final FlucText textFluc = index.flucText(textField);
        if (textFluc == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + textField + "' not found or not a text field");
            return null;
        }
        meta.put("textField", textField);
        // Build a filter query from years and tags
        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        
        TopTerms topTerms = textFluc.topTerms();
        // no queries, theme terms
        if (filterQuery == null && spanQuery == null) {
            // an http param may change idfExp
            final IdfTermScorer scorer = new IdfTermScorer.BM25(idfExp);
            // The weights for full field are cached if same idfExp is requested
            final double[] weights = textFluc.fieldStats().termWeights(index.reader(), scorer);
            // topTerms will ask the theme terms of corpus, cached if idfExp is always the same
            return topTerms.ranking(weights, topK);
        }
        // no coocs, doc filter query, contrastive terms from a part
        else if (spanQuery == null) {
            final String scorerName = pars.getString(SCORER, "");
            // partition query on dates
            Query yearQuery = yearQuery(index, pars);
            // TODO tags
            Query typeQuery = typeQuery(index, pars);
            FixedBitSet bits = null;
            if (typeQuery != null) {
                bits = index.searcher().search(typeQuery, new BitsCollectorManager(index.searcher()));
            }
            
            if (yearQuery != null && (scorerName.startsWith("part") || "".equals(scorerName))) {
                FlucNum fyears = index.flucNum(YEAR);
                final int start = pars.getInt(START, (int)fyears.min());
                int end = pars.getInt(END, (int)fyears.max());
                
                // TODO filter by tags
                final Partition partition = Partition.build(fyears, textFluc, start, end, bits);
                if (bits != null) {System.out.println(bits.cardinality());}
                
                final PartScorer partScorer = switch (scorerName) {
                    case "part2" -> new PartScorer.LogLikelihoodTail();
                    case "part3" -> new PartScorer.LogLikelihoodResidual();
                    case "part4" -> new PartScorer.Pearson();
                    default      -> new PartScorer.LogLikelihoodTail();
                };

                return new PartitionScorer(partition, partScorer)
                    .score(index.reader(), topTerms, topK);
            }
            
            // focus % all rest
            final FixedBitSet focusDocs = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));
            final KeynessScorer scorer = "chi2".equals(scorerName)
                    ? new KeynessScorer.Chi2()
                    : new KeynessScorer.LogLikelihood();

            return topTerms.select(index.reader(), focusDocs).rank(scorer, topK);
        }
        // coocs, with or without doc filter TODO
        else {
            final int ctx = pars.getInt(CTX, CTX_RANGE, CTX_DEFAULT, CTX);
            final int left = pars.getInt(CTX_LEFT, CTX_RANGE, ctx, CTX_LEFT);
            final int right = pars.getInt(CTX_RIGHT, CTX_RANGE, ctx, CTX_RIGHT);
            final CoocListener listener = new CoocListener(
                textFluc.fieldStats(),
                textFluc.termRail(),
                left,
                right);
            final SpanWalker walker = new SpanWalker(
                index.searcher(),
                textFluc.termLexicon(),
                spanQuery,
                filterQuery,
                listener);
            listener.bindTo(topTerms.buffers());
            walker.walk(0);
            topTerms.setTotals(listener.coocTokens(), listener.coocDocsTotal());
            
            meta.put("focusTokens", listener.coocTokens());
            meta.put("focusDocs", listener.coocDocsTotal());
            meta.put("hits", walker.hits());
            topTerms.rank(new KeynessScorer.Count(), topK);
            return topTerms;
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
        """.formatted(start, (int)years.min(), (int)years.max(), end, (int)years.min(), (int)years.max(),idfexp)
        );
        writer.append("      <td><b>2x2 LogLikelihood</b><br/>\n");
        request.setAttribute(SCORER, LOG_LIKELIHOOD);
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("      <td><b>Parts LogLikelihood</b><br/>\n");
        request.setAttribute(SCORER, "part1");
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("      <td><b>Parts LogLikelihoodTail</b><br/>\n");
        request.setAttribute(SCORER, "part2");
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("      <td><b>Parts LogLikelihoodResidual</b><br/>\n");
        request.setAttribute(SCORER, "part3");
        html(index, request, response);
        writer.append("      </td>\n");
        writer.append("      <td><b>Parts Pearson</b><br/>\n");
        request.setAttribute(SCORER, "part4");
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
            int rank = 1;
            for (TermEntry term : topTerms) {
                writer.append("  <tr>\n")
                  .append("    <th class=\"term\">%d</th>\n".formatted(rank++))
                  .append("    <td class=\"term\">%s</td>\n".formatted(term.form()))
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
                int rank = 1;
                for (TermEntry term : topTerms) {
                    jw.beginObject();
                    jw.name("rank").value(rank++);
                    jw.name("form").value(term.form());
                    jw.name("docs").value(term.docs());
                    jw.name("fieldDocs").value(term.fieldDocs());
                    jw.name("freq").value(term.freq());
                    jw.name("fieldFreq").value(term.fieldFreq());
                    jw.name("score").value(term.score());
                    jw.endObject();
                }
                jw.endArray();
            }

            jw.endObject();
        }
    }
}
