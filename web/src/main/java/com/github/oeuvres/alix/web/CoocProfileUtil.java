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
 * The JSON {@code data} is tidy-long: one object per {@code (term, tick)} pair carrying
 * {@code form}, {@code id}, {@code distance}, {@code count}, {@code docs} and {@code score}. That row shape feeds an Observable Plot line mark directly
 * ({@code Plot.line(rows, {x: "distance", y: "count", z: "form"})}). Non-finite scores (a scorer
 * undefined at zero focus count) are written as JSON {@code null}.
 * </p>
 */
class CoocProfileUtil
{
    /**
     * Writes the profile as an HTML fragment: a table with one row per term and one column per
     * distance, holding cumulative counts. Intended for streamed insertion, not a full page.
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
            writer.append("  <tr><td class=\"term\">%s</td>".formatted(profile.form(r)));
            for (int i = 0; i < ticks.length; i++) {
                writer.append("<td class=\"count\" align=\"right\">%d</td>".formatted(profile.count(r, i)));
            }
            writer.append("</tr>\n");
        }
        writer.append("</table>\n");
    }

    /**
     * Writes the standard {@code {meta, ticks, data}} JSON envelope for a profile. When
     * {@code profile} is {@code null} only the {@code meta} block is written, carrying any error
     * recorded by the operation.
     *
     * @param response servlet response (JSON content type set by {@link Op#jsonWriter})
     * @param meta     accumulated request-level meta ({@code hits}, {@code fieldTokens},
     *                 {@code pivotIds} are expected to already be recorded on success)
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
        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();

            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject(); // meta

            if (profile != null) {
                final int[] ticks = profile.ticks();

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

                jw.name("data");
                jw.beginArray();
                for (int r = 0; r < profile.rows(); r++) {
                    for (int i = 0; i < ticks.length; i++) {
                        jw.beginObject();
                        jw.name("form").value(profile.form(r));
                        jw.name("id").value(profile.id(r));
                        jw.name("distance").value(ticks[i]);
                        jw.name("count").value(profile.count(r, i));
                        jw.name("docs").value(profile.docCount(r, i));
                        final double s = profile.score(r, i, scorer);
                        jw.name("score");
                        if (Double.isFinite(s)) {
                            jw.value(s);
                        } else {
                            jw.nullValue();
                        }
                        jw.endObject();
                    }
                }
                jw.endArray();
            }

            jw.endObject();
        }
    }
}