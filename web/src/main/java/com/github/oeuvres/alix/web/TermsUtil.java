package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

import com.github.oeuvres.alix.lucene.terms.PoleTerms;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;


public class TermsUtil
{
    /**
     * Writes a pole comparison as plain text: rank, term, then for each pole
     * its score and occurrence count, an asterisk marking affiliation.
     *
     * @param request servlet request carrying meta and parameters
     * @param response servlet response
     * @param poles fused pole terms
     * @throws IOException if writing fails
     */
    static void txt(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final PoleTerms poles
    ) throws IOException {
        final Writer writer = response.getWriter();
        final MetaUtil meta = (MetaUtil)request.getAttribute(ALIX_META);
        meta.toString(writer, (HttpPars)request.getAttribute(ALIX_PARS));
        final int poleCount = poles.poleCount();
        for (int pole = 0; pole < poleCount; pole++) {
            writer.append(String.valueOf(pole + 1)).append(" = ").append(poles.label(pole)).append("\n");
        }
        for (int rank = 0; rank < poles.size(); rank++) {
            final int termId = poles.termId(rank);
            writer.append(String.valueOf(rank + 1)).append(". ")
            .append(poles.pole(0).lexicon().form(termId))
            .append(" (");
            for (int pole = 0; pole < poleCount; pole++) {
                if (pole > 0) writer.append(" | ");
                final double score = poles.score(rank, pole);
                writer.append(Double.isFinite(score) ? String.format(Locale.FRANCE, "%.5g", score) : "–")
                .append(" ; ")
                .append(String.valueOf(poles.pole(pole).termFreq(termId)));
                if (poles.affiliated(rank, pole)) writer.append("*");
            }
            writer.append("), ");
        }
    }

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
     * Writes the terms of one or several poles as JSON.
     *
     * <pre>
     * { "meta": { … },
     *   "data": {
     *     "poles":  [ { "label", "docs", "contexts", "tokens", "pivotCount" } ],
     *     "pivots": [ { "id", "form", "fieldFreq", "fieldDocs", "pole", "poles": [ PoleStats ] } ],
     *     "terms":  [ { "rank", "id", "form", "fieldFreq", "fieldDocs", "poles": [ PoleStats ] } ] } }
     *
     * PoleStats = { "rank", "freq", "docs", "contexts", "score", "affiliated" }
     * </pre>
     *
     * <p>
     * The index of a pole in {@code poles} is its number; every
     * {@code poles} array of a pivot or a term has one entry per pole, in the
     * same order. A pivot entry for its own pole gives its counts in its own
     * contexts, with no score and no rank. A pivot entry for another pole is
     * the attraction between the two poles, with the rank the pivot would take
     * in that pole list. {@code rank} and {@code score} are {@code null} when
     * absent; {@code affiliated} means a score above independence.
     * </p>
     *
     * @param request servlet request carrying meta and parameters
     * @param response servlet response
     * @param poles pole terms
     * @throws IOException if writing fails
     */
    static void json(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final PoleTerms poles
    ) throws IOException {
        final JsonWriter jw = new JsonWriter(response.getWriter());
        jw.setIndent("  ");
        final MetaUtil meta = (MetaUtil)request.getAttribute(ALIX_META);
        final int poleCount = poles.poleCount();
        jw.beginObject();

        jw.name("meta");
        jw.beginObject();
        meta.toJson(jw, (HttpPars)request.getAttribute(ALIX_PARS));
        jw.endObject(); // meta

        jw.name("data");
        jw.beginObject();
        jw.name("poles");
        poles(jw, poles);

        jw.name("pivots");
        jw.beginArray();
        for (int owner = 0; owner < poleCount; owner++) {
            for (final TopTerms.ExcludedTerm pivot : poles.pole(owner).excludedTerms()) {
                jw.beginObject();
                pivotFields(jw, owner, pivot);
                jw.name("poles");
                jw.beginArray();
                for (int pole = 0; pole < poleCount; pole++) {
                    jw.beginObject();
                    pivotPoleFields(jw, poles, owner, pivot, pole);
                    jw.endObject();
                }
                jw.endArray();
                jw.endObject();
            }
        }
        jw.endArray(); // pivots

        jw.name("terms");
        jw.beginArray();
        for (int rank = 0; rank < poles.size(); rank++) {
            jw.beginObject();
            termFields(jw, poles, rank);
            jw.name("poles");
            jw.beginArray();
            for (int pole = 0; pole < poleCount; pole++) {
                jw.beginObject();
                termPoleFields(jw, poles, rank, pole);
                jw.endObject();
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray(); // terms
        jw.endObject(); // data

        jw.endObject();
    }

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
            int rank = 1;
            for (TermEntry term : terms) {
                jw.beginObject();
                jw.name("rank").value(rank++);
                jw.name("form").value(term.form());
                jw.name("id").value(term.termId());
                jw.name("html").value(term.hilite()); // for suggest
                jw.name("docs").value(term.docs());
                if (hasContexts) jw.name("contexts").value(term.contexts());
                jw.name("fieldDocs").value(term.fieldDocs());
                jw.name("freq").value(term.freq());
                jw.name("fieldFreq").value(term.fieldFreq());
                jw.name("score").value(term.score()); // for terms
                jw.endObject();
            }
            jw.endArray(); // terms
            jw.endObject(); // data
        }

        jw.endObject();
    }

    /**
     * Writes the fields of a pivot common to every output: {@code id},
     * {@code form}, {@code fieldFreq}, {@code fieldDocs} and its owning
     * {@code pole}. The caller opens and closes the object.
     *
     * @param jw JSON writer inside an object
     * @param owner index of the pole of the pivot
     * @param pivot pivot snapshot
     * @throws IOException if writing fails
     */
    static void pivotFields(final JsonWriter jw, final int owner, final TopTerms.ExcludedTerm pivot) throws IOException
    {
        jw.name("id").value(pivot.termId());
        jw.name("form").value(pivot.form());
        jw.name("fieldFreq").value(pivot.fieldFreq());
        jw.name("fieldDocs").value(pivot.fieldDocs());
        jw.name("pole").value(owner);
    }

    /**
     * Writes the statistics of a pivot in one pole: {@code rank},
     * {@code freq}, {@code docs}, {@code contexts}, {@code score},
     * {@code affiliated}. In its own pole, the counts are those of its own
     * contexts, without score or rank; in another pole, the score is the
     * attraction and the rank the place it would take in that pole list.
     * The caller opens and closes the object.
     *
     * @param jw JSON writer inside an object
     * @param poles pole terms
     * @param owner index of the pole of the pivot
     * @param pivot pivot snapshot
     * @param pole index of the pole described
     * @throws IOException if writing fails
     */
    static void pivotPoleFields(
        final JsonWriter jw,
        final PoleTerms poles,
        final int owner,
        final TopTerms.ExcludedTerm pivot,
        final int pole
    ) throws IOException {
        if (pole == owner) {
            jw.name("rank").nullValue();
            jw.name("freq").value(pivot.freq());
            jw.name("docs").value(pivot.docs());
            jw.name("contexts").value(pivot.contexts());
            jw.name("score").nullValue();
            jw.name("affiliated").value(false);
            return;
        }
        final TopTerms terms = poles.pole(pole);
        final int termId = pivot.termId();
        final double score = poles.pivotScore(pole, termId);
        rank(jw, poles.pivotRank(pole, termId));
        jw.name("freq").value(terms.termFreq(termId));
        jw.name("docs").value(terms.docs(termId));
        jw.name("contexts").value(terms.contexts(termId));
        jw.name("score");
        number(jw, score);
        jw.name("affiliated").value(score > poles.neutral());
    }

    /**
     * Writes the array of the poles: {@code label}, {@code docs},
     * {@code contexts}, {@code tokens} (pivots excluded) and
     * {@code pivotCount} of each pole population.
     *
     * @param jw JSON writer positioned after a name
     * @param poles pole terms
     * @throws IOException if writing fails
     */
    static void poles(final JsonWriter jw, final PoleTerms poles) throws IOException
    {
        jw.beginArray();
        for (int pole = 0; pole < poles.poleCount(); pole++) {
            final TopTerms terms = poles.pole(pole);
            jw.beginObject();
            jw.name("label").value(poles.label(pole));
            jw.name("docs").value(terms.docs());
            jw.name("contexts").value(terms.contexts());
            jw.name("tokens").value(terms.tokens());
            jw.name("pivotCount").value(terms.pivotCount());
            jw.endObject();
        }
        jw.endArray();
    }

    /**
     * Writes the fields of a fused term common to every output:
     * {@code rank} (1-based), {@code id}, {@code form}, {@code fieldFreq},
     * {@code fieldDocs}. The caller opens and closes the object.
     *
     * @param jw JSON writer inside an object
     * @param poles pole terms
     * @param rank zero-based fused rank
     * @throws IOException if writing fails
     */
    static void termFields(final JsonWriter jw, final PoleTerms poles, final int rank) throws IOException
    {
        final int termId = poles.termId(rank);
        final TermStats termStats = poles.pole(0).termStats();
        jw.name("rank").value(rank + 1);
        jw.name("id").value(termId);
        jw.name("form").value(poles.pole(0).lexicon().form(termId));
        jw.name("fieldFreq").value(termStats.termFreq(termId));
        jw.name("fieldDocs").value(termStats.termDocs(termId));
    }

    /**
     * Writes the statistics of a fused term in one pole: {@code rank} in the
     * pole list, {@code freq}, {@code docs}, {@code contexts},
     * {@code score}, {@code affiliated}. The caller opens and closes the
     * object.
     *
     * @param jw JSON writer inside an object
     * @param poles pole terms
     * @param rank zero-based fused rank
     * @param pole pole index
     * @throws IOException if writing fails
     */
    static void termPoleFields(
        final JsonWriter jw,
        final PoleTerms poles,
        final int rank,
        final int pole
    ) throws IOException {
        final TopTerms terms = poles.pole(pole);
        final int termId = poles.termId(rank);
        rank(jw, poles.poleRank(rank, pole));
        jw.name("freq").value(terms.termFreq(termId));
        jw.name("docs").value(terms.docs(termId));
        jw.name("contexts").value(terms.contexts(termId));
        jw.name("score");
        number(jw, poles.score(rank, pole));
        jw.name("affiliated").value(poles.affiliated(rank, pole));
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
