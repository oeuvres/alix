package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.TreeSet;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocProfile;
import com.github.oeuvres.alix.lucene.snippets.CoocProfileSnippets;
import com.github.oeuvres.alix.lucene.snippets.Snippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * {@code /{index}/cooc-profile} — how a pivot's co-occurrents shift with distance.
 *
 * <p>
 * For a pivot span query {@code q}, returns the cumulative co-occurrence counts of context terms at
 * several nested distances {@code xticks} (default {@code 5,10,20,40} tokens, symmetric). A single
 * walk at the widest radius fills the whole {@code term × distance} grid via
 * {@link CoocProfileSnippets} into a {@link CoocProfile}; each tick's column is ranked independently
 * and the union of the per-tick top-K is kept, every term carrying its full vector across all ticks
 * so the curves are continuous on the client. Pivots are excluded — a pivot's count is flat across
 * ticks (its occurrences sit at distance 0) and would distort the plot scale. Rows are sorted by
 * descending score at the widest tick, matching the vertical order of end-of-curve labels.
 * </p>
 *
 * <h2>Parameters</h2>
 * <table>
 *   <tr><td>{@code q}</td><td>pivot span query; <em>required</em></td></tr>
 *   <tr><td>{@code ftext}</td><td>indexed text field; defaults to the index content field</td></tr>
 *   <tr><td>{@code xticks}</td><td>distance radii, comma-separated; default {@code 5,10,20,40},
 *       each clamped to {@code [1,200]}</td></tr>
 *   <tr><td>{@code terms}</td><td>top-K per tick (a floor on the row count, not a cap); default 50</td></tr>
 *   <tr><td>{@code tsort}</td><td>keyness scorer: {@code g2} (default), {@code logratio},
 *       {@code logdice}, {@code chi2}, {@code simple}, {@code count}</td></tr>
 *   <tr><td>{@code slop}, {@code year}, {@code type}</td><td>span slop and corpus filters, as elsewhere</td></tr>
 * </table>
 */
public final class OpCoocProfile extends Op
{
    @Override
    protected void html(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final CoocProfile profile = profile(index, pars, meta);
        final Writer writer = response.getWriter();
        if (profile != null) {
            CoocProfileUtil.html(writer, profile);
        }
        else {
            meta.toHtml(writer, pars);
        }
    }

    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final CoocProfile profile = profile(index, pars, meta);
        final KeynessScorer scorer = scorer(pars.getString(TSORT, ""));
        CoocProfileUtil.json(response, meta, pars, profile, scorer);
    }

    /**
     * Computes the co-occurrence-by-distance profile for the request, shared by {@link #html} and
     * {@link #json}.
     *
     * @param index target Lucene index
     * @param pars  resolved parameters
     * @param meta  request-level meta accumulator; an error is recorded here when {@code null} is
     *              returned, and {@code hits}, {@code fieldTokens}, {@code pivotIds} are recorded on
     *              success
     * @return the selected profile, or {@code null} when {@code q} is missing or the field is unknown
     * @throws IOException if searching or rail access fails
     */
    protected static CoocProfile profile(
        final LuceneIndex index,
        final HttpPars pars,
        final MetaUtil meta
    ) throws IOException {
        final SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) {
            pars.response().setStatus(400);
            meta.put("error", "parameter 'q' (pivot span query) is required");
            return null;
        }
        final String textField = pars.getString(FTEXT, index.content());
        final FlucText textFluc = index.flucText(textField);
        if (textFluc == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + textField + "' not found or not a text field");
            return null;
        }
        meta.put("textField", textField);

        final TermLexicon textLexicon = textFluc.termLexicon();
        final TermStats textStats = textFluc.termStats();
        final TermFlag tflag = pars.getEnum(TFLAG, TermFlag.NULL);

        // distance ticks: symmetric windows, clamped, deduplicated, ascending
        int[] ticks = pars.getIntList(XTICKS);
        if (ticks.length == 0) {
            ticks = XTICKS_DEFAULT.clone();
        }
        ticks = clampTicks(ticks);
        // symmetric for now; separate left/right arrays leave room for asymmetric ticks
        final int[] left = ticks;
        final int[] right = ticks;

        final int[] pivotIds = textLexicon.termIds(spanQuery);
        final int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);

        // one walk at the widest radius fills every tick
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final Query filterQuery = filterQuery(index, pars);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            new Snippets(Snippets.Usage.POSITIONS, slop),
            filterQuery);

        final CoocProfile profile = new CoocProfile(textStats, textLexicon, ticks);
        final CoocProfileSnippets consumer = new CoocProfileSnippets(
            profile, textStats, textFluc.termRail(), left, right);
        walker.walk(consumer);
        profile.cumulate();

        final KeynessScorer scorer = scorer(pars.getString(TSORT, ""));
        profile.select(scorer, topK, pivotIds, tflag);

        meta.put("hits", walker.hits());
        meta.put("fieldTokens", textStats.fieldTokens());
        meta.put("pivotIds", pivotIds);
        return profile;
    }

    /**
     * Clamps each requested radius to {@code [1, CTX_RANGE max]}, deduplicates, and sorts ascending,
     * so the windows nest as {@link CoocProfileSnippets} requires.
     *
     * @param ticks requested radii, in any order
     * @return clamped, deduplicated, ascending radii
     */
    private static int[] clampTicks(
        final int[] ticks
    ) {
        final TreeSet<Integer> set = new TreeSet<>();
        for (final int t : ticks) {
            set.add(Math.max(1, Math.min(XTICKS_RANGE[1], t)));
        }
        final int[] out = new int[set.size()];
        int k = 0;
        for (final int v : set) {
            out[k++] = v;
        }
        return out;
    }

    /**
     * Resolves the {@code tsort} parameter to a keyness scorer, matching the co-occurrence mode of
     * {@link OpTerms}.
     *
     * @param tsort scorer key, possibly empty
     * @return the scorer; log-likelihood when unrecognised
     */
    private static KeynessScorer scorer(
        final String tsort
    ) {
        return switch (tsort) {
            case "count", "raw" -> new KeynessScorer.Count();
            case "g2" -> new KeynessScorer.LogLikelihood();
            case "logratio" -> new KeynessScorer.LogRatio();
            case "logdice" -> new KeynessScorer.LogDice();
            case "chi2" -> new KeynessScorer.Chi2();
            case "simple" -> new KeynessScorer.SimpleMaths();
            default -> new KeynessScorer.LogLikelihood();
        };
    }
}