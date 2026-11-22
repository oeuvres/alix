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
     * Writes a pole comparison as JSON.
     *
     * <p>
     * Besides {@code meta}, a {@code poles} array describes each pole: label,
     * focus totals, and {@code attraction}, the scores of the other poles'
     * pivots in its contexts. In {@code data}, pivots come first, tagged by
     * {@code pole}, as for a single population. Each ranked term then carries
     * its field statistics, {@code affiliation} (indices of the poles where its
     * score exceeds independence), and per-pole {@code freq}, {@code docs},
     * {@code contexts} and {@code score}. Top-level {@code freq} is the sum of
     * the pole counts and top-level {@code score} the highest pole score, for
     * clients that expect one value per term; scores of different poles are
     * comparable only for ratio scorers, not for test statistics. Non-finite
     * scores are written as {@code null}.
     * </p>
     *
     * @param request servlet request carrying meta and parameters
     * @param response servlet response
     * @param poles fused pole terms
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
        final TermStats termStats = poles.pole(0).termStats();
        jw.beginObject();

        jw.name("meta");
        jw.beginObject();
        meta.toJson(jw, (HttpPars)request.getAttribute(ALIX_PARS));
        jw.endObject(); // meta

        jw.name("poles");
        jw.beginArray();
        for (int pole = 0; pole < poleCount; pole++) {
            final TopTerms terms = poles.pole(pole);
            jw.beginObject();
            jw.name("pole").value(pole);
            jw.name("label").value(poles.label(pole));
            jw.name("docs").value(terms.docs());
            jw.name("contexts").value(terms.contexts());
            jw.name("tokens").value(terms.tokens());
            jw.name("pivotCount").value(terms.pivotCount());
            jw.name("attraction");
            jw.beginArray();
            final int[] ids = poles.crossPivotIds(pole);
            final double[] scores = poles.crossPivotScores(pole);
            for (int i = 0; i < ids.length; i++) {
                jw.beginObject();
                jw.name("form").value(terms.lexicon().form(ids[i]));
                jw.name("id").value(ids[i]);
                jw.name("freq").value(terms.termFreq(ids[i]));
                jw.name("score");
                number(jw, scores[i]);
                jw.endObject();
            }
            jw.endArray(); // attraction
            jw.endObject();
        }
        jw.endArray(); // poles

        jw.name("data");
        jw.beginArray();
        for (int pole = 0; pole < poleCount; pole++) {
            for (final TopTerms.ExcludedTerm pivot : poles.pole(pole).excludedTerms()) {
                jw.beginObject();
                jw.name("form").value(pivot.form());
                jw.name("type").value("pivot");
                jw.name("pole").value(pole);
                jw.name("docs").value(pivot.docs());
                jw.name("contexts").value(pivot.contexts());
                jw.name("freq").value(pivot.freq());
                jw.name("fieldDocs").value(pivot.fieldDocs());
                jw.name("fieldFreq").value(pivot.fieldFreq());
                jw.name("id").value(pivot.termId());
                jw.endObject();
            }
        }
        for (int rank = 0; rank < poles.size(); rank++) {
            final int termId = poles.termId(rank);
            long freq = 0L;
            double best = Double.NaN;
            for (int pole = 0; pole < poleCount; pole++) {
                freq += poles.pole(pole).termFreq(termId);
                final double score = poles.score(rank, pole);
                if (Double.isFinite(score) && !(score <= best)) best = score;
            }
            jw.beginObject();
            jw.name("rank").value(rank + 1);
            jw.name("form").value(poles.pole(0).lexicon().form(termId));
            jw.name("id").value(termId);
            jw.name("fieldDocs").value(termStats.termDocs(termId));
            jw.name("fieldFreq").value(termStats.termFreq(termId));
            jw.name("freq").value(freq);
            jw.name("score");
            number(jw, best);
            jw.name("affiliation");
            jw.beginArray();
            for (int pole = 0; pole < poleCount; pole++) {
                if (poles.affiliated(rank, pole)) jw.value(pole);
            }
            jw.endArray();
            jw.name("poles");
            jw.beginArray();
            for (int pole = 0; pole < poleCount; pole++) {
                final TopTerms terms = poles.pole(pole);
                jw.beginObject();
                jw.name("freq").value(terms.termFreq(termId));
                jw.name("docs").value(terms.docs(termId));
                jw.name("contexts").value(terms.contexts(termId));
                jw.name("score");
                number(jw, poles.score(rank, pole));
                jw.endObject();
            }
            jw.endArray(); // poles
            jw.endObject();
        }
        jw.endArray(); // data

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
        
        // here, how to 

        // data
        if (terms != null) {
            jw.name("data");
            jw.beginArray();
            
            // loop on pivots
            for (final TopTerms.ExcludedTerm pivot : terms.excludedTerms()) {
                jw.beginObject();
                jw.name("form").value(pivot.form());
                jw.name("type").value("pivot");
                jw.name("docs").value(pivot.docs());
                if (hasContexts) jw.name("contexts").value(pivot.contexts());
                jw.name("freq").value(pivot.freq());
                jw.name("fieldDocs").value(pivot.fieldDocs());
                jw.name("fieldFreq").value(pivot.fieldFreq());
                jw.name("id").value(pivot.termId());
                jw.endObject();
            }
            
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
            jw.endArray();
        }

        jw.endObject();
    }

    /**
     * Writes a number, or {@code null} when it is not finite, which JSON
     * cannot represent.
     *
     * @param jw JSON writer positioned after a name
     * @param value number to write
     * @throws IOException if writing fails
     */
    private static void number(final JsonWriter jw, final double value) throws IOException
    {
        if (Double.isFinite(value)) jw.value(value);
        else jw.nullValue();
    }
}
