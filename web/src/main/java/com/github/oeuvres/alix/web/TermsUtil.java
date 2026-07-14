package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletResponse;

public class TermsUtil
{
    static void txt(
        final HttpServletResponse response,
        final MetaUtil meta,
        final HttpPars pars,
        TopTerms terms
    ) throws IOException {
        int rank = 1;
        Writer writer = response.getWriter();
        for (TermEntry term : terms) {
            if (term.isPromoted()) {
                writer
                .append(term.form())
                .append(" (")
                .append(String.valueOf(term.freq()))
                .append("), ");
                continue;
            }
            else {
                writer.append(String.valueOf(rank++)).append(". ")
                .append(term.form())
                .append(" (")
                .append(String.format(Locale.FRANCE, "%.5g", term.score()))
                .append(" ; ")
                .append(String.valueOf(term.freq()))
                .append("), ");
            }
        }
    }

    static void json(
        final HttpServletResponse response,
        final MetaUtil meta,
        final HttpPars pars,
        TopTerms terms
    ) throws IOException {
        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();

            // meta
            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
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
                    jw.name("id").value(pivot.termId());
                    jw.name("docs").value(pivot.docs());
                    jw.name("snippets").value(pivot.contexts());
                    jw.name("freq").value(pivot.freq());
                    jw.name("fieldDocs").value(pivot.fieldDocs());
                    jw.name("fieldFreq").value(pivot.fieldFreq());
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
                    jw.name("snippets").value(term.contexts());
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
    }
}
