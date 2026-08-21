package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Path;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.lucene.vecs.VecMap;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.ALIX_META;
import static com.github.oeuvres.alix.web.Pars.ALIX_PARS;

/**
 * Produces the two-dimensional factor map from TopTerms for a query,
 * from a pregenerated word2vec like model.
 */
public class OpVecMap extends Op
{
    /**
     * Writes the selected-term factor map as compact JSON.
     *
     * @param lucene Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access or response writing fails
     */
    @Override
    protected void json(
        final LuceneIndex lucene,
        final HttpServletRequest request,
        final HttpServletResponse response) throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final TopTerms topTerms = OpTerms.topTerms(lucene, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            meta.log("[no term selection]");
            AlixServlet.jsonError(request, response);
            return;
        }
        FlucText contentFluc = contentFluc(lucene, pars, meta);
        // Path path = Path.of("models/piaget,dims300,window30,iter20,negative10,alpha0.025.bin");
        Path path = Path.of("models/piaget-word2vec-coocs50-power0.5-stop2-dims300.bin");
        VecModel vecModel = VecModel.get(path);
        if (vecModel == null) {
            response.setStatus(400);
            meta.log("[no model for this field]");
            AlixServlet.jsonError(request, response);
            return;
        }
        VecMap map = new VecMap();
        // add pivots to the map
        for (final TopTerms.ExcludedTerm pivot : topTerms.excludedTerms()) {
            if (vecModel.id(pivot.form()) < 0) {
                continue;
            }
            map.add(pivot.form());
        }

        for (final TermEntry term : topTerms) {
            if (vecModel.id(term.form()) < 0) {
                continue;
            }
            map.add(term.form());
        }
        // what could be done if term not in model? Out of sync model, or not in 10000.
        map.distances(vecModel);
        map.layout();
        
        try (JsonWriter json = new JsonWriter(response.getWriter())) {
            json.beginObject();
            json.name("meta");
            json.beginObject();
            meta.toJson(json, pars);
            json.endObject();
            json.name("data");
            json.beginObject();
            json.name("axes");
            json.beginObject();
            // what kind of good name here fo info?
            json.endObject();

            json.name("nodes");
            json.beginArray();
            
            for (final TopTerms.ExcludedTerm pivot : topTerms.excludedTerms()) {
                final int index = map.index(pivot.form());
                if (index < 0) {
                    continue;
                }
                final VecMap.Point point = map.point(index);
                json.beginObject();
                json.name("form").value(point.key());
                json.name("x").value(round(point.x(), 4));
                json.name("y").value(round(point.y(), 4));
                json.name("quality").value(round(point.quality(), 4));
                json.name("type").value("pivot");
                json.endObject();
            }
            for (final TermEntry term : topTerms) {
                final int index = map.index(term.form());
                if (index < 0) {
                    continue;
                }
                final VecMap.Point point = map.point(index);
                json.beginObject();
                json.name("form").value(point.key());
                json.name("x").value(round(point.x(), 4));
                json.name("y").value(round(point.y(), 4));
                json.name("quality").value(round(point.quality(), 4));
                json.name("freq").value(term.freq());
                json.name("score").value(round(term.score(), 4));
                json.endObject();
            }
            

            json.endArray();
            json.endObject();
            json.endObject();
        }
    }

}
