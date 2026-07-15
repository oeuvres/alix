package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.lucene.terms.TopTerms.TermValue.FREQ;
import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;
import java.util.BitSet;
import java.util.Set;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.Assoc;
import com.github.oeuvres.alix.maths.ContingencySvd.SvdLayout;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Produces co-occurrence tables and their contingency-SVD representations.
 */
public final class OpCoocMap extends Op
{
    /** Tables available from the debug CSV endpoint. */
    private enum CsvStep
    {
        /** Observed contingency table. */
        CONTINGENCY,
        /** HAC principal-coordinate feature table for Orange. */
        HAC;
    }
    
    /**
     * Build the cooc matrix.
     * 
     * @param index
     * @param pars
     * @param meta
     * @return
     * @throws IOException
     */
    public static IntMatrixById coocMat(
        final LuceneIndex index,
        final HttpPars pars,
        final MetaUtil meta
    )
        throws IOException {
        final SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) return null;
        final Query filterQuery = filterQuery(index, pars);
        FlucText contentFluc = contentFluc(index, pars, meta);
        TermLexicon contentLexicon = contentFluc.termLexicon();
        TopTerms topTerms = contentFluc.topTerms();
        
        // pivotsIds
        final int[] pivotIds = contentFluc.termLexicon().termIds(spanQuery);
        // increment the topK of pivots
        final int terms = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);

        // same as for the span query parser
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            new DocSnippets(DocSnippets.Usage.POSITIONS, slop),
            filterQuery
        );
        
        final TopTerms.Population population = topTerms.beginPopulation();
        final TopCoocSnippets consumer = new TopCoocSnippets(
            contentFluc.termStats(),
            contentFluc.termRail(),
            left,
            right
        ).bindTo(population);
        
        walker.walk(consumer);
        consumer.subtractPivots(pivotIds); // should remove topTerms and simplify ranking
        consumer.complete();
        
        meta.put("pivotIds", pivotIds);
        meta.put("fieldWidth", contentFluc.termStats().fieldWidth());
        meta.put("fieldTokens", contentFluc.termStats().fieldTokens());
        meta.put("snippets", consumer.contextCount());
        meta.put("focusDocs", consumer.documentCount());
        meta.put("focusTokens", consumer.tokenCount());
        meta.put("hits", walker.hits());
        
        final TermFlag tflag = pars.getEnum(TFLAG, TermFlag.NULL);
        final KeynessScorer scorer = tsort(pars);
        
        // get sorted rows, maybe filtered by a flag, will be the rows of interest to display
        // Should we filter pivots now or after?
        topTerms.rank(scorer, terms, tflag);
        final IntList termIds = new IntList(topTerms.size());
        BitSet rows = new BitSet(contentLexicon.vocabSize());
        String[] rowTopFreq = new String[terms];
        int i = 0;
        for (final TermEntry term : topTerms) {
            final int termId = term.termId();
            rows.set(termId);
            termIds.push(termId);
            rowTopFreq[i++] = term.form() + " (" + term.freq() + ") #" + term.termId();
        }
        meta.put("rowTopFreq", rowTopFreq);
        
        int[] rowIds = termIds.toUniq();

        // get more co-occurrents than the focus terms, without filtering flag, to add semantic signal
        // 0 = same as rows. Positive, cols added
        final int cols = pars.getInt("cols", new int[]{0, 200}, 0);
        final int[] colIds;
        if (cols == 0) {
            colIds = rowIds;
        }
        else {
            final int colmin = pars.getInt("colmin", 8);
            topTerms.rank(new KeynessScorer.G2(), terms + (cols * 5), TermFlag.NOUN, TermFlag.VERB);
            int k = 0;
            for (final TermEntry term : topTerms) {
                final int termId = term.termId();
                if (rows.get(termId)) continue;
                if (term.contexts() < colmin) continue;
                termIds.push(termId);
                if (++k >= cols) break;
            }
            colIds = termIds.toUniq();
        }
        final IntMatrixById coocMat = new IntMatrixById(
            rowIds, 
            colIds,
            id -> contentLexicon.form(id)
        );
        meta.put("colLabels", coocMat.colLabels());
        final boolean directed = pars.getBoolean("directed", false);
        final CoocMatSnippets recorder = new CoocMatSnippets(coocMat, contentFluc.termRail(), left, right, directed);
        new SpanWalker(index.searcher(), spanQuery, new DocSnippets(DocSnippets.Usage.POSITIONS, slop), filterQuery).walk(recorder);
        return coocMat;
    }

    /**
     * Builds leading row principal coordinates for hierarchical clustering.
     *
     * <p>
     * The method obtains the tested source table from {@link #coocMat} and uses
     * the same smoothing, expectation, association, and clipping parameters as
     * the semantic map. Its output coordinates are specifically
     * {@code U_k Sigma_k}: the singular-value exponent is fixed to one and no
     * row-mass scaling or row normalisation is applied.
     * </p>
     *
     * @param index Lucene index
     * @param pars resolved HTTP parameters
     * @param meta response metadata collector
     * @param dimensions number of leading dimensions to retain
     * @return HAC row coordinates, or {@code null} when no span query is given
     * @throws IllegalArgumentException if {@code dimensions < 1}
     * @throws IOException if the co-occurrence table cannot be built
     */
    public static SvdLayout hacCoordinates(
        final LuceneIndex index,
        final HttpPars pars,
        final MetaUtil meta,
        final int dimensions
    )
        throws IOException {
        if (dimensions < 1) {
            throw new IllegalArgumentException(
                "dimensions must be at least 1, got " + dimensions);
        }
        final IntMatrixById coocMat = coocMat(index, pars, meta);
        if (coocMat == null) {
            return null;
        }

        final ContingencySvd model = contingencySvd(coocMat, pars);
        final SvdLayout layout = model.principalCoordinates(dimensions);
        putSvdMeta(model, layout, meta);
        meta.put("svdCoordinates", "U_k Sigma_k");
        meta.put("svdDimensions", layout.coords()[0].length);
        return layout;
    }

    /**
     * Prepares association residuals from a co-occurrence table.
     *
     * @param coocMat filled co-occurrence matrix
     * @param pars resolved HTTP parameters
     * @return model ready for decomposition
     */
    private static ContingencySvd contingencySvd(
        final IntMatrixById coocMat,
        final HttpPars pars
    ) {
        final ContingencySvd model = new ContingencySvd(coocMat);
        final Assoc assoc = pars.getEnum("assoc", Assoc.PMI);
        switch (assoc) {
            case PEARSON, G2, FT -> model.expectIpf();
            case PMI, PPMI, SPPMI -> {
                model.smooth(0.5);
                model.expectLog();
            }
        }

        final double shift = assoc == Assoc.SPPMI
            ? pars.getDouble("shift", 1d)
            : 1d;
        model.residual(assoc, shift);

        final double clip = pars.getDouble("clip", 0d);
        if (clip > 0d) {
            model.clipResiduals(clip);
        }
        return model;
    }

    /**
     * Writes the observed co-occurrence contingency table.
     *
     * @param coocMat filled co-occurrence matrix
     * @param writer response writer
     * @throws IOException if the table cannot be written
     */
    private static void writeContingency(
        final IntMatrixById coocMat,
        final Writer writer
    )
        throws IOException {
        final char separator = '\t';
        for (int colRank = 0; colRank < coocMat.colCount(); colRank++) {
            final String label = coocMat.colLabelByRank(colRank);
            writer.append(separator).append(csvEscape(label));
        }
        writer.append('\n');

        for (int rowRank = 0; rowRank < coocMat.rowCount(); rowRank++) {
            final String label = coocMat.rowLabelByRank(rowRank);
            writer.append(csvEscape(label));
            for (int colRank = 0; colRank < coocMat.colCount(); colRank++) {
                writer.append(separator);
                writer.append(Integer.toString(coocMat.countByRank(rowRank, colRank)));
            }
            writer.append('\n');
        }
    }

    /**
     * Writes an Orange native feature table for hierarchical clustering.
     *
     * <p>
     * The first three rows declare column names, types, and roles. Term form,
     * term id, and frequency are metadata; only the {@code U_k Sigma_k}
     * coordinates are continuous input features. Coordinates are written at
     * full {@code double} precision.
     * </p>
     *
     * @param layout HAC principal-coordinate layout
     * @param writer response writer
     * @throws IOException if the table cannot be written
     */
    private static void writeOrangeHac(
        final SvdLayout layout,
        final Writer writer
    )
        throws IOException {
        final char separator = '\t';
        final int dimensions = layout.coords()[0].length;

        writer.append("form").append(separator);
        writer.append("id").append(separator);
        writer.append("freq");
        for (int axis = 0; axis < dimensions; axis++) {
            writer.append(separator).append("svd_").append(Integer.toString(axis + 1));
        }
        writer.append('\n');

        writer.append("string").append(separator);
        writer.append("continuous").append(separator);
        writer.append("continuous");
        for (int axis = 0; axis < dimensions; axis++) {
            writer.append(separator).append("continuous");
        }
        writer.append('\n');

        writer.append("meta").append(separator);
        writer.append("meta").append(separator);
        writer.append("meta");
        for (int axis = 0; axis < dimensions; axis++) {
            writer.append(separator);
        }
        writer.append('\n');

        for (int row = 0; row < layout.size(); row++) {
            final String label = layout.label()[row];
            writer.append(csvEscape(label == null ? "" : label)).append(separator);
            writer.append(Integer.toString(layout.id()[row])).append(separator);
            writer.append(Long.toString(layout.freq()[row]));
            for (final double coordinate : layout.coords()[row]) {
                writer.append(separator).append(Double.toString(coordinate));
            }
            writer.append('\n');
        }
    }

    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final Writer writer = response.getWriter();
        final CsvStep step = pars.getEnum("step", CsvStep.CONTINGENCY);
        switch (step) {
            case CONTINGENCY -> {
                final IntMatrixById coocMat = coocMat(index, pars, meta);
                if (coocMat == null) {
                    meta.toString(writer, pars);
                    return;
                }
                writeContingency(coocMat, writer);
            }
            case HAC -> {
                final int dimensions = pars.getInt("dims", new int[] { 1, 300 }, 50);
                final SvdLayout layout = hacCoordinates(
                    index,
                    pars,
                    meta,
                    dimensions
                );
                if (layout == null) {
                    meta.toString(writer, pars);
                    return;
                }
                writeOrangeHac(layout, writer);
            }
        }
    }

    /**
     * Adds decomposition and expectation diagnostics to response metadata.
     *
     * @param model decomposed contingency-SVD model
     * @param layout projected layout
     * @param meta response metadata collector
     */
    private static void putSvdMeta(
        final ContingencySvd model,
        final SvdLayout layout,
        final MetaUtil meta
    ) {
        meta.put("svdFitConverged", model.fitConverged());
        meta.put("svdFitError", model.fitError());
        meta.put("svdFitIterations", model.fitIterations());
        meta.put("svdRank", layout.inertia().length);
        if (!Double.isNaN(model.smoothK())) {
            meta.put("svdSmooth", model.smoothK());
        }
    }
    
    
    /**
     * Builds a semantic-map layout from HTTP-selected contingency-SVD options.
     *
     * <p>The upstream co-occurrence matrix is already fixed when this method is
     * called. The parameters therefore affect only smoothing, expectation,
     * association residuals, decomposition geometry, and emitted dimensions.</p>
     *
     * @param coocMat filled co-occurrence matrix
     * @param pars resolved HTTP parameters
     * @param meta response metadata collector
     * @return semantic-map layout
     */
    private static SvdLayout semanticMap(
        final IntMatrixById coocMat,
        final HttpPars pars,
        final MetaUtil meta
    ) {
        final ContingencySvd model = contingencySvd(coocMat, pars);
        model.decompose();
        final double weightAxes = pars.getDouble("weight-axes", 1);
        if (weightAxes > 0) model.weightAxes(weightAxes);

        final int dims = pars.getInt("dims", new int[] { 2, 50 }, 6);
        final SvdLayout map = model.project(dims);

        putSvdMeta(model, map, meta);
        return map;
    }

    /**
     * Writes the semantic-map response as JSON.
     *
     * @param index Lucene index
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if index access or response writing fails
     */
    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    )
        throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final IntMatrixById coocMat = coocMat(index, pars, meta);
        final SvdLayout map = coocMat == null
            ? null
            : semanticMap(coocMat, pars, meta);

        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();

            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();

            if (map != null) {
                final int dims = map.coords().length == 0
                    ? 0
                    : map.coords()[0].length;
                final double[] spectrum = map.inertia();
                final double dim1 = spectrum.length > 0 ? spectrum[0] : 0d;
                final double dim2 = spectrum.length > 1 ? spectrum[1] : 0d;
                double emittedInertia = 0d;
                for (int axis = 0; axis < Math.min(dims, spectrum.length); axis++) {
                    emittedInertia += spectrum[axis];
                }

                jw.name("data");
                jw.beginObject();

                jw.name("axes");
                jw.beginObject();
                jw.name("dims").value(dims);
                jw.name("dim1_pct").value(round(dim1, 1));
                jw.name("dim2_pct").value(round(dim2, 1));
                jw.name("cum2_pct").value(round(dim1 + dim2, 1));
                jw.name("emitted_pct").value(round(emittedInertia, 1));
                jw.name("spectrum");
                jw.beginArray();
                for (final double pct : spectrum) {
                    jw.value(round(pct, 1));
                }
                jw.endArray();
                jw.endObject();

                jw.name("nodes");
                jw.beginArray();
                final int nodeCount = map.id().length;
                for (int node = 0; node < nodeCount; node++) {
                    final double[] coords = map.coords()[node];
                    final double x = coords.length > 0 ? coords[0] : 0d;
                    final double y = coords.length > 1 ? coords[1] : 0d;

                    jw.beginObject();
                    jw.name("id").value(map.id()[node]);
                    jw.name("form").value(map.label()[node]);
                    jw.name("freq").value(map.freq()[node]);
                    jw.name("x").value(round(x, 4));
                    jw.name("y").value(round(y, 4));
                    jw.name("cos2").value(round(map.cos2()[node], 4));
                    jw.name("coords");
                    jw.beginArray();
                    for (final double coordinate : coords) {
                        jw.value(round(coordinate, 4));
                    }
                    jw.endArray();
                    jw.endObject();
                }
                jw.endArray();

                jw.endObject();
            }

            jw.endObject();
        }
    }

}
