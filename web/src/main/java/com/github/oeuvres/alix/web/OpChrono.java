package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.*;

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.output.HistoNum;
import com.github.oeuvres.alix.lucene.output.HistoNum.Col;
import com.github.oeuvres.alix.lucene.snippets.HistoSnippets;
import com.github.oeuvres.alix.lucene.snippets.DocSnippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.web.util.HttpPars;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OpChrono extends Op
{
    private HistoNum histoNum(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        String yearName = pars.getString(FYEAR, index.year());
        final FlucNum flucYear = index.flucNum(yearName);
        if (flucYear == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + yearName + "' not found or not a numeric field valid for histogram");
        }
        String textName = pars.getString(FTEXT, index.content());
        final FlucText flucText = index.flucText(textName);
        if (flucText == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + textName + "' not found or not a text field");
        }
        if (flucText == null || flucYear == null) {
            return null;
        }
        meta.put("yearField", textName);
        meta.put("textField", yearName);
        
        HistoNum histo = flucYear.histo();
        // Build a document filter query from tags or types (but not years?)
        final Query filterQuery = typeQuery(index, pars);
        // TODO here, filter 
        histo.distribute(flucText.termStats(), null);
        final SpanQuery spanQuery = spanQuery(index, pars);
        if (spanQuery == null) {
            return histo;
        }
        meta.put("spanQuery", spanQuery.toString());
        // same as for the span query parser
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            new DocSnippets(DocSnippets.Usage.FREQS, slop),
            filterQuery
        );
        final HistoSnippets consumer = new HistoSnippets(histo);
        walker.walk(consumer);
        return histo;
    }
    
    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_PARS);
        final HistoNum histo = histoNum(index, pars, meta);
        
        try (JsonWriter json = jsonWriter(response)) {
            json.beginObject();

            // meta
            json.name("meta");
            json.beginObject();
            meta.toJson(json, pars);
            json.endObject(); // meta
            
            // data
            if (histo != null) {
                json.name("data");
                json.beginObject();
                json.name("length").value(histo.length());
                json.name("min").value(histo.min());
                json.name("max").value(histo.max());
                json.name("cols");
                json.beginArray();
                Set<Col> cols = histo.cols();
                for (Col col : cols) {
                    json.value(col.toString());
                }
                json.endArray();
                
                json.name("rows");
                json.beginArray();
                for (int row=0; row < histo.length(); row++) {
                    json.beginObject();
                    json.name("value").value(histo.min() + row);
                    for (Col col : cols) {
                        json.name(col.toString());
                        switch (col) {
                            case DOCS_ALL -> json.value(histo.valueDocsAll[row]);
                            case DOCS     -> json.value(histo.valueDocs[row]);
                            case WIDTH    -> json.value(histo.valueWidth[row]);
                            case TOKENS   -> json.value(histo.valueTokens[row]);
                            case SNIPPETS    -> json.value(histo.valueSnippets[row]);
                            case SCORE    -> json.value(histo.valueScore[row]);
                        }
                    }
                    json.endObject();
                }
                json.endArray();
                json.endObject();
            }

            
            json.endObject();
        }
    }
}
