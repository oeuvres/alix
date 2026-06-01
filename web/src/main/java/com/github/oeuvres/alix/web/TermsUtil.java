package com.github.oeuvres.alix.web;

import java.io.IOException;

import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.Op.OpMeta;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletResponse;

public class TermsUtil
{

    static void json(
        final HttpServletResponse response,
        final OpMeta meta,
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

            // data
            if (terms != null) {
                jw.name("data");
                jw.beginArray();
                int rank = 1;
                for (TermEntry term : terms) {
                    jw.beginObject();
                    jw.name("rank").value(rank++);
                    jw.name("form").value(term.form());
                    jw.name("html").value(term.hilite()); // for suggest
                    jw.name("docs").value(term.docs());
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
