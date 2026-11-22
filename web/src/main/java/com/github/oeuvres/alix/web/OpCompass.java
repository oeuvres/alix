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
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.PoleTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
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
 * Produces a two-dimensional map of the terms selected for a query by
 * {@link OpTerms#poleTerms}, one pole or several.
 *
 * <p>By default, the PCA plane is fitted to the selected terms, without
 * including query pivots in the fit. The request parameter {@code plane=local}
 * instead fits the PCA plane to the {@link #NEIGHBORS} model words nearest to
 * the query pivots. Both modes project pivots and terms into their
 * respective planes. {@code layout=pca|mds|centred} selects the layout
 * (default {@code pca}; the former {@code mds=true} still selects
 * {@code mds}).</p>
 *
 * <p>Two sources are kept apart. The model gives positions,
 * {@code pivotDistance} and {@code neighbors}. The contexts of each pole give
 * the counts, scores and {@code coocs}.</p>
 *
 * <p>The JSON {@code data} is the one of terms.json (see
 * {@link TermsUtil#json(HttpServletRequest, HttpServletResponse, PoleTerms)}),
 * extended:</p>
 * <ul>
 * <li>{@code axes}: the settings of the map;</li>
 * <li>{@code poles}: as in terms.json;</li>
 * <li>{@code pivots}: as in terms.json, with {@code x}, {@code y},
 * {@code quality} and {@code neighbors}, the {@link #NEAREST} words of the
 * whole model most similar to the pivot, when the pivot is in the model;</li>
 * <li>{@code terms}: as in terms.json, with {@code x}, {@code y} and
 * {@code quality} when the term is in the model;</li>
 * <li>in the {@code poles} array of a pivot or a term,
 * {@code pivotDistance}: model cosine distance to the nearest pivot of that
 * pole, {@code null} when the term or the pole has no vector;</li>
 * <li>{@code coocs}: for each pair of placed terms present together in at
 * least one context of a pole, {@code source} and {@code target} (term ids),
 * and for each pole the number of shared {@code contexts} and the
 * {@code score} of the pair: signed G² of {@link KeynessScorer.G2Alpha} on
 * the 2 x 2 table of the pole contexts (containing the first term or not,
 * containing the second or not), {@code null} when absent or without table.
 * Pairs are in the order of the terms, not sorted by score: scores of
 * poles of different sizes are not comparable.</li>
 * </ul>
 *
 * <p>Forms that could not be placed, absent from the model, are listed in
 * {@code meta.notInModel}.</p>
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
     * Counts, for the placed terms, the contexts of one pole in which each
     * term and each pair of terms are present. This is a second walk of the
     * contexts walked by {@link OpTerms#poleTerms}, with the same pole query,
     * filter and margins, read from the same parameters.
     *
     * @param lucene Lucene index
     * @param pars resolved parameters
     * @param meta meta of the request
     * @param spanQuery span query of the pole
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
        // same as in OpTerms.poleTerms
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
     * Writes the array of the pairs of placed terms sharing at least one
     * context in at least one pole, see {@link #coocMatrix}, with the number
     * of shared contexts and the score of the pair in each pole.
     *
     * <p>The score is signed G² at its midpoint specificity. For a pair
     * (a, b) in one pole, the corpus is the contexts of the pole, the focus
     * the contexts containing a, the scored term b: counts are contexts, not
     * occurrences. The 2 x 2 table is the same from the side of b, so one
     * score serves both terms. A pair absent from a pole, or a term present
     * in every context, has no table and a {@code null} score.</p>
     *
     * @param json writer, positioned after the name of the array
     * @param mats context counts of each pole, an entry may be null
     * @param termIds lexicon ids of the placed terms
     * @throws IOException if response writing fails
     */
    private static void coocs(
        final JsonWriter json,
        final IntMatrixById[] mats,
        final int[] termIds
    ) throws IOException {
        final KeynessScorer scorer = new KeynessScorer.G2Alpha();
        json.beginArray();
        for (int a = 0; a < termIds.length; a++) {
            for (int b = a + 1; b < termIds.length; b++) {
                boolean shared = false;
                for (final IntMatrixById mat : mats) {
                    if (mat != null && mat.count(termIds[a], termIds[b]) > 0) {
                        shared = true;
                        break;
                    }
                }
                if (!shared) {
                    continue;
                }
                json.beginObject();
                json.name("source").value(termIds[a]);
                json.name("target").value(termIds[b]);
                json.name("poles");
                json.beginArray();
                for (final IntMatrixById mat : mats) {
                    final int contexts = mat == null ? 0 : mat.count(termIds[a], termIds[b]);
                    double score = Double.NaN;
                    if (contexts > 0 && mat.total() > 0L) {
                        scorer.corpus(mat.total(), (int) mat.total());
                        scorer.focus(mat.count(termIds[a], termIds[a]), 0d);
                        score = scorer.score(contexts, mat.count(termIds[b], termIds[b]), 0);
                    }
                    json.beginObject();
                    json.name("contexts").value(contexts);
                    json.name("score");
                    rounded(json, score);
                    json.endObject();
                }
                json.endArray();
                json.endObject();
            }
        }
        json.endArray();
    }

    /**
     * Writes query pivots and terms in the selected plane, with their
     * statistics in every pole, the contexts shared by placed terms, and the
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
        final PoleTerms poles = OpTerms.poleTerms(lucene, pars, meta);
        if (poles == null) {
            badRequest(request, response, meta, "[no term selection]");
            return;
        }
        final Path modelPath = modelPath(request, pars, meta);
        if (modelPath == null) {
            AlixServlet.jsonError(request, response);
            return;
        }
        final VecModel model = vecModel(modelPath, meta);
        final int poleCount = poles.poleCount();
        final List<String> notInModel = new ArrayList<>();

        // Pivots of every pole in pole order, with their owner and model id.
        final List<TopTerms.ExcludedTerm> pivots = new ArrayList<>();
        final List<Integer> owners = new ArrayList<>();
        final List<Integer> pivotVecs = new ArrayList<>();
        final int[][] poleVecIds = new int[poleCount][];
        for (int pole = 0; pole < poleCount; pole++) {
            final List<Integer> ids = new ArrayList<>();
            for (final TopTerms.ExcludedTerm pivot : poles.pole(pole).excludedTerms()) {
                final int id = modelId(model, pivot.form());
                pivots.add(pivot);
                owners.add(pole);
                pivotVecs.add(id);
                if (id >= 0) {
                    ids.add(id);
                }
                else {
                    notInModel.add(pivot.form());
                }
            }
            poleVecIds[pole] = ids.stream().mapToInt(Integer::intValue).toArray();
        }
        final int[] allPivotVecIds = pivotVecs.stream().mapToInt(Integer::intValue).filter(id -> id >= 0).toArray();
        if (allPivotVecIds.length == 0) {
            badRequest(request, response, meta, "[no query pivot found in vector model]");
            return;
        }

        final int termCount = poles.size();
        final int[] termVecs = new int[termCount];
        final List<Integer> keywordIds = new ArrayList<>();
        for (int rank = 0; rank < termCount; rank++) {
            final String form = poles.pole(0).lexicon().form(poles.termId(rank));
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
        final int pivotCount = pivots.size();
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

        // distances[node][pole], aligned with points; NaN without vector.
        final double[][] distances = new double[points.length][poleCount];
        for (int pole = 0; pole < poleCount; pole++) {
            final double[] values = poleVecIds[pole].length == 0 || nodeIds.length == 0
                ? null
                : compass.pivotDistances(nodeIds, poleVecIds[pole]);
            int node = 0;
            for (int i = 0; i < points.length; i++) {
                if (points[i] == null) {
                    distances[i][pole] = Double.NaN;
                    continue;
                }
                distances[i][pole] = values == null ? Double.NaN : values[node];
                node++;
            }
        }

        // Co-occurrences of placed terms, pole by pole.
        final List<Integer> placed = new ArrayList<>();
        for (int rank = 0; rank < termCount; rank++) {
            if (points[pivotCount + rank] != null) {
                placed.add(poles.termId(rank));
            }
        }
        final int[] placedIds = placed.stream().mapToInt(Integer::intValue).toArray();
        final IntMatrixById[] mats = new IntMatrixById[poleCount];
        for (int pole = 0; pole < poleCount; pole++) {
            mats[pole] = coocMatrix(lucene, pars, meta, poles.spanQuery(pole), placedIds);
        }

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

            json.name("poles");
            TermsUtil.poles(json, poles);

            json.name("pivots");
            json.beginArray();
            for (int k = 0; k < pivotCount; k++) {
                final TopTerms.ExcludedTerm pivot = pivots.get(k);
                final int owner = owners.get(k);
                json.beginObject();
                TermsUtil.pivotFields(json, owner, pivot);
                point(json, points[k]);
                if (points[k] != null) {
                    json.name("neighbors");
                    neighbors(json, model, pivotVecs.get(k));
                }
                json.name("poles");
                json.beginArray();
                for (int pole = 0; pole < poleCount; pole++) {
                    json.beginObject();
                    TermsUtil.pivotPoleFields(json, poles, owner, pivot, pole);
                    json.name("pivotDistance");
                    rounded(json, distances[k][pole]);
                    json.endObject();
                }
                json.endArray();
                json.endObject();
            }
            json.endArray(); // pivots

            json.name("terms");
            json.beginArray();
            for (int rank = 0; rank < termCount; rank++) {
                final int node = pivotCount + rank;
                json.beginObject();
                TermsUtil.termFields(json, poles, rank);
                point(json, points[node]);
                json.name("poles");
                json.beginArray();
                for (int pole = 0; pole < poleCount; pole++) {
                    json.beginObject();
                    TermsUtil.termPoleFields(json, poles, rank, pole);
                    json.name("pivotDistance");
                    rounded(json, distances[node][pole]);
                    json.endObject();
                }
                json.endArray();
                json.endObject();
            }
            json.endArray(); // terms

            json.name("coocs");
            coocs(json, mats, placedIds);

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
