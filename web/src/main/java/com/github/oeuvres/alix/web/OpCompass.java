package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatRail;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.FocusPivots;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.TermDispersion;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.vecs.CompassVec;
import com.github.oeuvres.alix.lucene.vecs.CoocClusters;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.util.AssociationMeasure;
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
 * Without a text query or document filter, projects the ranked theme terms
 * of the whole field instead, with positional pair edges, lexical dispersion
 * (1 - DP), graph clusters, and no pivots or focuses.
 * Theme mode uses the keyword plane and its reference-vector centroid.
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
     * Writes the observed pair counts, expected counts under independence and
     * signed association scores. Query mode counts shared snippet contexts;
     * theme mode counts positional pairs with corpus-wide endpoint marginals.
     * @param json writer positioned after the array name
     * @param mat cooccurrence matrix, or null
     * @param railCoocs positional sample statistics, or null for snippet contexts
     * @param scorer association measure for edges
     * @return positive association matrix for theme mode, or null
     * @throws IOException if writing fails
     */
    private static double[][] coocs(final JsonWriter json, final IntMatrixById mat,
            final CoocMatRail railCoocs, final AssociationMeasure scorer) throws IOException
    {
        final double[][] weights = railCoocs == null ? null : new double[mat.rowCount()][mat.colCount()];
        json.beginArray();
        if (mat != null) {
            final boolean pairs = railCoocs != null;
            final long total = pairs ? railCoocs.total() : mat.total();
            if (total > 0L) {
                for (int a = 0; a < mat.rowCount(); a++) {
                    final int source = mat.rowId(a);
                    final long rowMargin = pairs ? railCoocs.margin(source) : mat.countByRank(a, a);
                    for (int b = a + 1; b < mat.colCount(); b++) {
                        final int count = mat.countByRank(a, b);
                        if (count == 0) continue;
                        final int target = mat.colId(b);
                        final long colMargin = pairs ? railCoocs.margin(target) : mat.countByRank(b, b);
                        json.beginObject();
                        json.name("source").value(source);
                        json.name("target").value(target);
                        json.name(pairs ? "count" : "contexts").value(count);
                        json.name("expected").value((double) rowMargin * colMargin / total);
                        json.name("score");
                        final double score = scorer.score(count, rowMargin, colMargin, total);
                        rounded(json, score);
                        if (weights != null && Double.isFinite(score) && score > 0) {
                            weights[a][b] = weights[b][a] = score;
                        }
                        json.endObject();
                    }
                }
            }
        }
        json.endArray();
        return weights;
    }

    /**
     * Writes spectral clusters, representative term ids and memberships.
     * @param json writer inside the data object
     * @param mat matrix defining vertex ranks
     * @param weights complete positive association graph, or null
     * @param requested requested cluster count, or AUTO
     * @throws IOException if writing fails
     */
    private static void clusters(final JsonWriter json, final IntMatrixById mat,
            final double[][] weights, final int requested) throws IOException
    {
        json.name("clusters").beginObject();
        json.name("requested").value(requested == CoocClusters.AUTO ? "auto" : Integer.toString(requested));
        if (weights != null) {
            final CoocClusters.Result result = CoocClusters.analyse(weights, requested);
            json.name("modularity");
            rounded(json, result.modularity());
            json.name("eigenvalues").beginArray();
            for (final double value : result.eigenvalues()) rounded(json, value);
            json.endArray();
            json.name("groups").beginArray();
            for (final int representative : result.representatives()) json.value(mat.rowId(representative));
            json.endArray();
            json.name("members").beginArray();
            for (int i = 0; i < result.groups().length; i++) {
                if (result.groups()[i] < 0) continue;
                json.beginObject();
                json.name("id").value(mat.rowId(i));
                json.name("group").value(result.groups()[i]);
                json.name("strength");
                rounded(json, result.strengths()[i]);
                json.endObject();
            }
            json.endArray();
        }
        json.endObject();
    }

    /**
     * Writes query pivots and terms in the selected plane, with their
     * statistics in each population, the contexts shared by placed terms, and the
     * model words most similar to each pivot. Without a query or filter, writes
     * the ranked theme terms, their coordinates and positional cooccurrences.
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
        final TopTerms themes;
        if (terms == null) {
            final String q = pars.getString(Q, null);
            if (q != null && !q.isBlank()) {
                badRequest(request, response, meta, "[no term selection]");
                return;
            }
            if (filterQuery(lucene, pars, meta) != null) {
                badRequest(request, response, meta, "[document filter without text query is not supported]");
                return;
            }
            themes = OpTerms.topTerms(lucene, pars, meta);
            if (themes == null) {
                badRequest(request, response, meta, "[no term selection]");
                return;
            }
        }
        else {
            themes = null;
        }
        final boolean theme = themes != null;
        final List<TermEntry> themeEntries = new ArrayList<>();
        if (theme) themes.forEach(themeEntries::add);
        final Path modelPath = modelPath(request, pars, meta);
        if (modelPath == null) {
            AlixServlet.jsonError(request, response);
            return;
        }
        final VecModel model = vecModel(modelPath, meta);
        final List<String> notInModel = new ArrayList<>();
        final int[] pivots = theme ? new int[0] : terms.pivotIds();
        final List<Integer> pivotVecs = new ArrayList<>();
        for (final int pivot : pivots) {
            final String form = terms.lexicon().form(pivot);
            final int id = modelId(model, form);
            pivotVecs.add(id);
            if (id < 0) notInModel.add(form);
        }
        final int[] allPivotVecIds = pivotVecs.stream().mapToInt(Integer::intValue).filter(id -> id >= 0).distinct().toArray();
        if (!theme && allPivotVecIds.length == 0) {
            badRequest(request, response, meta, "[no query pivot found in vector model]");
            return;
        }

        final int termCount = theme ? themeEntries.size() : terms.size();
        final int[] termVecs = new int[termCount];
        final List<Integer> keywordIds = new ArrayList<>();
        for (int rank = 0; rank < termCount; rank++) {
            final String form = theme ? themeEntries.get(rank).form() : terms.lexicon().form(terms.termId(rank));
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
        if (theme && !keywords) {
            badRequest(request, response, meta, "[theme terms require plane=keywords]");
            return;
        }

        final String layoutName = pars.getString(
            "layout", pars.getBoolean("mds", false) ? "mds" : "pca");
        final CompassVec.Layout layout = switch (layoutName) {
            case "pca" -> CompassVec.Layout.PCA;
            case "mds" -> CompassVec.Layout.MDS;
            case "mds_cosine" -> CompassVec.Layout.MDS_COSINE;
            case "umap" -> CompassVec.Layout.UMAP;
            case "lpp" -> CompassVec.Layout.LPP;
            case "centred" -> CompassVec.Layout.MDS_CENTRED;
            default -> null;
        };
        if (layout == null) {
            badRequest(request, response, meta, "[unknown layout: " + layoutName + "]");
            return;
        }

        final boolean pca = layout == CompassVec.Layout.PCA;
        int axisX = 1;
        int axisY = 2;
        if (pca) {
            final String axes = pars.getString("axes", "1,2");
            final String[] components = axes.split(",", -1);
            try {
                if (components.length != 2) throw new IllegalArgumentException();
                axisX = Integer.parseInt(components[0].trim());
                axisY = Integer.parseInt(components[1].trim());
                if (axisX < 1 || axisY < 1 || axisX == axisY) throw new IllegalArgumentException();
            }
            catch (IllegalArgumentException e) {
                badRequest(request, response, meta, "[axes requires two distinct positive component ranks: " + axes + "]");
                return;
            }
        }

        final CompassVec compass;
        try {
            if (theme) {
                final int[] ids = keywordIds.stream().mapToInt(Integer::intValue).distinct().toArray();
                compass = pca ? new CompassVec(model, ids, axisX, axisY)
                    : new CompassVec(model, ids, layout);
            }
            else if (layout == CompassVec.Layout.UMAP || (keywords && (pca || layout == CompassVec.Layout.LPP))) {
                if (!keywords) {
                    badRequest(request, response, meta, "[UMAP requires plane=keywords]");
                    return;
                }
                final int[] ids = IntStream.concat(keywordIds.stream().mapToInt(Integer::intValue),
                    IntStream.of(allPivotVecIds)).distinct().toArray();
                compass = pca ? new CompassVec(model, ids, axisX, axisY)
                    : new CompassVec(model, ids, layout);
            }
            else if (keywords) {
                compass = new CompassVec(
                    model, keywordIds.stream().mapToInt(Integer::intValue).toArray(), layout);
            }
            else {
                compass = pca ? new CompassVec(model, allPivotVecIds, NEIGHBORS, axisX, axisY)
                    : new CompassVec(model, allPivotVecIds, NEIGHBORS, layout);
            }
        }
        catch (IllegalArgumentException e) {
            badRequest(request, response, meta, "[" + e.getMessage() + "]");
            return;
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
        // Pivot distances exist only in query mode.
        final double[] distances = new double[points.length];
        if (!theme) {
            for (final CompassVec.Point point : points) {
                if (point != null) nodeVecs.add(point.id());
            }
            final int[] nodeIds = nodeVecs.stream().mapToInt(Integer::intValue).toArray();
            final double[] values = nodeIds.length == 0 ? new double[0]
                : compass.pivotDistances(nodeIds, allPivotVecIds);
            int placedNode = 0;
            for (int i = 0; i < points.length; i++) {
                distances[i] = points[i] == null ? Double.NaN : values[placedNode++];
            }
        }

        final List<Integer> placed = new ArrayList<>();
        for (int rank = 0; rank < termCount; rank++) {
            if (points[pivotCount + rank] != null) {
                placed.add(theme ? themeEntries.get(rank).termId() : terms.termId(rank));
            }
        }
        final int[] placedIds = placed.stream().mapToInt(Integer::intValue).toArray();
        int requestedClusters = 0;
        if (theme) {
            final String value = pars.getString("clusters", Integer.toString(CoocClusters.DEFAULT_CLUSTERS));
            try {
                requestedClusters = "auto".equals(value) ? CoocClusters.AUTO : Integer.parseInt(value);
                if (requestedClusters > CoocClusters.MAX_CLUSTERS || requestedClusters < 0 && !"auto".equals(value)) {
                    throw new IllegalArgumentException();
                }
            }
            catch (IllegalArgumentException e) {
                badRequest(request, response, meta, "[clusters requires 0 to 5 or auto: " + value + "]");
                return;
            }
        }
        final int distance = theme ? pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP) : 0;
        final IntMatrixById mat;
        final CoocMatRail railCoocs;
        final double[] dispersion;
        if (theme) {
            final FlucText contentFluc = contentFluc(lucene, pars, meta);
            if (contentFluc == null) {
                badRequest(request, response, meta, "[no content field]");
                return;
            }
            final int[] themeIds = themeEntries.stream().mapToInt(TermEntry::termId).toArray();
            dispersion = TermDispersion.scores(lucene.reader(), contentFluc.termLexicon(),
                contentFluc.termStats(), themeIds);
            if (placedIds.length >= 2 && distance > 0) {
                mat = new IntMatrixById(placedIds, placedIds, null);
                railCoocs = CoocMatRail.count(mat, contentFluc.termRail(), distance);
            }
            else {
                mat = null;
                railCoocs = null;
            }
        }
        else {
            dispersion = null;
            mat = coocMatrix(lucene, pars, meta, terms.query(), placedIds);
            railCoocs = null;
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
            json.name("unit").value(switch (layout) {
                case PCA -> "vector-projection";
                case UMAP, LPP -> "layout-coordinate";
                default -> "model-distance";
            });
            if (layout == CompassVec.Layout.UMAP || layout == CompassVec.Layout.LPP) {
                json.name("qualityMetric").value("neighbor-recall@5");
            }
            json.name("plane").value(keywords ? "keywords" : "local");
            json.name("layout").value(layoutName);
            json.name("reference").value(compass.referenceCount());
            if (pca) {
                json.name("components").beginArray().value(axisX).value(axisY).endArray();
            }
            json.endObject();

            if (theme) {
                json.name("dispersionMetric").value("1-DP");
                json.name("docs").value(themes.docs());
                json.name("tokens").value(themes.tokens());
                json.name("focuses").beginArray().endArray();
                json.name("populations").beginArray().endArray();
            }
            else {
                TermsUtil.populations(json, terms);
            }

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
                if (theme) {
                    TermsUtil.termFields(json, themeEntries.get(rank), rank, false);
                    json.name("dispersion");
                    rounded(json, dispersion[rank]);
                }
                else {
                    TermsUtil.termFields(json, terms, rank);
                }
                point(json, points[node]);
                if (!theme) {
                    json.name("pivotDistance");
                    rounded(json, distances[node]);
                }
                json.endObject();
            }
            json.endArray();

            json.name("coocUnit").value(theme ? "pairs" : "contexts");
            if (theme) json.name("coocDistance").value(distance);
            json.name("coocs");
            final double[][] weights = coocs(json, mat, railCoocs, new AssociationMeasure.LogLikelihood());
            if (theme) clusters(json, mat, weights, requestedClusters);

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
