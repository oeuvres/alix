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
import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.ALIX_META;
import static com.github.oeuvres.alix.web.Pars.ALIX_PARS;

/**
 * Produces a two-dimensional map of the {@link TopTerms} selected for a query.
 *
 * <p>By default, the PCA plane is fitted to the selected TopTerms, without
 * including query pivots in the fit. The request parameter {@code plane=local}
 * instead fits the PCA plane to the {@link #NEIGHBORS} model words nearest to
 * the query pivots. Both modes project pivots and TopTerms into their
 * respective planes and use the same JSON node format.</p>
 *
 * <p>{@code layout=pca|mds|centred} selects the layout (default {@code pca};
 * the former {@code mds=true} still selects {@code mds}).
 * {@code linkmode=mix|mutual|nearest|tree|directed|none} selects the lines
 * drawn between nodes (default {@code mix}), and {@code links} their number
 * per node, 0 or more (default {@link #LINKS_PER_NODE}).</p>
 *
 * <p>The JSON {@code data} holds:</p>
 * <ul>
 * <li>{@code axes}: the settings of the map;</li>
 * <li>{@code nodes}: pivots, then TopTerms, each with its vector {@code id};
 * a TopTerms node carries {@code pivotDistance}, its model cosine distance to
 * the nearest pivot;</li>
 * <li>{@code links}: {@code source} and {@code target} are node ids;</li>
 * <li>{@code neighbors}: for each pivot, then for all pivots together when
 * there are several, the {@link #NEAREST} words of the whole model most
 * similar to it ({@code 1 - mean(cosine)} to the pivots, see
 * {@link VecModel#nearest(int[], int)}). These words are taken from the
 * model, not from the query contexts: they need not be on the map.</li>
 * </ul>
 *
 * <p>{@code linkmode=mix} links each term to its nearest displayed term, with
 * {@code "directed": true} when the choice is not mutual (source to its
 * nearest), then adds mutual-rank pairs up to the budget; all from the word
 * vectors alone. {@code linkmode=directed} is experimental: when the context
 * vectors of the model sit next to it ({@code <name>.ctx} for
 * {@code <name>.bin}), it draws each term to the term it expects most,
 * relative to the other displayed terms (see {@link CompassVec}).</p>
 *
 * <p>With {@code linkmode=mix} or {@code linkmode=directed}, pivot links are
 * added: the TopTerms with the highest share of their corpus occurrences
 * inside the query contexts ({@code freq / fieldFreq}), each linked to its
 * nearest pivot, marked {@code "pivot": true} with its {@code "share"}; their
 * number per node is {@link #PIVOT_LINKS_PER_NODE}, never more than
 * {@code links}. A link already drawn between the same two terms is not
 * repeated.</p>
 */
public class OpCompass extends Op
{
    /** Property for the directory of models */
    private static final String ALIX_MODELS_ROOT = "alix.models.root";
    /**
     * Default of the {@code links} parameter, links per node: a readability
     * choice, not derived from the model.
     */
    private static final double LINKS_PER_NODE = 1.5d;
    /** Similar words listed for each query word, a display choice. */
    private static final int NEAREST = 20;
    /**
     * Number of model words nearest to the query pivots that define the PCA
     * plane with {@code plane=local}. They fit the plane and are not
     * displayed. Unused with the default keyword plane.
     */
    private static final int NEIGHBORS = 300;
    /** Pivot links per displayed node, a display choice: about 8 for 50 nodes. */
    private static final double PIVOT_LINKS_PER_NODE = 0.15d;

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
     * Returns the context vector of each displayed term.
     *
     * @param contexts context vectors of the model
     * @param points displayed terms
     * @return one vector per term, null for a term without context vector
     */
    private static double[][] contextVectors(
        final VecModel contexts,
        final List<CompassVec.Point> points
    ) {
        final double[][] vectors = new double[points.size()][];
        for (int node = 0; node < vectors.length; node++) {
            final int id = contexts.id(points.get(node).form());
            if (id < 0) {
                continue;
            }
            vectors[node] = new double[contexts.dim()];
            contexts.get(id, vectors[node]);
        }
        return vectors;
    }

    /**
     * Writes query pivots and TopTerms in the selected plane, their links,
     * and the model words most similar to the query.
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

        final String linksName = pars.getString("linkmode", "mix");
        final CompassVec.LinkMode linkMode;
        switch (linksName) {
            case "mutual" -> linkMode = CompassVec.LinkMode.MUTUAL_RANK;
            case "nearest" -> linkMode = CompassVec.LinkMode.NEAREST;
            case "tree" -> linkMode = CompassVec.LinkMode.TREE;
            case "mix" -> linkMode = CompassVec.LinkMode.MIX;
            case "directed" -> linkMode = CompassVec.LinkMode.DIRECTED;
            case "none" -> linkMode = null;
            default -> {
                badRequest(request, response, meta, "[unknown linkmode: " + linksName + "]");
                return;
            }
        }

        double linksPerNode = LINKS_PER_NODE;
        final String linksValue = pars.getString("links", null);
        if (linksValue != null && !linksValue.isBlank()) {
            try {
                linksPerNode = Double.parseDouble(linksValue.trim());
            }
            catch (final NumberFormatException e) {
                linksPerNode = Double.NaN;
            }
            if (!(linksPerNode >= 0d) || Double.isInfinite(linksPerNode)) {
                badRequest(request, response, meta, "[links must be a number >= 0: " + linksValue + "]");
                return;
            }
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

        final Path contextsPath = modelsRoot.resolve(modelName.replaceFirst("\\.bin$", "") + ".ctx");
        final double[][] nodeContexts =
            linkMode == CompassVec.LinkMode.DIRECTED && Files.isRegularFile(contextsPath)
                ? contextVectors(VecModel.get(contextsPath), points)
                : null;
        final List<CompassVec.Link> links = linkMode == null
            ? List.of()
            : compass.links(
                nodeIds,
                (int) Math.round(linksPerNode * nodeIds.length),
                linkMode,
                nodeContexts);

        final double[] shares = new double[nodeIds.length];
        final List<CompassVec.Link> pivotLinks =
            linkMode == CompassVec.LinkMode.MIX || linkMode == CompassVec.LinkMode.DIRECTED
                ? pivotLinks(
                    compass,
                    nodeIds,
                    entries,
                    shares,
                    (int) Math.round(Math.min(PIVOT_LINKS_PER_NODE, linksPerNode) * nodeIds.length),
                    links)
                : List.of();
        final double[] pivotDistances = compass.pivotDistances(nodeIds, pivotIds);

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
            json.name("linkmode").value(linksName);
            json.name("links").value(linksPerNode);
            json.name("directed").value(nodeContexts != null);
            json.name("reference").value(compass.referenceCount());
            json.name("xCenter").value(round(compass.xCenter(), 4));
            json.name("yCenter").value(round(compass.yCenter(), 4));
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
                    json.name("type").value("pivot");
                }
                else {
                    json.name("freq").value(term.freq());
                    json.name("score").value(round(term.score(), 4));
                    json.name("pivotDistance").value(round(pivotDistances[node], 4));
                }
                json.endObject();
            }
            json.endArray();

            json.name("links");
            json.beginArray();
            for (final CompassVec.Link link : links) {
                json.beginObject();
                json.name("source").value(link.source());
                json.name("target").value(link.target());
                json.name("distance").value(round(link.distance(), 4));
                if (link.directed()) {
                    json.name("directed").value(true);
                }
                json.endObject();
            }
            for (final CompassVec.Link link : pivotLinks) {
                json.beginObject();
                json.name("source").value(link.source());
                json.name("target").value(link.target());
                json.name("distance").value(round(link.distance(), 4));
                json.name("directed").value(true);
                json.name("pivot").value(true);
                for (int node = 0; node < nodeIds.length; node++) {
                    if (nodeIds[node] == link.source()) {
                        json.name("share").value(round(shares[node], 4));
                        break;
                    }
                }
                json.endObject();
            }
            json.endArray();

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
     * Writes the array of the model words most similar to the query: one
     * entry per pivot, then one for all pivots together when there are
     * several. An entry has the {@code form} of its pivots, separated by
     * spaces, and its {@code terms}, most similar first, each with its
     * {@code form} and {@code distance}.
     *
     * @param json writer, positioned after the name of the array
     * @param model vector model
     * @param pivotIds vector ids of the query pivots
     * @throws IOException if response writing fails
     */
    private void neighbors(
        final JsonWriter json,
        final VecModel model,
        final int[] pivotIds
    ) throws IOException {
        final List<int[]> queries = new ArrayList<>();
        for (final int pivotId : pivotIds) {
            queries.add(new int[] { pivotId });
        }
        if (pivotIds.length > 1) {
            queries.add(pivotIds);
        }
        json.beginArray();
        for (final int[] query : queries) {
            final StringBuilder form = new StringBuilder();
            for (final int id : query) {
                if (!form.isEmpty()) {
                    form.append(' ');
                }
                form.append(model.word(id));
            }
            json.beginObject();
            json.name("form").value(form.toString());
            json.name("terms");
            json.beginArray();
            for (final VecModel.Neighbor neighbor : model.nearest(query, NEAREST)) {
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

    /**
     * Computes the share of each displayed term, the part of its corpus
     * occurrences inside the query contexts, and returns the pivot links not
     * already drawn between the same two terms.
     *
     * @param compass compass of the map
     * @param nodeIds vector ids of the displayed terms
     * @param entries TopTerms entry of each displayed term, null for a pivot
     * @param shares filled with the share of each displayed term, 0 for a pivot
     * @param limit maximum number of pivot links
     * @param links links already drawn
     * @return pivot links, highest share first
     */
    private static List<CompassVec.Link> pivotLinks(
        final CompassVec compass,
        final int[] nodeIds,
        final List<TermEntry> entries,
        final double[] shares,
        final int limit,
        final List<CompassVec.Link> links
    ) {
        final boolean[] isPivot = new boolean[nodeIds.length];
        for (int node = 0; node < nodeIds.length; node++) {
            final TermEntry term = entries.get(node);
            isPivot[node] = term == null;
            shares[node] = term == null || term.fieldFreq() == 0L
                ? 0d
                : (double) term.freq() / term.fieldFreq();
        }
        final List<CompassVec.Link> pivotLinks = new ArrayList<>();
        for (final CompassVec.Link link : compass.pivotLinks(nodeIds, isPivot, shares, limit)) {
            boolean drawn = false;
            for (final CompassVec.Link other : links) {
                if ((other.source() == link.source() && other.target() == link.target())
                    || (other.source() == link.target() && other.target() == link.source())) {
                    drawn = true;
                    break;
                }
            }
            if (!drawn) {
                pivotLinks.add(link);
            }
        }
        return pivotLinks;
    }
}
