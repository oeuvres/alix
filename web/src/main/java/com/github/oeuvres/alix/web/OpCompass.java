package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.vecs.CompassVec;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Produces a two-dimensional map of the {@link TopTerms} selected for a query.
 *
 * <p>By default, the PCA plane is fitted to the selected TopTerms, without
 * including query pivots in the fit. The request parameter {@code plane=local}
 * instead fits the PCA plane to the {@link #NEIGHBORS} model words nearest to
 * the query pivots. Both modes project pivots and TopTerms into their
 * respective planes and use the same JSON node format.
 * {@code layout=pca|mds|centred} selects the layout (default {@code pca};
 * the former {@code mds=true} still selects {@code mds}).</p>
 *
 * <p>Two sources are kept apart. The model gives positions,
 * {@code pivotDistance} and {@code neighbors}. The query contexts, the same
 * contexts as the TopTerms selection, give {@code freq}, {@code contexts} and
 * {@code coocs}; their number is {@code focusContexts} in the meta.</p>
 *
 * <p>The JSON {@code data} holds:</p>
 * <ul>
 * <li>{@code axes}: the settings of the map;</li>
 * <li>{@code nodes}: pivots, then TopTerms, each with its vector {@code id}
 * and the number of {@code contexts} containing it; a TopTerms node also
 * carries {@code pivotDistance}, its model cosine distance to the nearest
 * pivot;</li>
 * <li>{@code coocs}: for each pair of displayed TopTerms present together in
 * at least one context, the number of those {@code contexts}, most frequent
 * pair first; {@code source} and {@code target} are node ids;</li>
 * <li>{@code neighbors}: for each pivot, the {@link #NEAREST} words of the
 * whole model most similar to it (see {@link VecModel#nearest(int[], int)}).
 * These words are taken from the model, not from the query contexts: they
 * need not be on the map.</li>
 * </ul>
 */
public class OpCompass extends Op
{
    /** Property for the directory of models */
    private static final String ALIX_MODELS_ROOT = "alix.models.root";
    /** Similar words listed for each query word, a display choice. */
    private static final int NEAREST = 10;
    /**
     * Number of model words nearest to the query pivots that define the PCA
     * plane with {@code plane=local}. They fit the plane and are not
     * displayed. Unused with the default keyword plane.
     */
    private static final int NEIGHBORS = 300;

    /** Vector model used by the current experiment. */
    private static String modelDefault;

    /**
     * Answers a request that cannot be served: status 400, the reason logged
     * in the meta, the JSON error document.
     *
     * @param request HTTP request
     * @param response HTTP response
     * @param meta meta of the request
     * @param message reason, shown to the client
     * @throws IOException if response writing fails
     * @throws ServletException if the error document cannot be produced
     */
    private static void badRequest(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final MetaUtil meta,
        final String message
    ) throws IOException, ServletException {
        response.setStatus(400);
        meta.log(message);
        AlixServlet.jsonError(request, response);
    }

    /**
     * Counts, for the displayed TopTerms, the query contexts in which each
     * term and each pair of terms are present. This is a second walk of the
     * contexts walked by {@link OpTerms#topTerms}, with the same query,
     * filter and margins, read from the same parameters.
     *
     * @param lucene Lucene index
     * @param pars resolved parameters
     * @param meta meta of the request
     * @param entries TopTerms entry of each displayed term, null for a pivot
     * @return square matrix by term id: a cell is the number of contexts
     *         containing both terms, an identity cell the number of contexts
     *         containing the term, the total the number of contexts; null
     *         without a span query or with fewer than two terms
     * @throws IOException if index access fails
     */
    private static IntMatrixById coocMatrix(
        final LuceneIndex lucene,
        final HttpPars pars,
        final MetaUtil meta,
        final List<TermEntry> entries
    ) throws IOException {
        final List<Integer> termIds = new ArrayList<>();
        for (final TermEntry term : entries) {
            if (term != null) {
                termIds.add(term.termId());
            }
        }
        if (termIds.size() < 2) {
            return null;
        }
        final FlucText contentFluc = contentFluc(lucene, pars, meta);
        final SpanQuery spanQuery = spanQuery(lucene, pars, meta);
        if (contentFluc == null || spanQuery == null) {
            return null;
        }
        final Query filterQuery = filterQuery(lucene, pars, meta);
        // same as in OpTerms.topTerms
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);

        final int[] ids = termIds.stream().mapToInt(Integer::intValue).toArray();
        final IntMatrixById mat = new IntMatrixById(ids, ids, null);
        final SpanWalker walker = new SpanWalker(
            lucene.searcher(),
            spanQuery,
            new DocSnippets(DocSnippets.Usage.POSITIONS, slop),
            filterQuery
        );
        walker.walk(new CoocMatSnippets(
            mat,
            contentFluc.termRail(),
            left,
            right,
            false,
            CoocMatSnippets.Counting.CONTEXTS));
        return mat;
    }

    /**
     * Writes the array of the contexts shared by two displayed TopTerms, see
     * {@link #coocMatrix}: most frequent pair first, pairs never found
     * together omitted.
     *
     * @param json writer, positioned after the name of the array
     * @param mat context counts by term id, or null for an empty array
     * @param entries TopTerms entry of each displayed term, null for a pivot
     * @param nodeIds vector ids of the displayed terms, aligned with entries
     * @throws IOException if response writing fails
     */
    private static void coocs(
        final JsonWriter json,
        final IntMatrixById mat,
        final List<TermEntry> entries,
        final int[] nodeIds
    ) throws IOException {
        final List<int[]> pairs = new ArrayList<>();
        if (mat != null) {
            for (int a = 0; a < nodeIds.length; a++) {
                if (entries.get(a) == null) {
                    continue;
                }
                for (int b = a + 1; b < nodeIds.length; b++) {
                    if (entries.get(b) == null) {
                        continue;
                    }
                    final int count = mat.count(entries.get(a).termId(), entries.get(b).termId());
                    if (count > 0) {
                        pairs.add(new int[] { a, b, count });
                    }
                }
            }
            pairs.sort((x, y) -> Integer.compare(y[2], x[2]));
        }
        json.beginArray();
        for (final int[] pair : pairs) {
            json.beginObject();
            json.name("source").value(nodeIds[pair[0]]);
            json.name("target").value(nodeIds[pair[1]]);
            json.name("contexts").value(pair[2]);
            json.endObject();
        }
        json.endArray();
    }

    /**
     * Writes query pivots and TopTerms in the selected plane, the contexts
     * shared by displayed terms, and the model words most similar to each
     * query word.
     *
     * @param lucene Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access, model loading, or response writing fails
     * @throws ServletException if the models root is not configured
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
            badRequest(request, response, meta, "[no term selection]");
            return;
        }
        final Path modelsRoot = modelsRoot(request);
        final String modelName = pars.getString("model", modelDefault);
        final VecModel model = VecModel.get(modelsRoot.resolve(modelName));

        final List<TopTerms.ExcludedTerm> pivots = new ArrayList<>();
        for (final TopTerms.ExcludedTerm pivot : topTerms.excludedTerms()) {
            if (model.id(pivot.form()) >= 0) {
                pivots.add(pivot);
            }
        }
        if (pivots.isEmpty()) {
            badRequest(request, response, meta, "[no query pivot found in vector model]");
            return;
        }
        final int[] pivotIds = new int[pivots.size()];
        for (int pivot = 0; pivot < pivotIds.length; pivot++) {
            pivotIds[pivot] = model.id(pivots.get(pivot).form());
        }

        final String plane = request.getParameter("plane");
        if (plane != null && !"local".equals(plane) && !"keywords".equals(plane)) {
            badRequest(request, response, meta, "[unknown PCA plane: " + plane + "]");
            return;
        }
        final boolean keywords = !"local".equals(plane);

        final String layoutName = pars.getString(
            "layout", pars.getBoolean("mds", false) ? "mds" : "pca");
        final CompassVec.Layout layout = switch (layoutName) {
            case "pca" -> CompassVec.Layout.PCA;
            case "mds" -> CompassVec.Layout.MDS;
            case "centred" -> CompassVec.Layout.MDS_CENTRED;
            default -> null;
        };
        if (layout == null) {
            badRequest(request, response, meta, "[unknown layout: " + layoutName + "]");
            return;
        }

        final CompassVec compass;
        if (keywords) {
            final List<Integer> keywordIds = new ArrayList<>();
            for (final TermEntry term : topTerms) {
                final int id = model.id(term.form());
                if (id >= 0) {
                    keywordIds.add(id);
                }
            }
            if (keywordIds.size() < 3) {
                badRequest(request, response, meta,
                    "[keyword PCA requires at least three words in the vector model]");
                return;
            }
            compass = new CompassVec(
                model, keywordIds.stream().mapToInt(Integer::intValue).toArray(), layout);
        }
        else {
            compass = new CompassVec(model, pivotIds, NEIGHBORS, layout);
        }

        // Displayed nodes: pivots first, with a null entry, then TopTerms.
        final List<CompassVec.Point> points = new ArrayList<>();
        final List<TermEntry> entries = new ArrayList<>();
        for (final TopTerms.ExcludedTerm pivot : pivots) {
            points.add(compass.point(pivot.form()));
            entries.add(null);
        }
        for (final TermEntry term : topTerms) {
            final CompassVec.Point point = compass.point(term.form());
            if (point == null) {
                continue;
            }
            points.add(point);
            entries.add(term);
        }
        final int[] nodeIds = new int[points.size()];
        for (int node = 0; node < nodeIds.length; node++) {
            nodeIds[node] = points.get(node).id();
        }

        final double[] pivotDistances = compass.pivotDistances(nodeIds, pivotIds);
        final IntMatrixById coocMatrix = coocMatrix(lucene, pars, meta, entries);

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
            json.name("unit").value(layout == CompassVec.Layout.PCA ? "vector-projection" : "model-distance");
            json.name("plane").value(keywords ? "keywords" : "local");
            json.name("layout").value(layoutName);
            json.name("reference").value(compass.referenceCount());
            json.endObject();

            json.name("nodes");
            json.beginArray();
            for (int node = 0; node < points.size(); node++) {
                final CompassVec.Point point = points.get(node);
                final TermEntry term = entries.get(node);
                json.beginObject();
                json.name("id").value(point.id());
                json.name("form").value(point.form());
                json.name("x").value(round(point.x(), 4));
                json.name("y").value(round(point.y(), 4));
                json.name("quality").value(round(point.quality(), 4));
                if (term == null) {
                    // pivots are the first nodes, in the order of the list
                    json.name("type").value("pivot");
                    json.name("contexts").value(pivots.get(node).contexts());
                }
                else {
                    json.name("freq").value(term.freq());
                    json.name("contexts").value(term.contexts());
                    json.name("score").value(round(term.score(), 4));
                    json.name("pivotDistance").value(round(pivotDistances[node], 4));
                }
                json.endObject();
            }
            json.endArray();

            json.name("coocs");
            coocs(json, coocMatrix, entries, nodeIds);

            json.name("neighbors");
            neighbors(json, model, pivotIds);

            json.endObject();
            json.endObject();
        }
    }

    /**
     * Returns the configured models root directory.
     *
     * @param request HTTP request
     * @return models root directory
     * @throws IOException if the parameter does not designate a directory, or
     *         the directory holds no model
     * @throws ServletException if the parameter is missing
     */
    private static Path modelsRoot(final HttpServletRequest request) throws IOException, ServletException
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
        Path model = Dir.firstFile(path, "*.bin");
        if (model == null) {
            throw new IOException(ALIX_MODELS_ROOT + ": no model *.bin found");
        }
        modelDefault = model.getFileName().toString();
        context.setAttribute(ALIX_MODELS_ROOT, path);
        return path;
    }

    /**
     * Writes the array of the model words most similar to each query pivot:
     * one entry per pivot, with its {@code form} and its {@code terms}, most
     * similar first, each with its {@code form} and {@code distance}.
     *
     * @param json writer, positioned after the name of the array
     * @param model vector model
     * @param pivotIds vector ids of the query pivots
     * @throws IOException if response writing fails
     */
    private static void neighbors(
        final JsonWriter json,
        final VecModel model,
        final int[] pivotIds
    ) throws IOException {
        json.beginArray();
        for (final int pivotId : pivotIds) {
            json.beginObject();
            json.name("form").value(model.word(pivotId));
            json.name("terms");
            json.beginArray();
            for (final VecModel.Neighbor neighbor : model.nearest(new int[] { pivotId }, NEAREST)) {
                json.beginObject();
                json.name("form").value(neighbor.word());
                json.name("distance").value(round(neighbor.distance(), 4));
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }
}
