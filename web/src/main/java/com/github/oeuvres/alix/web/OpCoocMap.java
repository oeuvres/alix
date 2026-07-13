package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.lucene.terms.TopTerms.TermValue.FREQ;
import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.SingularValueDecomposition;
import org.hipparchus.special.Gamma;

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


public final class OpCoocMap extends Op
{
    
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
        
        final TopCoocSnippets consumer = new TopCoocSnippets(
            contentFluc.termStats(),
            contentFluc.termRail(),
            left,
            right);
        consumer.bindTo(topTerms.buffers());
        walker.walk(consumer);
        topTerms.setTotals(consumer.coocTokens(), consumer.coocDocsTotal());
        meta.put("pivotIds", pivotIds);
        meta.put("fieldWidth", contentFluc.termStats().fieldWidth());
        meta.put("fieldTokens", contentFluc.termStats().fieldTokens());
        meta.put("focusTokens", consumer.coocTokens());
        meta.put("focusDocs", consumer.coocDocsTotal());
        meta.put("hits", walker.hits());
        
        final TermFlag tflag = pars.getEnum(TFLAG, TermFlag.NULL);
        final KeynessScorer scorer = tsort(pars);
        
        // get sorted rows, maybe filtered by a flag, will be the rows of interest to display
        // Should we filter pivots now or after?
        topTerms.rank(scorer, terms + pivotIds.length, tflag).promote(pivotIds, FREQ);
        final IntList termIds = new IntList(topTerms.size());
        BitSet rows = new BitSet(contentLexicon.vocabSize());
        String[] rowTopFreq = new String[terms];
        int i = 0;
        for (final TermEntry term : topTerms) {
            final int termId = term.termId();
            // shall we cut pivotIds from rows for SVD? Does it add signal or noise?
            if (pivotIds != null && Arrays.binarySearch(pivotIds, termId) >= 0) continue;
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
            // keep same scorer? do raw sort add signal or noise?
            topTerms.rank(scorer, terms + pivotIds.length +cols, tflag).promote(pivotIds, FREQ); // TermFlag.NULL

            // here termIds contains rows, add other terms
            int k = 0;
            final String[] colForms = new String[cols];
            for (final TermEntry term : topTerms) {
                final int termId = term.termId();
                if (pivotIds != null && Arrays.binarySearch(pivotIds, termId) >= 0) continue;
                if (rows.get(termId)) continue;
                termIds.push(termId);
                colForms[k] = term.form() + " (" + term.freq() + " ; " + term.score() + ")";
                if (++k >= cols) break;
            }
            colIds = termIds.toUniq();
            meta.put("cols", colForms);
        }
        final IntMatrixById coocMat = new IntMatrixById(
            rowIds, 
            colIds,
            id -> contentLexicon.form(id)
        );
        final boolean directed = pars.getBoolean("directed", false);
        final CoocMatSnippets recorder = new CoocMatSnippets(coocMat, contentFluc.termRail(), left, right, directed);
        new SpanWalker(index.searcher(), spanQuery, new DocSnippets(DocSnippets.Usage.POSITIONS, slop), filterQuery).walk(recorder);
        return coocMat;
    }

    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final IntMatrixById coocMat = coocMat(index, pars, meta);
        final Writer writer = response.getWriter();
        if (coocMat == null) {
            meta.toString(writer, pars);
            return;
        }
        // final DecimalFormat fmt = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));
        final char sep = '\t';
        for (int colRank = 0; colRank < coocMat.colCount(); colRank++) {
            String label = coocMat.colLabelByRank(colRank);
            writer.append(sep).append(csvEscape(label));
        }
        writer.append('\n');
        for (int rowRank = 0; rowRank < coocMat.rowCount(); rowRank++) {
            String label = coocMat.rowLabelByRank(rowRank);
            writer.append(csvEscape(label));
            for (int colRank = 0; colRank < coocMat.colCount(); colRank++) {
                writer.append(sep).append("" + coocMat.countByRank(rowRank, colRank));
            }
            writer.append('\n');
        }
        SvdLayout layout = new ContingencySvd(coocMat)
            .freq(rowFreq)            // display marginal, uninterpreted
            // .smooth(0.5)              // or smoothAuto(), or skip
             .expectIpf()              // or expectLog()
             .residual(Assoc.G2)        // any cell function against the fitted e
             .massScale(false)         // true restores textbook CA geometry
             .svd()
             .layout(6);

        /*
        for (String line: (String[])meta.get("rowTopFreq")) {
            writer.append(line + '\n');
        }*/
    }
    
    
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
        
        /*
        try (JsonWriter jw = Op.jsonWriter(response)) {
            jw.beginObject();

            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject();

            if (map != null) {
                final int dims = map.coords()[0].length;
                jw.name("data");
                jw.beginObject();
                jw.name("axes");
                jw.beginObject();
                jw.name("dims").value(dims);
                jw.name("dim1_pct").value(round(map.inertia()[0], 1));
                jw.name("dim2_pct").value(round(map.inertia()[1], 1));
                jw.name("cum2_pct").value(round(map.inertia()[0] + map.inertia()[1], 1));
                jw.name("spectrum");
                jw.beginArray();
                for (final double pct : map.inertia()) {
                    jw.value(round(pct, 1));
                }
                jw.endArray();
                jw.endObject();
                jw.name("nodes");
                jw.beginArray();
                for (int i = 0; i < map.form().length; i++) {
                    jw.beginObject();
                    jw.name("id").value(map.id()[i]);
                    jw.name("form").value(map.form()[i]);
                    jw.name("freq").value(map.freq()[i]);
                    jw.name("x").value(round(map.coords()[i][0], 4));
                    jw.name("y").value(round(map.coords()[i][1], 4));
                    jw.name("cos2").value(round(map.cos2()[i], 4));
                    jw.name("coords");
                    jw.beginArray();
                    for (int k = 0; k < dims; k++) {
                        jw.value(round(map.coords()[i][k], 4));
                    }
                    jw.endArray();
                    jw.endObject();
                }
                jw.endArray();
                jw.endObject();
            }

            jw.endObject();
        }
        */

    }

}
