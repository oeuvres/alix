package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.io.Writer;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.ResultsSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.IdfTermScorer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Returns snippets for one doc
 */
public class OpSnippets extends Op
{
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        Writer writer = response.getWriter();
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
        SpanQuery spanQuery = spanQuery(index, pars, meta);
        if (spanQuery == null) {
            writer
            .append("<p class=\"error\">")
            .append("No query to extract snippets")
            .append("</p>");
            meta.toHtml(writer, pars);
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
            index.locale(),
            null
        ).ctx(ctx)
        .urlTemplate("{docname}?" + pars.queryString(FTEXT, Q, CTX) + "&amp;slop=" + slop);
        DocSnippets snippets = new DocSnippets(DocSnippets.Usage.OFFSETS, slop);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            snippets
        );
        walker.visit(docId);
        // list all snippets in document order
        results.snippets(docId, snippets);
        
    }

    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        final String docName = pars.getString(DOCNAME, null);
        int docId = pars.getInt(DOCID, -1);
        if (docId == -1) {
            docId = AlixServlet.docIdByName(index, docName);
        }
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final SpanQuery spanQuery = spanQuery(index, pars, meta);
        DocSnippets snippets = null;
        if (docId == -1) {
            response.setStatus(404);
            meta.put("error", DOCNAME + "=" + docName + " " + DOCID + "=" + docId + " not found");
        }
        else if (spanQuery == null) {
            meta.put("error", "no query to extract snippets");
        }
        else {
            snippets = new DocSnippets(DocSnippets.Usage.OFFSETS, slop, true);
            final SpanWalker walker = new SpanWalker(index.searcher(), spanQuery, snippets);
            walker.visit(docId);
        }

        try (JsonWriter json = new JsonWriter(response.getWriter())) {
            json.beginObject();

            json.name("meta");
            json.beginObject();
            meta.toJson(json, pars);
            json.endObject();

            if (snippets != null) {
                json.name("data");
                json.beginObject();
                json.name("docId").value(docId);
                json.name("slop").value(slop);

                // raw spans in Lucene emission order, before merge
                final int rawCount = snippets.rawSpanCount();
                boolean rawMonotonic = true;
                int previousStart = -1;
                json.name("rawSpans");
                json.beginArray();
                for (int i = 0; i < rawCount; i++) {
                    final int start = snippets.rawSpanStart(i);
                    final int end = snippets.rawSpanEnd(i);
                    if (start < previousStart) {
                        rawMonotonic = false;
                    }
                    previousStart = start;
                    json.beginObject();
                    json.name("start").value(start);
                    json.name("end").value(end);
                    json.endObject();
                }
                json.endArray();
                json.name("rawMonotonic").value(rawMonotonic);

                // merged snippets, with pairwise overlap detection
                final int snipCount = snippets.count();
                json.name("snippets");
                json.beginArray();
                for (int i = 0; i < snipCount; i++) {
                    json.beginObject();
                    json.name("startPos").value(snippets.snipStartPosition(i));
                    json.name("endPos").value(snippets.snipEndPosition(i));
                    json.name("startOffset").value(snippets.snipStartOffset(i));
                    json.name("endOffset").value(snippets.snipEndOffset(i));
                    json.endObject();
                }
                json.endArray();
                json.name("snippetsOverlap").value(snippetsOverlap(snippets, snipCount));

                // deduplicated matched leaves
                final int matchCount = snippets.matchCount();
                json.name("matches");
                json.beginArray();
                for (int i = 0; i < matchCount; i++) {
                    json.beginObject();
                    json.name("pos").value(snippets.matchPos(i));
                    json.name("startOffset").value(snippets.matchStartOffset(i));
                    json.name("endOffset").value(snippets.matchEndOffset(i));
                    json.endObject();
                }
                json.endArray();

                json.endObject(); // data
            }

            json.endObject();
        }
    }

    /**
     * Returns whether any two merged snippet position ranges intersect. Merged ranges must be
     * disjoint; an intersection is the direct symptom of an out-of-order span fold.
     */
    private static boolean snippetsOverlap(final DocSnippets snippets, final int count)
    {
        for (int i = 0; i < count; i++) {
            final int aStart = snippets.snipStartPosition(i);
            final int aEnd = snippets.snipEndPosition(i);
            for (int j = i + 1; j < count; j++) {
                final int bStart = snippets.snipStartPosition(j);
                final int bEnd = snippets.snipEndPosition(j);
                if (aStart < bEnd && bStart < aEnd) {
                    return true;
                }
            }
        }
        return false;
    }

}
