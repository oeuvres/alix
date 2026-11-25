package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

import com.github.oeuvres.alix.lucene.snippets.FocusPivots;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Serializes ranked terms and query populations for the web operations.
 */
public class TermsUtil
{
    /**
     * Writes the selected terms and per-population counts and scores as text.
     * @param request servlet request carrying metadata
     * @param response servlet response
     * @param terms partitioned cooccurrence terms
     * @throws IOException if writing fails
     */
    static void txt(final HttpServletRequest request, final HttpServletResponse response,
            final FocusPivots terms) throws IOException
    {
        final Writer writer = response.getWriter();
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        meta.toString(writer, (HttpPars) request.getAttribute(ALIX_PARS));
        for (final FocusPivots.Population pop : terms.populations()) {
            writer.append(label(terms, pop.mask())).append("\n");
        }
        for (int r = 0; r < terms.size(); r++) {
            final int id = terms.termId(r);
            writer.append(String.valueOf(r + 1)).append(". ").append(terms.lexicon().form(id)).append(" (");
            for (int p = 0; p < terms.populations().size(); p++) {
                if (p > 0) writer.append(" | ");
                writer.append(String.valueOf(terms.populations().get(p).terms().termFreq(id)))
                    .append(" ; ").append(String.format(Locale.ROOT, "%.5g", terms.score(p, id)));
            }
            writer.append("), ");
        }
    }

    /**
     * Writes a table with one count and one score column per population.
     * @param writer HTML writer
     * @param terms partitioned cooccurrence terms
     * @throws IOException if writing fails
     */
    static void html(final Writer writer, final FocusPivots terms) throws IOException
    {
        writer.append("<table class=\"terms\"><tr><th></th><th></th>");
        for (final FocusPivots.Population pop : terms.populations()) {
            writer.append("<th colspan=\"2\">").append(escape(label(terms, pop.mask()))).append("</th>");
        }
        writer.append("</tr>\n");
        for (int r = 0; r < terms.size(); r++) {
            final int id = terms.termId(r);
            writer.append("<tr><th class=\"no\">").append(String.valueOf(r + 1))
                .append("</th><td class=\"term\">").append(escape(terms.lexicon().form(id))).append("</td>");
            for (int p = 0; p < terms.populations().size(); p++) {
                writer.append("<td class=\"count\">").append(String.valueOf(terms.populations().get(p).terms().termFreq(id)))
                    .append("</td><td class=\"score\">").append(String.format(Locale.ROOT, "%.5g", terms.score(p, id)))
                    .append("</td>");
            }
            writer.append("</tr>\n");
        }
        writer.append("</table>\n");
    }

    /**
     * Writes a ranked term list as text.
     * @param request servlet request carrying metadata
     * @param response servlet response
     * @param terms ranked terms
     * @throws IOException if writing fails
     */
    static void txt(
        final HttpServletRequest request,
        final HttpServletResponse response,
        TopTerms terms
    ) throws IOException {
        int rank = 1;
        Writer writer = response.getWriter();
        MetaUtil meta = (MetaUtil)request.getAttribute(ALIX_META);
        // why this write nothing?
        meta.toString(writer, (HttpPars)request.getAttribute(ALIX_PARS));
        for (TermEntry term : terms) {
            writer.append(String.valueOf(rank++)).append(". ")
            .append(term.form())
            .append(" (")
            .append(String.format(Locale.FRANCE, "%.5g", term.score()))
            .append(" ; ")
            .append(String.valueOf(term.freq()))
            .append("), ");
        }
    }

    /**
     * Writes cooccurrence terms, query pivots and exact-membership populations.
     * @param request servlet request carrying metadata
     * @param response servlet response
     * @param terms partitioned cooccurrence terms
     * @throws IOException if writing fails
     */
    static void json(final HttpServletRequest request, final HttpServletResponse response,
            final FocusPivots terms) throws IOException
    {
        final JsonWriter jw = new JsonWriter(response.getWriter());
        jw.setIndent("  ");
        jw.beginObject();
        jw.name("meta");
        jw.beginObject();
        ((MetaUtil) request.getAttribute(ALIX_META)).toJson(jw, (HttpPars) request.getAttribute(ALIX_PARS));
        jw.endObject();
        jw.name("data");
        jw.beginObject();
        populations(jw, terms);
        jw.name("pivots").beginArray();
        for (final int id : terms.pivotIds()) {
            jw.beginObject();
            pivotFields(jw, terms, id);
            jw.endObject();
        }
        jw.endArray();
        jw.name("terms").beginArray();
        for (int r = 0; r < terms.size(); r++) {
            jw.beginObject();
            termFields(jw, terms, r);
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
        jw.endObject();
    }

    /**
     * Writes a ranked term list and any excluded pivots as JSON.
     * @param request servlet request carrying metadata
     * @param response servlet response
     * @param terms ranked terms, or null when unavailable
     * @throws IOException if writing fails
     */
    static void json(
        final HttpServletRequest request,
        final HttpServletResponse response,
        TopTerms terms
    ) throws IOException {
        JsonWriter jw = new JsonWriter(response.getWriter());
        jw.setIndent("  ");
        MetaUtil meta = (MetaUtil)request.getAttribute(ALIX_META);
        boolean hasContexts = meta.get("spanQuery") != null;
        jw.beginObject();

        // meta
        jw.name("meta");
        jw.beginObject();
        meta.toJson(jw, (HttpPars)request.getAttribute(ALIX_PARS));
        jw.endObject(); // meta

        // data: { pivots, terms }, pivots only when the population has some
        if (terms != null) {
            jw.name("data");
            jw.beginObject();
            if (!terms.excludedTerms().isEmpty()) {
                jw.name("pivots");
                jw.beginArray();
            }
            for (final TopTerms.ExcludedTerm pivot : terms.excludedTerms()) {
                jw.beginObject();
                jw.name("form").value(pivot.form());
                jw.name("docs").value(pivot.docs());
                if (hasContexts) jw.name("contexts").value(pivot.contexts());
                jw.name("freq").value(pivot.freq());
                jw.name("fieldDocs").value(pivot.fieldDocs());
                jw.name("fieldFreq").value(pivot.fieldFreq());
                jw.name("id").value(pivot.termId());
                jw.endObject();
            }
            if (!terms.excludedTerms().isEmpty()) {
                jw.endArray(); // pivots
            }

            jw.name("terms");
            jw.beginArray();
            int rank = 0;
            for (TermEntry term : terms) {
                jw.beginObject();
                termFields(jw, term, rank++, hasContexts);
                jw.endObject();
            }
            jw.endArray(); // terms
            jw.endObject(); // data
        }

        jw.endObject();
    }

    /**
     * Escapes text embedded in an HTML cell.
     * @param value cell text
     * @return escaped text
     */
    private static String escape(final String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Labels an exact-membership population, including the absent focuses.
     * @param terms partitioned cooccurrence terms
     * @param mask population membership mask
     * @return human-readable label
     */
    private static String label(final FocusPivots terms, final int mask)
    {
        if (terms.focuses().isEmpty()) return "All contexts";
        final StringBuilder label = new StringBuilder();
        for (int i = 0; i < terms.focuses().size(); i++) {
            if (i > 0) label.append(" ∧ ");
            if ((mask & (1 << i)) == 0) label.append("¬");
            label.append(terms.focuses().get(i));
        }
        return label.toString();
    }

    /**
     * Writes union totals and, only for a comparison, population definitions in mask order.
     * @param jw JSON writer inside data object
     * @param terms partitioned cooccurrence terms
     * @throws IOException if writing fails
     */
    static void populations(final JsonWriter jw, final FocusPivots terms) throws IOException
    {
        jw.name("docs").value(terms.docs());
        jw.name("contexts").value(terms.contexts());
        jw.name("tokens").value(terms.tokens());
        if (terms.populations().size() <= 1) return;
        jw.name("focuses").beginArray();
        for (final String label : terms.focuses()) jw.value(label);
        jw.endArray();
        jw.name("populations").beginArray();
        for (final FocusPivots.Population pop : terms.populations()) {
            jw.beginObject();
            jw.name("mask").value(pop.mask());
            jw.name("label").value(label(terms, pop.mask()));
            jw.name("docs").value(pop.terms().docs());
            jw.name("contexts").value(pop.terms().contexts());
            jw.name("tokens").value(pop.terms().tokens());
            jw.endObject();
        }
        jw.endArray();
    }

    /**
     * Writes a query pivot and its counts in each population.
     * @param jw JSON writer inside a pivot object
     * @param terms partitioned cooccurrence terms
     * @param id dense term id
     * @throws IOException if writing fails
     */
    static void pivotFields(final JsonWriter jw, final FocusPivots terms, final int id) throws IOException
    {
        identity(jw, terms, id);
        if (terms.populations().size() > 1) {
            jw.name("mask").value(terms.pivotMask(id));
            termPopulations(jw, terms, id, false);
        }
    }

    /**
     * Writes one ranked term and its statistics, without opening or closing the
     * term object, so callers may append vector coordinates or other fields.
     * @param jw JSON writer inside a term object
     * @param term ranked term entry
     * @param rank zero-based rank
     * @param hasContexts whether to include context counts
     * @throws IOException if writing fails
     */
    static void termFields(final JsonWriter jw, final TermEntry term, final int rank,
            final boolean hasContexts) throws IOException
    {
        jw.name("rank").value(rank + 1);
        jw.name("form").value(term.form());
        jw.name("id").value(term.termId());
        jw.name("html").value(term.hilite()); // for suggest
        jw.name("docs").value(term.docs());
        if (hasContexts) jw.name("contexts").value(term.contexts());
        jw.name("fieldDocs").value(term.fieldDocs());
        jw.name("freq").value(term.freq());
        jw.name("fieldFreq").value(term.fieldFreq());
        jw.name("score").value(term.score()); // for terms
    }

    /**
     * Writes one selected term and its counts, scores and ranks by population.
     * @param jw JSON writer inside a term object
     * @param terms partitioned cooccurrence terms
     * @param rank zero-based final rank
     * @throws IOException if writing fails
     */
    static void termFields(final JsonWriter jw, final FocusPivots terms, final int rank) throws IOException
    {
        final int id = terms.termId(rank);
        jw.name("rank").value(rank + 1);
        identity(jw, terms, id);
        if (terms.populations().size() > 1) {
            jw.name("rrf").value(terms.rrf(id));
            termPopulations(jw, terms, id, true);
        }
        else {
            jw.name("score");
            number(jw, terms.populations().isEmpty() ? Double.NaN : terms.score(0, id));
        }
    }

    /**
     * Writes lexicon identity, selected-context counts and whole-field counts.
     * @param jw JSON writer inside an object
     * @param terms partitioned cooccurrence terms
     * @param id dense term id
     * @throws IOException if writing fails
     */
    private static void identity(final JsonWriter jw, final FocusPivots terms, final int id) throws IOException
    {
        jw.name("id").value(id);
        jw.name("form").value(terms.lexicon().form(id));
        jw.name("freq").value(terms.freq(id));
        jw.name("docs").value(terms.docs(id));
        jw.name("contexts").value(terms.contexts(id));
        jw.name("fieldFreq").value(terms.stats().termFreq(id));
        jw.name("fieldDocs").value(terms.stats().termDocs(id));
    }

    /**
     * Writes counts in the same order as the population definitions.
     * @param jw JSON writer inside a pivot or term object
     * @param terms partitioned cooccurrence terms
     * @param id dense term id
     * @param ranked whether to include ranking statistics
     * @throws IOException if writing fails
     */
    private static void termPopulations(final JsonWriter jw, final FocusPivots terms, final int id,
            final boolean ranked) throws IOException
    {
        jw.name("populations").beginArray();
        int p = 0;
        for (final FocusPivots.Population pop : terms.populations()) {
            jw.beginObject();
            jw.name("freq").value(pop.terms().termFreq(id));
            jw.name("docs").value(pop.terms().docs(id));
            jw.name("contexts").value(pop.terms().contexts(id));
            if (ranked) {
                rank(jw, terms.rank(p, id));
                jw.name("score");
                number(jw, terms.score(p, id));
            }
            jw.endObject();
            p++;
        }
        jw.endArray();
    }

    /**
     * Writes a number, or {@code null} when it is not finite, which JSON
     * cannot represent.
     *
     * @param jw JSON writer positioned after a name
     * @param value number to write
     * @throws IOException if writing fails
     */
    static void number(final JsonWriter jw, final double value) throws IOException
    {
        if (Double.isFinite(value)) jw.value(value);
        else jw.nullValue();
    }

    /**
     * Writes a 1-based rank as {@code "rank"}, or {@code null} for 0.
     *
     * @param jw JSON writer inside an object
     * @param rank 1-based rank, 0 when absent
     * @throws IOException if writing fails
     */
    private static void rank(final JsonWriter jw, final int rank) throws IOException
    {
        jw.name("rank");
        if (rank > 0) jw.value(rank);
        else jw.nullValue();
    }
}
