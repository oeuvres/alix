package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.vecs.CompassVec;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.ALIX_META;
import static com.github.oeuvres.alix.web.Pars.ALIX_PARS;

/**
 * Produces a query-centred two-dimensional map of the {@link TopTerms} selected
 * for a query, using a stable local compass defined by nearest model vectors.
 *
 * <p>The query pivots and {@link TopTerms} are selected exactly as for
 * {@link OpVecMap}. The nearest model vectors are used only to define and orient
 * the two-dimensional compass; they are not emitted. The JSON node list contains
 * the pivots followed by the selected {@link TopTerms}, so the existing map
 * client can render the response without a compass-specific node format.</p>
 */
public class OpCompass extends Op
{
    /** Property for the directory of models */
    private static final String ALIX_MODELS_ROOT="alix.models.root";
    /** Number of nearest model vectors used to define the compass. */
    private static final int NEIGHBORS = 300;

    /** Vector model used by the current experiment. */
    private static final String MODEL = "piaget-content-coocs30-g2specif1.0-dims200.bin"; // best model
    
    /**
     * Returns the configured models root directory.
     *
     * @param request HTTP request
     * @return models root directory
     * @throws IOException if the parameter is missing or does not designate a directory
     * @throws ServletException 
     */
    private static Path modelRoot(final HttpServletRequest request) throws IOException, ServletException
    {
        final ServletContext context = request.getServletContext();

        final Object cached = context.getAttribute(ALIX_MODELS_ROOT);
        if (cached instanceof Path path) {
            return path;
        }
        final String value = HttpPars.requiresInitParameter(context, ALIX_MODELS_ROOT);
        final Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException(ALIX_MODELS_ROOT + " is not a directory: " + path);
        }
        context.setAttribute(ALIX_MODELS_ROOT, path);
        return path;
    }

    /**
     * Writes the query TopTerms projected into the nearest-neighbour compass.
     *
     * @param lucene Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access, model loading, or response writing fails
     * @throws ServletException 
     */
    @Override
    protected void json(
        final LuceneIndex lucene,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException, ServletException {
        
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final TopTerms topTerms = OpTerms.topTerms(lucene, pars, meta);
        if (topTerms == null) {
            response.setStatus(400);
            meta.log("[no term selection]");
            AlixServlet.jsonError(request, response);
            return;
        }
        final VecModel model = VecModel.get(modelRoot(request).resolve(MODEL));
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
            json.setIndent("  ");
            json.beginObject();
            json.name("meta");
            json.beginObject();
            meta.toJson(json, pars);
            json.endObject();

            json.name("data");
            json.beginObject();
            json.name("axes");
            json.beginObject();
            json.name("unit").value("vector-projection");
            json.name("reference").value(compass.referenceCount());
            json.name("xCenter").value(round(compass.xCenter(), 4));
            json.name("yCenter").value(round(compass.yCenter(), 4));
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

            for (final TermEntry term : topTerms) {
                final CompassVec.Point point = compass.point(term.form());
                if (point == null) {
                    continue;
                }
                json.beginObject();
                json.name("form").value(point.form());
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
