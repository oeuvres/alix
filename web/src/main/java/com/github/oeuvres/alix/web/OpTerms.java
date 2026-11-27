package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
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
import com.github.oeuvres.alix.lucene.snippets.FocusPivots;
import com.github.oeuvres.alix.lucene.snippets.SpanQueryParser;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.PartScorer;
import com.github.oeuvres.alix.lucene.terms.Partition;
import com.github.oeuvres.alix.lucene.terms.PartitionScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.ThemeTerms;
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
 *   <tr><td>{@code terms}</td><td>number of results; default 50, max 5000</td></tr>
 *   <tr><td>{@code tsort}</td><td>local keyness scorer for theme voting (default simpleridf); raw or bm25 with vote=none</td></tr>
 *   <tr><td>{@code vote}</td><td>theme aggregation: rrf (default) or none for legacy whole-field scoring</td></tr>
 *   <tr><td>{@code rrf}</td><td>nonnegative rank offset; defaults to index configuration</td></tr>
 *   <tr><td>{@code votes}</td><td>maximum keywords per document ballot; defaults to index configuration</td></tr>
 *   <tr><td>{@code specif}</td><td>local keyness specificity; default 0.5</td></tr>
 *   <tr><td>{@code simplek}</td><td>optional smoothing centre for Simple Maths scorers</td></tr>
 *   <tr><td>{@code idfexp}</td><td>BM25 IDF exponent; used with vote=none and tsort=bm25 in theme mode</td></tr>
 *   <tr><td>{@code q}</td><td>query terms for co-occurrence mode</td></tr>
 *   <tr><td>{@code focusrank}</td><td>rrf (default) or common (shared vocabulary of exclusive focuses)</td></tr>
 * </table>
 *
 * <h2>Response</h2>
 * <pre>
 * {
 *   "meta": {
 *     "QTime": 42,
 *     "params": { "field": "text", "terms": 50, "vote": "rrf", "tsort": "simpleridf", "specif": 0.5 }
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
     * Collects and ranks the ordinary search snippets, partitioning by exact
     * focus membership only for a top-level union of two to four focuses.
     *
     * @param index Lucene index
     * @param pars request parameters
     * @param meta response metadata
     * @return cooccurrence terms, or null without a usable query
     * @throws IOException if index or rail access fails
     */
    protected static FocusPivots focusTerms(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        final String q = pars.getString(Q, null);
        if (q == null || q.isBlank()) return null;
        final FlucText contentFluc = contentFluc(index, pars, meta);
        if (contentFluc == null) return null;
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final SpanQueryParser.ParseResult parsed = contentFluc.spanQueryParser().parse(q, slop);
        if (parsed.spanQuery() == null) return null;
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

        final List<SpanQueryParser.Focus> focuses = parsed.focuses();
        final List<String> labels = new ArrayList<>();
        int[][] focusIds = new int[0][];
        if (focuses != null && focuses.size() <= FocusPivots.MAX_FOCUSES) {
            focusIds = new int[focuses.size()][];
            for (int i = 0; i < focuses.size(); i++) {
                final SpanQueryParser.Focus focus = focuses.get(i);
                labels.add(focus.queryString());
                focusIds[i] = contentLexicon.termIds(index.searcher().rewrite(focus.spanQuery()));
            }
        }
        final FocusPivots result = new FocusPivots(rewritten, contentFluc.termStats(),
            contentLexicon, contentFluc.termRail(), corpus, left, right, labels, focusIds);
        final SpanWalker walker = new SpanWalker(index.searcher(), rewritten,
            new DocSnippets(DocSnippets.Usage.POSITIONS, slop), filterQuery);
        walker.walk(result);
        final boolean common = labels.size() >= 2 && "common".equals(pars.getString("focusrank", "rrf"));
        result.complete(scorer, terms, incIds, excIds, common, tflags);
        meta.put("focusrank", common ? "common" : "rrf");
        meta.put("pivotCount", result.spanCount());
        return result;
    }

    /**
     * Ranks whole-field theme terms, filtered terms or span cooccurrences.
     * Used by the terms operation and by the query-free compass.
     *
     * @param index Lucene index
     * @param pars request parameters
     * @param meta response metadata
     * @return ranked terms, or null when the content field is unavailable
     * @throws IOException if index access fails
     */
    protected static TopTerms topTerms(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        final int terms = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        FlucText contentFluc = contentFluc(index, pars, meta);
        if (contentFluc == null) return null;
        TermLexicon contentLexicon = contentFluc.termLexicon();
        TopTerms topTerms = contentFluc.topTerms();
        String include = pars.getString("include", null);
        int[] incIds = null;
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

        // Build a filter query from years and tags
        final Query filterQuery = filterQuery(index, pars, meta);
        final String q = pars.getString(Q, null);
        final SpanQuery spanQuery = q == null || q.isBlank() ? null : spanQuery(index, pars, meta);
        
        // no queries, theme terms
        if (filterQuery == null && spanQuery == null) {
            final String vote = pars.getString("vote", "rrf");
            final double[] weights;
            if ("none".equals(vote)) {
                final String tsort = pars.getString(TSORT, "bm25");
                final ThemeTerms theme = "raw".equals(tsort) ? new ThemeTerms.Raw()
                    : new ThemeTerms.BM25(pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP));
                weights = contentFluc.termStats().termWeights(index.reader(), theme);
                meta.put("themeScorer", "raw".equals(tsort) ? "raw" : "bm25");
                meta.put("vote", "none");
            }
            else {
                // Rank within the chosen vocabulary; never change CF, DF or lengths.
                final BitSet candidates = new BitSet(contentLexicon.vocabSize());
                boolean all = tflags.length == 0;
                for (final TermFlag flag : tflags) {
                    if (flag == TermFlag.NULL) { all = true; break; }
                    candidates.or(contentLexicon.bits(flag));
                }
                if (all) {
                    candidates.set(1, contentLexicon.vocabSize());
                    candidates.andNot(contentLexicon.bits(TermFlag.STOPWORD));
                }
                if (incIds != null) {
                    for (final int id : incIds) if (id > 0) candidates.set(id);
                }
                final String tsort = pars.getString(TSORT, "simpleridf");
                final KeynessScorer scorer;
                if ("simpleridf".equals(tsort) || "rrf".equals(tsort)) {
                    // The old tsort=rrf remains an alias for the default local scorer.
                    final double specif = pars.getDouble(SPECIF, SPECIF_RANGE, SPECIF_DEFAULT, SPECIF);
                    final double simplek = pars.getDouble(SIMPLEK, SIMPLEK_RANGE, Double.NaN);
                    scorer = new KeynessScorer.SimpleRidf(simplek).specif(specif);
                } else if ("bm25".equals(tsort)) {
                    // BM25Alpha is the local keyness variant; the original
                    // corpus BM25 sum remains available with vote=none.
                    final double specif = pars.getDouble(SPECIF, SPECIF_RANGE, SPECIF_DEFAULT, SPECIF);
                    scorer = new KeynessScorer.BM25Alpha(150, 0.75f).specif(specif);
                } else {
                    scorer = tsort(pars);
                }
                final int rrf = pars.getInt("rrf", new int[] {0, Integer.MAX_VALUE}, index.themeRrf());
                final int votes = pars.getInt("votes", new int[] {1, Math.max(5000, index.themeVotes())}, index.themeVotes());
                weights = ThemeTerms.rrf(index.reader(), contentFluc.termStats(), candidates,
                    scorer, rrf, votes);
                meta.put("themeScorer", scorer.code());
                meta.put("vote", "rrf");
            }
            topTerms.include(incIds);
            return topTerms.ranking(weights, terms, tflags);
        }
        // no coocs, doc filter query, contrastive terms from a part
        else if (spanQuery == null) {
            final KeynessScorer scorer = tsort(pars);
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
            final KeynessScorer scorer = tsort(pars);
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
            topTerms.include(incIds);
            topTerms.exclude(pivotIds);
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
        final FocusPivots focusTerms = focusTerms(index, pars, meta);
        if (focusTerms != null) {
            TermsUtil.html(response.getWriter(), focusTerms);
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
        final FocusPivots focusTerms = focusTerms(index, pars, meta);
        if (focusTerms != null) {
            TermsUtil.json(request, response, focusTerms);
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
        final FocusPivots focusTerms = focusTerms(index, pars, meta);
        if (focusTerms != null) {
            TermsUtil.txt(request, response, focusTerms);
            return;
        }
        TopTerms topTerms = topTerms(index, pars, meta);
        TermsUtil.txt(request, response, topTerms);
    }
}
