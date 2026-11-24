package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.FocusPivots;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.vecs.CompassVec;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Projects the selected cooccurrence terms and query pivots into a vector plane.
 * The term statistics are the same as in terms.json. One matrix over the
 * original union contexts gives shared-context counts and signed G² for edges.
 * The model gives coordinates and distance to the nearest query pivot.
 * The plane and layout parameters retain their existing meanings.
 */
public class OpCompass extends Op
{
    /** Similar words listed for each query word, a display choice. */
    private static final int NEAREST = 20;
    /**
     * Number of model words nearest to the query pivots that define the PCA
     * plane with {@code plane=local}. They fit the plane and are not
     * displayed. Unused with the default keyword plane.
     */
    private static final int NEIGHBORS = 300;

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
     * Counts the union contexts in which each placed term and pair occur.
     * This is a second walk of the contexts walked by {@link OpTerms#focusTerms},
     * with the same original query,
     * filter and margins, read from the same parameters.
     *
     * @param lucene Lucene index
     * @param pars resolved parameters
     * @param meta meta of the request
     * @param spanQuery original span query
     * @param termIds lexicon ids of the placed terms
     * @return square matrix by term id: a cell is the number of contexts
     *         containing both terms, an identity cell the number of contexts
     *         containing the term, the total the number of contexts; null
     *         with fewer than two terms
     * @throws IOException if index access fails
     */
    private static IntMatrixById coocMatrix(
        final LuceneIndex lucene,
        final HttpPars pars,
        final MetaUtil meta,
        final SpanQuery spanQuery,
        final int[] termIds
    ) throws IOException {
        if (termIds.length < 2) {
            return null;
        }
        final FlucText contentFluc = contentFluc(lucene, pars, meta);
        if (contentFluc == null) {
            return null;
        }
        final Query filterQuery = filterQuery(lucene, pars, meta);
        // same as in OpTerms.focusTerms
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);

        final IntMatrixById mat = new IntMatrixById(termIds, termIds, null);
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
     * Writes one global edge per pair sharing at least one union context.
     * @param json writer positioned after the array name
     * @param mat union context matrix, or null
     * @param termIds placed term ids
     * @throws IOException if writing fails
     */
    private static void coocs(final JsonWriter json, final IntMatrixById mat,
            final int[] termIds) throws IOException
    {
        json.beginArray();
        if (mat != null && mat.total() > 0L) {
            final KeynessScorer scorer = new KeynessScorer.G2Alpha();
            scorer.corpus(mat.total(), (int) mat.total());
            for (int a = 0; a < termIds.length; a++) {
                scorer.focus(mat.count(termIds[a], termIds[a]), 0d);
                for (int b = a + 1; b < termIds.length; b++) {
                    final int count = mat.count(termIds[a], termIds[b]);
                    if (count == 0) continue;
                    json.beginObject();
                    json.name("source").value(termIds[a]);
                    json.name("target").value(termIds[b]);
                    json.name("contexts").value(count);
                    json.name("score");
                    rounded(json, scorer.score(count, mat.count(termIds[b], termIds[b]), 0));
                    json.endObject();
                }
            }
        }
        json.endArray();
    }

    /**
     * Writes query pivots and terms in the selected plane, with their
     * statistics in each population, the contexts shared by placed terms, and the
     * model words most similar to each pivot.
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
        final FocusPivots terms = OpTerms.focusTerms(lucene, pars, meta);
        if (terms == null) {
            badRequest(request, response, meta, "[no term selection]");
            return;
        }
        final Path modelPath = modelPath(request, pars, meta);
        if (modelPath == null) {
            AlixServlet.jsonError(request, response);
            return;
        }
        final VecModel model = vecModel(modelPath, meta);
        final List<String> notInModel = new ArrayList<>();
        final int[] pivots = terms.pivotIds();
        final List<Integer> pivotVecs = new ArrayList<>();
        for (final int pivot : pivots) {
            final String form = terms.lexicon().form(pivot);
            final int id = modelId(model, form);
            pivotVecs.add(id);
            if (id < 0) notInModel.add(form);
        }
        final int[] allPivotVecIds = pivotVecs.stream().mapToInt(Integer::intValue).filter(id -> id >= 0).distinct().toArray();
        if (allPivotVecIds.length == 0) {
            badRequest(request, response, meta, "[no query pivot found in vector model]");
            return;
        }

        final int termCount = terms.size();
        final int[] termVecs = new int[termCount];
        final List<Integer> keywordIds = new ArrayList<>();
        for (int rank = 0; rank < termCount; rank++) {
            final String form = terms.lexicon().form(terms.termId(rank));
            termVecs[rank] = modelId(model, form);
            if (termVecs[rank] >= 0) {
                keywordIds.add(termVecs[rank]);
            }
            else {
                notInModel.add(form);
            }
        }
        if (!notInModel.isEmpty()) {
            meta.put("notInModel", notInModel.toArray(new String[0]));
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
            if (keywordIds.size() < 3) {
                badRequest(request, response, meta,
                    "[keyword PCA requires at least three words in the vector model]");
                return;
            }
            compass = new CompassVec(
                model, keywordIds.stream().mapToInt(Integer::intValue).toArray(), layout);
        }
        else {
            compass = new CompassVec(model, allPivotVecIds, NEIGHBORS, layout);
        }

        // Points: pivots first, then terms; null outside the model.
        final int pivotCount = pivots.length;
        final CompassVec.Point[] points = new CompassVec.Point[pivotCount + termCount];
        final List<Integer> nodeVecs = new ArrayList<>();
        for (int k = 0; k < pivotCount; k++) {
            final int id = pivotVecs.get(k);
            points[k] = id < 0 ? null : compass.point(model.word(id));
        }
        for (int rank = 0; rank < termCount; rank++) {
            points[pivotCount + rank] = termVecs[rank] < 0 ? null : compass.point(model.word(termVecs[rank]));
        }
        for (final CompassVec.Point point : points) {
            if (point != null) {
                nodeVecs.add(point.id());
            }
        }
        final int[] nodeIds = nodeVecs.stream().mapToInt(Integer::intValue).toArray();

        // Model distance to the nearest pivot of the whole query.
        final double[] distances = new double[points.length];
        final double[] values = nodeIds.length == 0 ? new double[0]
            : compass.pivotDistances(nodeIds, allPivotVecIds);
        int placedNode = 0;
        for (int i = 0; i < points.length; i++) {
            distances[i] = points[i] == null ? Double.NaN : values[placedNode++];
        }

        // One global matrix; no partition-specific edge statistics.
        final List<Integer> placed = new ArrayList<>();
        for (int rank = 0; rank < termCount; rank++) {
            if (points[pivotCount + rank] != null) placed.add(terms.termId(rank));
        }
        final int[] placedIds = placed.stream().mapToInt(Integer::intValue).toArray();
        final IntMatrixById mat = coocMatrix(lucene, pars, meta, terms.query(), placedIds);

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

            TermsUtil.populations(json, terms);

            json.name("pivots").beginArray();
            for (int k = 0; k < pivotCount; k++) {
                json.beginObject();
                TermsUtil.pivotFields(json, terms, pivots[k]);
                point(json, points[k]);
                if (points[k] != null) {
                    json.name("neighbors");
                    neighbors(json, model, pivotVecs.get(k));
                }
                json.endObject();
            }
            json.endArray();

            json.name("terms").beginArray();
            for (int rank = 0; rank < termCount; rank++) {
                final int node = pivotCount + rank;
                json.beginObject();
                TermsUtil.termFields(json, terms, rank);
                point(json, points[node]);
                json.name("pivotDistance");
                rounded(json, distances[node]);
                json.endObject();
            }
            json.endArray();

            json.name("coocs");
            coocs(json, mat, placedIds);

            json.endObject(); // data
            json.endObject();
        }
    }

    /**
     * Returns the model id of a lexicon form: the form as is, then with its
     * spaces written as underscores, a common notation of multiword
     * expressions in vector models.
     *
     * @param model vector model
     * @param form lexicon form
     * @return model id, or {@code -1} when the model has neither form
     */
    private static int modelId(final VecModel model, final String form)
    {
        final int id = model.id(form);
        if (id >= 0 || form.indexOf(' ') < 0) {
            return id;
        }
        return model.id(form.replace(' ', '_'));
    }

    /**
     * Writes the array of the model words most similar to one pivot, most
     * similar first, each with its {@code form} and {@code distance}.
     *
     * @param json writer, positioned after the name of the array
     * @param model vector model
     * @param pivotId vector id of the pivot
     * @throws IOException if response writing fails
     */
    private static void neighbors(
        final JsonWriter json,
        final VecModel model,
        final int pivotId
    ) throws IOException {
        json.beginArray();
        for (final VecModel.Neighbor neighbor : model.nearest(new int[] { pivotId }, NEAREST)) {
            json.beginObject();
            json.name("form").value(neighbor.word());
            json.name("distance").value(round(neighbor.distance(), 4));
            json.endObject();
        }
        json.endArray();
    }

    /**
     * Writes the plane coordinates {@code x}, {@code y} and the
     * {@code quality} of a point, nothing for a word outside the model.
     *
     * @param json writer inside an object
     * @param point point of the map, or null
     * @throws IOException if response writing fails
     */
    private static void point(final JsonWriter json, final CompassVec.Point point) throws IOException
    {
        if (point == null) {
            return;
        }
        json.name("x").value(round(point.x(), 4));
        json.name("y").value(round(point.y(), 4));
        json.name("quality").value(round(point.quality(), 4));
    }

    /**
     * Writes a number rounded to 4 decimals, or {@code null} when it is not
     * finite.
     *
     * @param json writer positioned after a name
     * @param value number to write
     * @throws IOException if response writing fails
     */
    private static void rounded(final JsonWriter json, final double value) throws IOException
    {
        if (Double.isFinite(value)) json.value(round(value, 4));
        else json.nullValue();
    }
}
