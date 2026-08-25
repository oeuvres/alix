package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.vecs.CompassVec;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.ALIX_META;
import static com.github.oeuvres.alix.web.Pars.ALIX_PARS;

/**
 * Produces a query-centred compass map from the nearest vectors of a
 * pregenerated word2vec-like model.
 *
 * <p>The first experiment deliberately plots only the model neighbours plus the
 * query pivots. It does not cache query or layout results. The existing immutable
 * {@link VecModel} model cache is reused.</p>
 */
public class OpCompass extends Op
{
    /** Number of nearest model vectors plotted by the first experiment. */
    private static final int NEIGHBORS = 300;

    /** Vector model used by the current experiment. */
    private static final Path MODEL = Path.of(
        "models/piaget-260825-word2vec-coocs50-g2specif2.0-power0.5-stop2-dims500.bin");

    /**
     * Writes the nearest-neighbour compass as compact JSON.
     *
     * @param lucene Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access, model loading, or response writing fails
     */
    @Override
    protected void json(
        final LuceneIndex lucene,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);

        final TopTerms topTerms = OpTerms.topTerms(lucene, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            meta.log("[no pivot selection]");
            AlixServlet.jsonError(request, response);
            return;
        }

        final VecModel model = VecModel.get(MODEL);
        final List<TopTerms.ExcludedTerm> pivots = new ArrayList<>();
        final List<Integer> pivotIds = new ArrayList<>();
        for (final TopTerms.ExcludedTerm pivot : topTerms.excludedTerms()) {
            final int id = model.id(pivot.form());
            if (id < 0) {
                continue;
            }
            pivots.add(pivot);
            pivotIds.add(id);
        }
        if (pivotIds.isEmpty()) {
            response.setStatus(400);
            meta.log("[no query pivot found in vector model]");
            AlixServlet.jsonError(request, response);
            return;
        }

        final int[] ids = new int[pivotIds.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = pivotIds.get(index);
        }
        final CompassVec compass = new CompassVec(model, ids, NEIGHBORS);

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
            json.endObject();

            json.name("nodes");
            json.beginArray();
            for (final TopTerms.ExcludedTerm pivot : pivots) {
                json.beginObject();
                json.name("form").value(pivot.form());
                json.name("x").value(0d);
                json.name("y").value(0d);
                json.name("quality").value(1d);
                json.name("type").value("pivot");
                json.endObject();
            }
            for (final CompassVec.Point point : compass.points()) {
                json.beginObject();
                json.name("form").value(point.form());
                json.name("x").value(round(point.x(), 4));
                json.name("y").value(round(point.y(), 4));
                json.name("quality").value(round(point.quality(), 4));
                json.endObject();
            }
            json.endArray();
            json.endObject();
            json.endObject();
        }
    }
}
