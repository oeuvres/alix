package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.SpanQueryParser.ParseResult;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.vecs.VecModel;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.github.oeuvres.alix.web.Pars.*;

/**
 * {@code /{index}/neighbors} — the words of a vector model nearest to one or
 * more query words.
 *
 * <p>The rows are read from the model, not from a search field: a word of the
 * model is listed even when the default search field does not index it, for
 * example a grammatical word.</p>
 *
 * <h2>Parameters</h2>
 * <ul>
 * <li>{@code q}: the query words, separated by commas, so that a form of
 * several words stays whole ({@code justice rétributive, sanction}). Each
 * item is looked up as it is in the model; an item not found is analysed like
 * a search query of the field of the model, and the resulting forms are
 * looked up.</li>
 * <li>{@code model}: file name of a model of the models root, see
 * {@link Op#modelPath}.</li>
 * <li>{@code terms}: number of neighbours.</li>
 * <li>{@code rank}: {@code tau} or {@code quality} ranks the neighbours by
 * cosine shrunk by the evidence of each candidate, see
 * {@link VecModel#nearestShrunk(int[], int, VecModel.Shrink)}, so that a word
 * seen once falls in the list of a frequent word; absent or any other value,
 * by cosine. Both need the vocabulary file of the model; without it the
 * ranking is by cosine, and the meta says so.</li>
 * </ul>
 *
 * <h2>Response</h2>
 * <p>The {@code meta} describes the model ({@code model}, {@code model dim},
 * {@code model words}) and names the field it was built from
 * ({@code model field}). The {@code data} is one flat array: the query words
 * found in the model, with {@code type: "pivot"}, then the neighbours, nearest
 * first, with their {@code rank}. Each row has:</p>
 * <ul>
 * <li>{@code form}: the word;</li>
 * <li>{@code distance}: {@code 1 - mean(cosine)} to the query words, see
 * {@link VecModel#nearest(int[], int)}. A single query word is at 0 from
 * itself; with several, each one is at its distance to the whole query.
 * With a shrunk {@code rank} it is still the cosine distance, so it does not
 * always grow down the list;</li>
 * <li>{@code fieldDocs}, {@code fieldFreq}: documents and occurrences of the
 * form in the field the model was built from, so the counts the model was
 * computed on. They are absent when that field is unknown.</li>
 * </ul>
 *
 * <p>No term id is given: a Lucene term id belongs to one field, and the field
 * of the model is not the search field of the other operations. The form is
 * the key between them.</p>
 */
public class OpNeighbors extends Op
{
    /** Parameter choosing the ranking of the neighbours. */
    private static final String RANK = "rank";

    /**
     * Returns the indexed forms an item of the query resolves to, when it is
     * analysed like a search query of the field: lemma, or the forms matched
     * by a pattern.
     *
     * @param index Lucene index
     * @param fluc field the model was built from, or null
     * @param item one item of the query, not found as it is in the model
     * @return indexed forms, empty without a field or without a match
     * @throws IOException if query parsing or index access fails
     */
    private static String[] analysed(
        final LuceneIndex index,
        final FlucText fluc,
        final String item
    ) throws IOException {
        if (fluc == null) {
            return new String[0];
        }
        // the slop does not change the terms of the query
        final ParseResult res = fluc.spanQueryParser().parse(item, 0);
        if (res == null || res.spanQuery() == null) {
            return new String[0];
        }
        final SpanQuery spanQuery = (SpanQuery) index.searcher().rewrite(res.spanQuery());
        final TermLexicon lexicon = fluc.termLexicon();
        final int[] termIds = lexicon.termIds(spanQuery);
        final String[] forms = new String[termIds.length];
        for (int i = 0; i < termIds.length; i++) {
            forms[i] = lexicon.form(termIds[i]);
        }
        return forms;
    }

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
     * Writes the counts of a form in the field the model was built from.
     * Nothing is written without a field, or for a form the field does not
     * index.
     *
     * @param json writer, positioned inside the object of a row
     * @param lexicon lexicon of the field, or null
     * @param stats statistics of the field, or null
     * @param form word of the model
     * @throws IOException if response writing fails
     */
    private static void counts(
        final JsonWriter json,
        final TermLexicon lexicon,
        final TermStats stats,
        final String form
    ) throws IOException {
        if (lexicon == null || stats == null) {
            return;
        }
        final int termId = lexicon.id(form);
        if (termId < 1) {
            return;
        }
        json.name("fieldDocs").value(stats.termDocs(termId));
        json.name("fieldFreq").value(stats.termFreq(termId));
    }

    /**
     * Writes the query words found in the model, then their nearest
     * neighbours.
     *
     * @param index Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access, model loading, or response writing fails
     * @throws ServletException if the models root is not configured
     */
    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException, ServletException {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);

        final Path modelPath = modelPath(request, pars, meta);
        if (modelPath == null) {
            AlixServlet.jsonError(request, response);
            return;
        }
        final VecModel model = vecModel(modelPath, meta);

        // The field the model was built from gives the counts and the analysis.
        final String field = modelField(modelPath);
        final FlucText fluc = (field == null) ? null : index.flucText(field);
        final TermLexicon lexicon;
        final TermStats stats;
        if (fluc == null) {
            lexicon = null;
            stats = null;
            meta.log("[field of the model unknown" + (field == null ? "" : ": " + field)
                + ", rows without counts]");
        }
        else {
            lexicon = fluc.termLexicon();
            stats = fluc.termStats();
            meta.put("model field", field);
        }

        final String q = pars.getString(Q, null);
        if (q == null || q.isBlank()) {
            badRequest(request, response, meta, "[no query word]");
            return;
        }
        final Set<Integer> pivotSet = new LinkedHashSet<>();
        for (String item : q.split(",")) {
            item = item.strip();
            if (item.isEmpty()) {
                continue;
            }
            final int id = model.id(item);
            if (id >= 0) {
                pivotSet.add(id);
                continue;
            }
            boolean found = false;
            for (final String form : analysed(index, fluc, item)) {
                final int formId = model.id(form);
                if (formId >= 0) {
                    pivotSet.add(formId);
                    found = true;
                }
            }
            if (!found) {
                meta.log("[not in vector model: " + item + "]");
            }
        }
        if (pivotSet.isEmpty()) {
            badRequest(request, response, meta, "[no query word found in vector model]");
            return;
        }
        final int[] pivotIds = pivotSet.stream().mapToInt(Integer::intValue).toArray();

        final int limit = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT);
        final String rankName = pars.getString(RANK, null);
        VecModel.Shrink shrink = null;
        for (final VecModel.Shrink value : VecModel.Shrink.values()) {
            if (value.name().equalsIgnoreCase(rankName)) {
                shrink = value;
            }
        }
        if (shrink != null && !model.canShrink(shrink)) {
            meta.log("[rank " + rankName + " needs the vocabulary file of the model, ranked by cosine]");
            shrink = null;
        }
        if (shrink != null) {
            meta.put("rank", shrink.name().toLowerCase());
        }
        final List<VecModel.Neighbor> neighbors = (shrink == null)
            ? model.nearest(pivotIds, limit)
            : model.nearestShrunk(pivotIds, limit, shrink);

        try (JsonWriter json = new JsonWriter(response.getWriter())) {
            json.setIndent("  ");
            json.beginObject();
            json.name("meta");
            json.beginObject();
            meta.toJson(json, pars);
            json.endObject();

            json.name("data");
            json.beginArray();
            for (final int pivotId : pivotIds) {
                final String form = model.word(pivotId);
                json.beginObject();
                json.name("form").value(form);
                json.name("type").value("pivot");
                counts(json, lexicon, stats, form);
                json.name("distance").value(round(pivotDistance(model, pivotIds, pivotId), 4));
                json.endObject();
            }
            int rank = 1;
            for (final VecModel.Neighbor neighbor : neighbors) {
                json.beginObject();
                json.name("rank").value(rank++);
                json.name("form").value(neighbor.word());
                counts(json, lexicon, stats, neighbor.word());
                json.name("distance").value(round(neighbor.distance(), 4));
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
    }

    /**
     * Reads the field a model was built from, in the diagnosis file written
     * beside it by {@code Coocs2vec}: {@code <name>.json}, where {@code args}
     * holds the command line, the field being its second argument.
     *
     * @param modelPath model file, {@code <name>.bin}
     * @return field name, or null without a readable diagnosis file
     */
    private static String modelField(final Path modelPath)
    {
        final String fileName = modelPath.getFileName().toString();
        final int dot = fileName.lastIndexOf('.');
        final String name = (dot < 0) ? fileName : fileName.substring(0, dot);
        final Path diagnosis = modelPath.resolveSibling(name + ".json");
        if (!Files.isRegularFile(diagnosis)) {
            return null;
        }
        try (
            Reader reader = Files.newBufferedReader(diagnosis, StandardCharsets.UTF_8);
            JsonReader json = new JsonReader(reader)
        ) {
            json.beginObject();
            while (json.hasNext()) {
                if (!"args".equals(json.nextName()) || json.peek() != JsonToken.BEGIN_ARRAY) {
                    json.skipValue();
                    continue;
                }
                json.beginArray();
                if (!json.hasNext()) {
                    return null;
                }
                json.skipValue(); // index directory
                if (!json.hasNext() || json.peek() != JsonToken.STRING) {
                    return null;
                }
                return json.nextString();
            }
            return null;
        }
        catch (IOException | IllegalStateException e) {
            // a diagnosis file that is not the expected JSON gives no field
            return null;
        }
    }

    /**
     * Returns the distance of one query word to the whole query, with the
     * measure of {@link VecModel#nearest(int[], int)}: {@code 1 - mean(cosine)}
     * over the query words, the word itself included.
     *
     * @param model vector model
     * @param pivotIds vector ids of the query words
     * @param pivotId vector id of one of them
     * @return distance, 0 for a single query word
     */
    private static double pivotDistance(
        final VecModel model,
        final int[] pivotIds,
        final int pivotId
    ) {
        double sum = 0d;
        for (final int other : pivotIds) {
            sum += model.cosine(pivotId, other);
        }
        return 1d - sum / pivotIds.length;
    }
}
