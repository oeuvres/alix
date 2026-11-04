package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * instead fits the PCA plane to nearest neighbours of the query pivots.
 * Both modes project pivots and TopTerms into their respective planes and use
 * the same JSON node format.</p>
 *
 * <p>{@code layout=pca|mds|centred} selects the layout (default {@code pca};
 * the former {@code mds=true} still selects {@code mds}). {@code links=mutual|nearest|tree|mix|none}
 * selects the lines drawn between nodes (default {@code mutual}). The JSON
 * writes {@code nodes} first, each with its vector {@code id}, then
 * {@code links}, whose {@code source} and {@code target} are node ids. Each
 * TopTerms node carries {@code pivotDistance}, its model cosine distance to
 * the nearest pivot.</p>
 *
 * <p>When the context vectors of the model sit next to it ({@code <name>.ctx}
 * for {@code <name>.bin}), {@code links=mix} draws directed links after the
 * spanning tree: a term to the term it expects most, relative to the other
 * displayed terms (see {@link CompassVec}). The context vector of a term is
 * the sum of its band vectors, {@code word@5}, {@code word@10}..., the bands
 * being read from the model name ({@code -b5_10_30}). A directed link has
 * {@code "directed": true}, source expecting target.</p>
 */
public class OpCompass extends Op
{
    /** Property for the directory of models */
    private static final String ALIX_MODELS_ROOT="alix.models.root";
    /** Bands in a model name, e.g. {@code -b5_10_30}. */
    private static final Pattern BANDS = Pattern.compile("-b(\\d+(?:_\\d+)*)");
    /** Display budget of links per node: a readability choice, not derived from the model. */
    private static final double LINKS_PER_NODE = 2.5d;
    /**
     * Links added to the spanning tree in {@code links=mix}, per node: the tree
     * gives about one link per node, so mix totals about {@link #LINKS_PER_NODE}.
     */
    private static final double MIX_LINKS_PER_NODE = 1.5d;
    /** Number of query neighbours defining the optional local PCA plane. */
    private static final int NEIGHBORS = 300;

    /** Vector model used by the current experiment. */
    private static String modelDefault;

    /**
     * Returns the context vector of each displayed term: the sum of its
     * context vectors over the bands of the model, or its single context
     * vector for a model without bands.
     *
     * @param contexts context vectors of the model
     * @param modelName file name of the model, giving its bands
     * @param points displayed terms
     * @return one vector per term, null for a term without context vector
     */
    private static double[][] contextVectors(
        final VecModel contexts,
        final String modelName,
        final List<CompassVec.Point> points
    ) {
        final Matcher bands = BANDS.matcher(modelName);
        final String[] suffixes;
        if (bands.find()) {
            final String[] bounds = bands.group(1).split("_");
            suffixes = new String[bounds.length];
            for (int band = 0; band < bounds.length; band++) {
                suffixes[band] = "@" + bounds[band];
            }
        }
        else {
            suffixes = new String[] { "" };
        }
        final double[][] vectors = new double[points.size()][];
        final double[] band = new double[contexts.dim()];
        for (int node = 0; node < vectors.length; node++) {
            for (final String suffix : suffixes) {
                final int id = contexts.id(points.get(node).form() + suffix);
                if (id < 0) {
                    continue;
                }
                contexts.get(id, band);
                if (vectors[node] == null) {
                    vectors[node] = new double[band.length];
                }
                for (int axis = 0; axis < band.length; axis++) {
                    vectors[node][axis] += band[axis];
                }
            }
        }
        return vectors;
    }
    
    /**
     * Returns the configured models root directory.
     *
     * @param request HTTP request
     * @return models root directory
     * @throws IOException if the parameter is missing or does not designate a directory
     * @throws ServletException 
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
     * Writes query pivots and TopTerms in the selected PCA plane.
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
        final Path modelsRoot = modelsRoot(request);
        final String modelName = pars.getString("model", modelDefault);
        final VecModel model = VecModel.get(modelsRoot.resolve(modelName));
        final Path contextsPath = modelsRoot.resolve(modelName.replaceFirst("\\.bin$", "") + ".ctx");
        final VecModel contexts = Files.isRegularFile(contextsPath) ? VecModel.get(contextsPath) : null;
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

        final String plane = request.getParameter("plane");
        if (plane != null && !"local".equals(plane) && !"keywords".equals(plane)) {
            response.setStatus(400);
            meta.log("[unknown PCA plane: " + plane + "]");
            AlixServlet.jsonError(request, response);
            return;
        }
        final boolean keywords = !"local".equals(plane);
        final int[] ids;
        if (keywords) {
            final List<Integer> keywordIds = new ArrayList<>();
            for (final TermEntry term : topTerms) {
                final int id = model.id(term.form());
                if (id >= 0) {
                    keywordIds.add(id);
                }
            }
            if (keywordIds.size() < 3) {
                response.setStatus(400);
                meta.log("[keyword PCA requires at least three words in the vector model]");
                AlixServlet.jsonError(request, response);
                return;
            }
            ids = keywordIds.stream().mapToInt(Integer::intValue).toArray();
        }
        else {
            ids = pivotIds.stream().mapToInt(Integer::intValue).toArray();
        }
        final String layoutName = pars.getString(
            "layout", pars.getBoolean("mds", false) ? "mds" : "pca");
        final CompassVec.Layout layout = switch (layoutName) {
            case "pca" -> CompassVec.Layout.PCA;
            case "mds" -> CompassVec.Layout.MDS;
            case "centred" -> CompassVec.Layout.MDS_CENTRED;
            default -> null;
        };
        if (layout == null) {
            response.setStatus(400);
            meta.log("[unknown layout: " + layoutName + "]");
            AlixServlet.jsonError(request, response);
            return;
        }
        final String linksName = pars.getString("links", "mutual");
        final CompassVec.LinkMode linkMode;
        switch (linksName) {
            case "mutual" -> linkMode = CompassVec.LinkMode.MUTUAL_RANK;
            case "nearest" -> linkMode = CompassVec.LinkMode.NEAREST;
            case "tree" -> linkMode = CompassVec.LinkMode.TREE;
            case "mix" -> linkMode = CompassVec.LinkMode.MIX;
            case "none" -> linkMode = null;
            default -> {
                response.setStatus(400);
                meta.log("[unknown links: " + linksName + "]");
                AlixServlet.jsonError(request, response);
                return;
            }
        }
        final CompassVec compass = keywords
            ? new CompassVec(model, ids, layout)
            : new CompassVec(model, ids, NEIGHBORS, layout);

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
        final double[][] nodeContexts = linkMode == CompassVec.LinkMode.MIX && contexts != null
            ? contextVectors(contexts, modelName, points)
            : null;
        final List<CompassVec.Link> links = linkMode == null
            ? List.of()
            : compass.links(
                nodeIds,
                (int) Math.round((linkMode == CompassVec.LinkMode.MIX
                    ? MIX_LINKS_PER_NODE
                    : LINKS_PER_NODE) * nodeIds.length),
                linkMode,
                nodeContexts);
        final double[] pivotDistances = compass.pivotDistances(
            nodeIds, pivotIds.stream().mapToInt(Integer::intValue).toArray());

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
            json.name("links").value(linksName);
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
            json.endArray();
            json.endObject();
            json.endObject();
        }
    }
}
