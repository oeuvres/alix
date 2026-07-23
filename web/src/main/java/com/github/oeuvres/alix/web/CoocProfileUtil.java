package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;

import com.github.oeuvres.alix.lucene.snippets.CoocProfile;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders a {@link CoocProfile} as JSON or as an HTML diagnostic table, parallel to
 * {@link TermsUtil}.
 * <p>
 * The JSON shape is curve-oriented: {@code terms} contains one object per selected term. Each
 * term object carries {@code form}, {@code id}, {@code entryTick}, {@code entryRank}, and parallel
 * {@code count}, {@code docs}, and {@code score} arrays. These arrays start at {@code entryTick};
 * earlier ticks are omitted because the term had not yet entered the per-distance top-K set.
 * </p>
 * <p>
 * Non-finite scores are written as JSON {@code null}.
 * </p>
 */
class CoocProfileUtil
{
    /**
     * Writes the profile as an HTML fragment: a table with one row per term and one column per
     * distance. Cells before {@code entryTick} are intentionally left empty.
     *
     * @param writer  destination
     * @param profile computed profile
     * @throws IOException if writing fails
     */
    static void html(
        final Writer writer,
        final CoocProfile profile
    ) throws IOException {
        final int[] ticks = profile.ticks();

        writer.append("<table class=\"cooc-profile\">\n  <tr><th></th>");
        for (final int t : ticks) {
            writer.append("<th>±%d</th>".formatted(t));
        }
        writer.append("</tr>\n");

        for (int r = 0; r < profile.rows(); r++) {
            final int start = startTick(profile, r, ticks.length);

            writer.append("  <tr><td class=\"term\">%s</td>".formatted(profile.form(r)));
            for (int i = 0; i < ticks.length; i++) {
                if (i < start) {
                    writer.append("<td class=\"count\"></td>");
                }
                else {
                    writer.append("<td class=\"count\" align=\"right\">%d</td>".formatted(profile.count(r, i)));
                }
            }
            writer.append("</tr>\n");
        }

        writer.append("</table>\n");
    }

    /**
     * Writes the standard {@code {meta, ticks, terms}} JSON envelope for a profile. When
     * {@code profile} is {@code null}, only the {@code meta} block is written, carrying any error
     * recorded by the operation.
     *
     * @param response servlet response
     * @param meta     accumulated request-level meta
     * @param pars     resolved parameters, for the meta echo
     * @param profile  computed profile, or {@code null} on error
     * @param scorer   keyness measure used to compute {@code score}; may differ from the one used
     *                 to select the rows
     * @throws IOException if writing fails
     */
    static void json(
        final HttpServletResponse response,
        final MetaUtil meta,
        final HttpPars pars,
        final CoocProfile profile,
        final KeynessScorer scorer
    ) throws IOException {
        try (JsonWriter jw = new JsonWriter(response.getWriter())) {
            jw.beginObject();

            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();

            if (profile != null) {
                final int[] ticks = profile.ticks();
                writeTicks(jw, profile, ticks);
                writeTerms(jw, profile, scorer, ticks);
            }

            jw.endObject();
        }
    }

    /**
     * Checks and returns the first tick to emit for a selected row.
     *
     * @param profile    computed profile
     * @param row        selected row index
     * @param ticksCount number of ticks
     * @return first tick to emit
     */
    private static int startTick(
        final CoocProfile profile,
        final int row,
        final int ticksCount
    ) {
        final int start = profile.entryTick(row);
        if (start < 0 || start >= ticksCount) {
            throw new IllegalStateException(
                "Invalid entryTick %d for row %d, id=%d, form=%s, ticks=%d".formatted(
                    start,
                    row,
                    profile.id(row),
                    profile.form(row),
                    ticksCount
                )
            );
        }
        return start;
    }

    /**
     * Writes one score array for a selected row, starting at the row's entry tick.
     *
     * @param jw         JSON destination
     * @param profile    computed profile
     * @param scorer     keyness scorer
     * @param row        selected row index
     * @param startTick  first tick to write
     * @param ticksCount total tick count
     * @throws IOException if writing fails
     */
    private static void writeScores(
        final JsonWriter jw,
        final CoocProfile profile,
        final KeynessScorer scorer,
        final int row,
        final int startTick,
        final int ticksCount
    ) throws IOException {
        jw.name("score");
        jw.beginArray();
        for (int i = startTick; i < ticksCount; i++) {
            final double score = profile.score(row, i, scorer);
            if (Double.isFinite(score)) {
                jw.value(score);
            }
            else {
                jw.nullValue();
            }
        }
        jw.endArray();
    }

    /**
     * Writes one JSON object per selected term. Count, document, and score arrays start at
     * {@code entryTick}; earlier points are intentionally omitted.
     *
     * @param jw      JSON destination
     * @param profile computed profile
     * @param scorer  keyness scorer
     * @param ticks   distance ticks
     * @throws IOException if writing fails
     */
    private static void writeTerms(
        final JsonWriter jw,
        final CoocProfile profile,
        final KeynessScorer scorer,
        final int[] ticks
    ) throws IOException {
        jw.name("terms");
        jw.beginArray();

        for (int r = 0; r < profile.rows(); r++) {
            final int start = startTick(profile, r, ticks.length);

            jw.beginObject();
            jw.name("form").value(profile.form(r));
            jw.name("id").value(profile.id(r));
            jw.name("entryTick").value(start);
            jw.name("entryRank").value(profile.entryRank(r));

            jw.name("count");
            jw.beginArray();
            for (int i = start; i < ticks.length; i++) {
                jw.value(profile.count(r, i));
            }
            jw.endArray();

            jw.name("docs");
            jw.beginArray();
            for (int i = start; i < ticks.length; i++) {
                jw.value(profile.docCount(r, i));
            }
            jw.endArray();

            writeScores(jw, profile, scorer, r, start, ticks.length);

            jw.endObject();
        }

        jw.endArray();
    }

    /**
     * Writes the distance ticks and their cumulative focus totals.
     *
     * @param jw      JSON destination
     * @param profile computed profile
     * @param ticks   distance ticks
     * @throws IOException if writing fails
     */
    private static void writeTicks(
        final JsonWriter jw,
        final CoocProfile profile,
        final int[] ticks
    ) throws IOException {
        jw.name("ticks");
        jw.beginArray();

        for (int i = 0; i < ticks.length; i++) {
            jw.beginObject();
            jw.name("distance").value(ticks[i]);
            jw.name("tokens").value(profile.tokens(i));
            jw.name("docs").value(profile.docsTotal(i));
            jw.endObject();
        }

        jw.endArray();
    }
}
