package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.spans.ResultsSnippets;
import com.github.oeuvres.alix.lucene.spans.Snippets;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.IdfTermScorer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OpSnippets extends Op
{
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        final OpMeta meta = new OpMeta();
        Writer writer = response.getWriter();
        final HttpPars pars = new HttpPars(request, response);
        final String docName = pars.getString(DOCNAME, null);
        int docId = pars.getInt(DOCID, -1);
        if (docId == -1) {
            docId = AlixServlet.docIdByName(index, docName);
        }
        if (docId == -1) {
            response.setStatus(404);
            writer
            .append("<p class=\"error\">")
            .append(DOCNAME)
            .append("=")
            .append(String.valueOf(docName))
            .append("  ")
            .append(DOCID)
            .append("=")
            .append(String.valueOf(docId))
            .append(" not found")
            .append("</p>")
            ;
            return;
        }
        final StoredFields storedFields = index.reader().storedFields();
        Document doc = storedFields.document(docId);
        String content = doc.get(index.content());
        if (content == null || content.isBlank()) {
            response.setStatus(404);
            writer
            .append("<p class=\"error\">")
            .append(DOCNAME)
            .append("=")
            .append(String.valueOf(docName))
            .append("  ")
            .append(DOCID)
            .append("=")
            .append(String.valueOf(docId))
            .append(" empty")
            .append("</p>")
            ;
            return;
        }
        SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) {
            writer
            .append("<p class=\"error\">")
            .append("No query to extract snippets")
            .append(meta.toHtml(pars))
            .append("</p>");
            return;
        }
        
        final String contentFname = pars.getString(FTEXT, index.content());
        final FlucText contentFluc = index.flucText(contentFname);
        final TermRail rail = contentFluc.termRail();
        final TermStats fieldStats = contentFluc.termStats();
        final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);
        final double[]termWeights = fieldStats.termWeights(index.reader(), new IdfTermScorer.BM25(idfExp));

        // With a span walker, populate the snippets to list
        final int snipLimit = pars.getInt(SNIPPETS, SNIPPETS_RANGE, SNIPPETS_DEFAULT, SNIPPETS);
        final int ctx = pars.getInt(CTX, CTX_RANGE, CTX_DEFAULT, CTX);
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        
        
        // final TermRail rail = flucText.termRail();
        // final double[] termWeights;

        
        final ResultsSnippets results = new ResultsSnippets(
            writer, 
            index.reader().storedFields(), 
            snipLimit,
            index.locale()
        ).ctx(ctx)
        .rail(rail)
        .termWeights(termWeights)
        .urlTemplate("{docname}?" + pars.queryString(FTEXT, Q, CTX) + "&amp;slop=" + slop);
        Snippets snippets = new Snippets(Snippets.Usage.OFFSETS, slop);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            snippets
        );
        walker.visit(docId);
        // list all snippets in document order
        results.snippets(docId, snippets);
        
    }

}
