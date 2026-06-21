package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Locale;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.CoocMatSnippets;
import com.github.oeuvres.alix.lucene.snippets.Snippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.util.IntMatrixById;
import com.github.oeuvres.alix.util.AssociationMeasure;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OpCoocs extends Op
{

    @Override
    protected void csv(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    )
        throws IOException {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        TopTerms topTerms = OpTerms.topTerms(index, pars, meta);

        final Writer writer = response.getWriter();
        if (topTerms == null) {
            meta.toString(writer, pars);
            return;
        }

        // topTerms may contain pivots
        int[] pivotIds = (int[]) meta.get("pivotIds");
        final IntList termIds = new IntList(topTerms.size());
        for (TermEntry term : topTerms) {
            final int termId = term.termId();
            if (Arrays.binarySearch(pivotIds, termId) >= 0)
                continue;
            termIds.push(termId);
        }
        final int terms = termIds.size();
        final boolean directed = pars.getBoolean("directed", false);

        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        Snippets snippets = new Snippets(Snippets.Usage.POSITIONS, slop);
        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        final String contentFname = pars.getString(FTEXT, index.content());
        final FlucText contentFluc = index.flucText(contentFname);

        final int left = pars.getInt(LEFT, LEFT_RANGE, slop);
        final int right = pars.getInt(RIGHT, RIGHT_RANGE, slop);
        final IntMatrixById coocMat = new IntMatrixById(termIds);
        final CoocMatSnippets coocRecorder = new CoocMatSnippets(
                coocMat, contentFluc.termRail(), left, right, directed
        );

        if (spanQuery == null) {
            writer.append("TODO\n");
            meta.toString(writer, pars);
            return;
        }

        final SpanWalker walker = new SpanWalker(
                index.searcher(), spanQuery, snippets, filterQuery
        );
        walker.walk(coocRecorder);

        final AssociationMeasure scorer = switch (pars.getString("assoc", "raw"))
            {
            case "g2" -> new AssociationMeasure.LogLikelihood();
            case "ppmi" -> new AssociationMeasure.Ppmi();
            case "npmi" -> new AssociationMeasure.Npmi();
            case "logdice" -> new AssociationMeasure.LogDice();
            case "raw" -> new AssociationMeasure.Raw();
            default -> new AssociationMeasure.Raw();
            };

        final long[] rowMargin = new long[terms];
        final long[] colMargin = new long[terms];
        long total = 0L;
        for (int row = 0; row < terms; row++) {
            for (int col = 0; col < terms; col++) {
                if (row == col) continue; // diagonal is not part of the count
                final long v = coocMat.countByRank(row, col);
                rowMargin[row] += v;
                colMargin[col] += v;
                total += v;
            }
        }

        final DecimalFormat fmt = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US));

        TermLexicon lexicon = contentFluc.termLexicon();
        for (int col = 0; col < terms; col++) {
            final int termId = coocMat.id(col);
            writer.append(',').append(lexicon.form(termId));
        }
        writer.append('\n');
        for (int row = 0; row < terms; row++) {
            final int termId = coocMat.id(row);
            writer.append(lexicon.form(termId));
            for (int col = 0; col < terms; col++) {
                double score;
                if (row == col)
                    score = 0;
                else
                    score = scorer.score(coocMat.countByRank(row, col), rowMargin[row], colMargin[col], total);
                if (score == Double.NEGATIVE_INFINITY) score = 0;
                writer.append(',').append(fmt.format(score));
            }
            writer.append('\n');
        }

    }

}
