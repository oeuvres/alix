package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.Snippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.PartScorer;
import com.github.oeuvres.alix.lucene.terms.Partition;
import com.github.oeuvres.alix.lucene.terms.PartitionScorer;
import com.github.oeuvres.alix.lucene.terms.IdfTermScorer;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.util.BitsCollectorManager;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.lucene.terms.TopTerms.TermValue.FREQ;
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

    
    protected static TopTerms topTerms(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);
        
        String textField = pars.getString(FTEXT, index.content());
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
            final double[] weights = textFluc.termStats().termWeights(index.reader(), scorer);
            // topTerms will ask the theme terms of corpus, cached if idfExp is always the same
            return topTerms.ranking(weights, topK);
        }
        // no coocs, doc filter query, contrastive terms from a part
        else if (spanQuery == null) {
            // partition query on dates
            Query yearQuery = yearQuery(index, pars);
            // TODO tags
            Query typeQuery = typeQuery(index, pars);
            FixedBitSet bits = null;
            if (typeQuery != null) {
                bits = index.searcher().search(typeQuery, new BitsCollectorManager(index.searcher()));
            }
            
            if (yearQuery != null) {
                FlucNum fyears = index.flucNum(YEAR);
                final int start = pars.getInt(START, (int)fyears.min());
                int end = pars.getInt(END, (int)fyears.max());
                
                // TODO filter by tags
                final Partition partition = Partition.build(fyears, textFluc, start, end, bits);
                
                final PartScorer partScorer = new PartScorer.LogLikelihoodTail();
                return new PartitionScorer(partition, partScorer)
                    .score(index.reader(), topTerms, topK);
            }
            
            // focus % all rest
            final FixedBitSet focusDocs = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));
            final KeynessScorer scorer = new KeynessScorer.LogLikelihood();

            return topTerms.select(index.reader(), focusDocs).rank(scorer, topK);
        }
        else {
            // pivotsIds
            final int[] pivotIds = textFluc.termLexicon().termIds(spanQuery);
            // increment the topK of pivots
            topK += pivotIds.length;

            // same as for the span query parser
            final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
            final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
            final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
            final SpanWalker walker = new SpanWalker(
                index.searcher(),
                spanQuery,
                new Snippets(Snippets.Usage.POSITIONS, slop),
                filterQuery
            );
            
            final TopCoocSnippets consumer = new TopCoocSnippets(
                textFluc.termStats(),
                textFluc.termRail(),
                left,
                right);
            consumer.bindTo(topTerms.buffers());
            walker.walk(consumer);
            topTerms.setTotals(consumer.coocTokens(), consumer.coocDocsTotal());
            meta.put("pivotIds", pivotIds);
            meta.put("fieldWidth", textFluc.termStats().fieldWidth());
            meta.put("fieldTokens", textFluc.termStats().fieldTokens());
            meta.put("focusTokens", consumer.coocTokens());
            meta.put("focusDocs", consumer.coocDocsTotal());
            meta.put("hits", walker.hits());
            // choose scorer
            final KeynessScorer scorer = switch (pars.getString(SCORER, "")) {
                case "count" -> new KeynessScorer.Count();
                case "raw" -> new KeynessScorer.Count();
                case "g2" -> new KeynessScorer.LogLikelihood();
                case "logratio" -> new KeynessScorer.LogRatio();
                case "logdice" -> new KeynessScorer.LogDice();
                case "chi2" -> new KeynessScorer.Chi2();
                case "simple" -> new KeynessScorer.SimpleMaths();
                default -> new KeynessScorer.LogLikelihood();
            };
            return topTerms.rank(scorer, topK).promote(pivotIds, FREQ);
        }
    }
    
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
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
        final MetaUtil meta = new MetaUtil();
        TopTerms topTerms = topTerms(index, pars, meta);
        TermsUtil.json(response, meta, pars, topTerms);
    }
}
