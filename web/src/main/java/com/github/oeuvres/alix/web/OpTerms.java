package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.BitSet;
import java.util.List;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanQueryParser;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.PartScorer;
import com.github.oeuvres.alix.lucene.terms.Partition;
import com.github.oeuvres.alix.lucene.terms.PartitionScorer;
import com.github.oeuvres.alix.lucene.terms.PoleTerms;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
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

    /**
     * Collects the co-occurrents of a span query, as one pole, or as several
     * poles compared when the query is one top-level group, such as
     * {@code ((assimilation assimiler) (accommodation accommoder))}.
     *
     * <p>
     * Each pole is walked alone, so that its snippets are merged only with its
     * own spans, and collected into its own population. Every pole is scored
     * against the same corpus, the whole field or the documents of the filter
     * query. A pole's own pivots are removed from its population; the pivots of
     * the other poles remain tokens of its population but are excluded from its
     * ranking, and their scores are reported apart as attraction between poles.
     * Rankings are fused by {@link PoleTerms}.
     * </p>
     *
     * @param index Lucene index
     * @param pars request parameters
     * @param meta response metadata
     * @return pole terms, or {@code null} without a usable span query
     * @throws IOException if Lucene or rail access fails
     */
    protected static PoleTerms poleTerms(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        final String q = pars.getString(Q, null);
        if (q == null || q.isBlank()) return null;
        final FlucText contentFluc = contentFluc(index, pars, meta);
        if (contentFluc == null) return null;
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final SpanQueryParser.ParseResult parsed = contentFluc.spanQueryParser().parse(q, slop);
        if (parsed.spanQuery() == null) return null;
        // without poles, the whole query is one pole
        final List<SpanQueryParser.Pole> poleQueries = parsed.poles() != null
            ? parsed.poles()
            : List.of(new SpanQueryParser.Pole(parsed.spanQuery(), parsed.queryString()));
        // same entries as Op.spanQuery()
        final SpanQuery rewritten = (SpanQuery) index.searcher().rewrite(parsed.spanQuery());
        meta.put("spanQuery", rewritten.toString(contentFluc.name()));
        meta.put("queryString", parsed.queryString());

        final int terms = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
        final TermLexicon contentLexicon = contentFluc.termLexicon();
        final String include = pars.getString("include", null);
        final int[] incIds = include == null ? null : contentLexicon.termIds(include.split(","));
        final String exclude = pars.getString("exclude", null);
        final int[] excIds = exclude == null ? null : contentLexicon.termIds(exclude.split(","));
        final TermFlag[] tflags = pars.getEnums(TFLAG, TermFlag.NULL);
        final KeynessScorer scorer = tsort(pars);

        final Query filterQuery = filterQuery(index, pars, meta);
        TopTerms corpus = null;
        if (filterQuery != null) {
            final FixedBitSet corpusDocs = index.searcher().search(
                filterQuery,
                new BitsCollectorManager(index.searcher())
            );
            corpus = contentFluc.topTerms().select(index.reader(), corpusDocs);
        }

        final int poleCount = poleQueries.size();
        final String[] labels = new String[poleCount];
        final SpanQuery[] spanQueries = new SpanQuery[poleCount];
        final TopTerms[] poles = new TopTerms[poleCount];
        final int[][] pivotIds = new int[poleCount][];
        int pivotTotal = 0;
        for (int pole = 0; pole < poleCount; pole++) {
            final SpanQueryParser.Pole poleQuery = poleQueries.get(pole);
            final SpanWalker walker = new SpanWalker(
                index.searcher(),
                poleQuery.spanQuery(),
                new DocSnippets(DocSnippets.Usage.POSITIONS, slop),
                filterQuery
            );
            // rewritten query, wildcard expansions included
            pivotIds[pole] = contentLexicon.termIds(walker.spanQuery());
            pivotTotal += pivotIds[pole].length;
            poles[pole] = contentFluc.topTerms();
            final TopCoocSnippets consumer = new TopCoocSnippets(
                contentFluc.termStats(),
                contentFluc.termRail(),
                left,
                right
            ).bindTo(poles[pole].beginPopulation(), corpus);
            walker.walk(consumer);
            consumer.complete();
            poles[pole].populationExclude(pivotIds[pole]);
            labels[pole] = poleQuery.queryString();
            spanQueries[pole] = walker.spanQuery();
        }

        final int[] allPivotIds = new int[pivotTotal];
        int offset = 0;
        for (final int[] ids : pivotIds) {
            System.arraycopy(ids, 0, allPivotIds, offset, ids.length);
            offset += ids.length;
        }
        for (final TopTerms pole : poles) {
            pole.exclude(excIds);
            pole.exclude(allPivotIds);
            pole.include(incIds);
            pole.rank(scorer, terms, tflags);
        }
        return new PoleTerms(labels, spanQueries, poles, pivotIds, scorer, terms);
    }

    protected static TopTerms topTerms(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        final int terms = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        FlucText contentFluc = contentFluc(index, pars, meta);
        if (contentFluc == null) return null;
        TermLexicon contentLexicon = contentFluc.termLexicon();
        TopTerms topTerms = contentFluc.topTerms();
        String include = pars.getString("include", null);
        int[] incIds = null; // set include after exclusion of pivots from population
        if (include != null) {
            String[] incs = include.split(",");
            incIds = contentLexicon.termIds(incs);
        }
        String exclude = pars.getString("exclude", null);
        if (exclude != null) {
            String[] excs = exclude.split(",");
            int[] termIds = contentLexicon.termIds(excs);
            if (termIds.length > 0) topTerms.exclude(termIds);
        }

        
        final TermFlag[] tflags = pars.getEnums(TFLAG, TermFlag.NULL);

        final KeynessScorer scorer = tsort(pars);
        
        
        // Build a filter query from years and tags
        final Query filterQuery = filterQuery(index, pars, meta);
        final SpanQuery spanQuery = spanQuery(index, pars, meta);
        
        // no queries, theme terms
        if (filterQuery == null && spanQuery == null) {
            final String tsort = pars.getString(TSORT, "");
            final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);
            final IdfTermScorer idf = switch (tsort) {
                case "raw" -> new IdfTermScorer.Raw();
                default -> new IdfTermScorer.BM25(idfExp);
            };
            // The weights for full field are cached if same idfExp is requested
            final double[] weights = contentFluc.termStats().termWeights(index.reader(), idf);
            // topTerms will ask the theme terms of corpus, cached if idfExp is always the same
            return topTerms.ranking(weights, terms, tflags);
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
                final Partition partition = Partition.build(fyears, contentFluc, start, end, bits);
                
                final PartScorer partScorer = new PartScorer.LogLikelihoodTail();
                return new PartitionScorer(partition, partScorer)
                    .score(index.reader(), topTerms, terms);
            }
            
            // focus % all rest
            final FixedBitSet focusDocs = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));
            return topTerms.select(index.reader(), focusDocs).rank(scorer, terms, tflags);
        }
        else {
            meta.put("spanQuery", spanQuery.toString());
            // pivotsIds
            final int[] pivotIds = contentFluc.termLexicon().termIds(spanQuery);

            // A filtered co-occurrence focus must be scored against the same
            // filtered corpus. Null means the immutable whole field.
            TopTerms corpus = null;
            if (filterQuery != null) {
                final FixedBitSet corpusDocs = index.searcher().search(
                    filterQuery,
                    new BitsCollectorManager(index.searcher())
                );
                corpus = contentFluc.topTerms().select(index.reader(), corpusDocs);
            }

            // same as for the span query parser
            final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
            final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
            final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
            final SpanWalker walker = new SpanWalker(
                index.searcher(),
                spanQuery,
                new DocSnippets(DocSnippets.Usage.POSITIONS, slop),
                filterQuery
            );
            
            final TopTerms.Population population = topTerms.beginPopulation();
            final TopCoocSnippets consumer = new TopCoocSnippets(
                contentFluc.termStats(),
                contentFluc.termRail(),
                left,
                right
            ).bindTo(population, corpus);
            walker.walk(consumer);
            consumer.complete(); // update TopTerms population
            topTerms.populationExclude(pivotIds);
            topTerms.include(incIds);
            meta.put("pivotIds", pivotIds);
            meta.put("focusDocs", consumer.documentCount());
            meta.put("focusTokens", consumer.tokenCount());
            meta.put("focusSnippets", consumer.contextCount());
            meta.put("pivotCount", consumer.pivotCount());
            return topTerms.rank(scorer, terms, tflags);
        }
    }
    
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final PoleTerms poleTerms = poleTerms(index, pars, meta);
        if (poleTerms != null) {
            final Writer writer = response.getWriter();
            final int poleCount = poleTerms.poleCount();
            writer.append("<table class=\"terms poles\">\n  <tr>\n    <th class=\"no\"></th>\n    <th class=\"term\"></th>\n");
            for (int pole = 0; pole < poleCount; pole++) {
                writer.append("    <th class=\"pole\" colspan=\"2\">%s</th>\n".formatted(poleTerms.label(pole)));
            }
            writer.append("  </tr>\n");
            for (int rank = 0; rank < poleTerms.size(); rank++) {
                final int termId = poleTerms.termId(rank);
                writer.append("  <tr>\n")
                  .append("    <th class=\"no\">%d</th>\n".formatted(rank + 1))
                  .append("    <td class=\"term\">%s</td>\n".formatted(poleTerms.pole(0).lexicon().form(termId)));
                for (int pole = 0; pole < poleCount; pole++) {
                    final String affiliated = poleTerms.affiliated(rank, pole) ? " affiliated" : "";
                    writer.append("    <td class=\"count%s\" align=\"right\">%d</td>\n".formatted(affiliated, poleTerms.pole(pole).termFreq(termId)))
                      .append("    <td class=\"score%s\" align=\"right\">%f</td>\n".formatted(affiliated, poleTerms.score(rank, pole)));
                }
                writer.append("  </tr>\n");
            }
            writer.append("</table>\n");
            return;
        }
        TopTerms topTerms = topTerms(index, pars, meta);
        Writer writer = response.getWriter();
        if (topTerms != null) {
            writer.append("<table class=\"terms\">\n");
            int rank = 1;
            for (TermEntry term : topTerms) {
                writer.append("  <tr>\n")
                  .append("    <th class=\"no\">%d</th>\n".formatted(rank++))
                  .append("    <td class=\"term\">%s</td>\n".formatted(term.form()))
                  .append("    <td class=\"count\" align=\"right\">%d</td>\n".formatted(term.freq()))
                  .append("    <td class=\"docs\" align=\"right\">%d</td>\n".formatted(term.docs()))
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
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final PoleTerms poleTerms = poleTerms(index, pars, meta);
        if (poleTerms != null) {
            TermsUtil.json(request, response, poleTerms);
            return;
        }
        TopTerms topTerms = topTerms(index, pars, meta);
        TermsUtil.json(request, response, topTerms);
    }
    
    @Override
    protected void txt(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final PoleTerms poleTerms = poleTerms(index, pars, meta);
        if (poleTerms != null) {
            TermsUtil.txt(request, response, poleTerms);
            return;
        }
        TopTerms topTerms = topTerms(index, pars, meta);
        TermsUtil.txt(request, response, topTerms);
    }
}
