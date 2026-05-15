package com.github.oeuvres.alix.web;

import java.io.IOException;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.spans.CoocListener;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.lucene.util.BitsCollectorManager;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;


/**
 * TODO Javadoc
 */
public final class OpSuggest extends Op
{

    /**
     * Build the terms to search in.
     * No ranking here, will be done with the suggest.
     * 
     * @param index
     * @param pars
     * @param meta
     * @return
     * @throws IOException
     */
    private TopTerms topTerms(final LuceneIndex index, final HttpPars pars, final OpMeta meta) throws IOException
    {
        String textField = pars.getString(FTEXT, index.content());
        final FlucText textFluc = index.flucText(textField);
        if (textFluc == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + textField + "' not found or not a text field");
            return null;
        }
        meta.put("textField", textField);
        // Build a filter query from years and tags
        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        // get a full topTerms
        TopTerms topTerms = textFluc.topTerms();
        // no queries, all terms from the field
        if (filterQuery == null && spanQuery == null) {
            return topTerms;
        }
        // no coocs, doc filter query, contrastive terms from a part
        else if (spanQuery == null) {
            final FixedBitSet focusDocs = index.searcher().search(filterQuery, new BitsCollectorManager(index.searcher()));
            // should set focusTermFreq for the selected terms
            return topTerms.select(index.reader(), focusDocs);
        }
        // coocs, with or without doc filter TODO
        else {
            /*
            final int ctx = pars.getInt(CTX, CTX_RANGE, CTX_DEFAULT, CTX);
            final int left = pars.getInt(CTX_LEFT, CTX_RANGE, ctx, CTX_LEFT);
            final int right = pars.getInt(CTX_RIGHT, CTX_RANGE, ctx, CTX_RIGHT);
            */
            final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
            final CoocListener listener = new CoocListener(
                textFluc.termStats(),
                textFluc.termRail(),
                slop,
                slop);
            final SpanWalker walker = new SpanWalker(
                index.searcher(),
                spanQuery,
                filterQuery,
                listener,
                textFluc.termLexicon()
            );
            
            listener.bindTo(topTerms.buffers());
            walker.walk(0);
            topTerms.setTotals(listener.coocTokens(), listener.coocDocsTotal());
            
            meta.put("focusTokens", listener.coocTokens());
            meta.put("focusDocs", listener.coocDocsTotal());
            meta.put("hits", walker.hits());
            return topTerms;
        }
    }
    

    

    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final OpMeta meta = new OpMeta();
        TopTerms topTerms = topTerms(index, pars, meta);
        if (topTerms != null) {
            String textField = pars.getString(FTEXT, index.content());
            final FlucText textFluc = index.flucText(textField);
            final String infix = pars.getString(INFIX, "").replace("(", "").replace(")", "");;
            final int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
            meta.put("infix", infix);
            meta.put("limit", topK);
            // should rank the terms
            textFluc.termSuggest().suggest(topTerms, infix, topK);
        }
        
        
        
        try (JsonWriter jw = jsonWriter(response)) {
            jw.beginObject();

            // meta
            jw.name("meta");
            jw.beginObject();
            meta.toJson(jw, pars);
            jw.endObject(); // meta

            // data
            if (topTerms != null) {
                jw.name("data");
                jw.beginArray();
                int rank = 1;
                for (TermEntry term : topTerms) {
                    jw.beginObject();
                    jw.name("rank").value(rank++);
                    jw.name("form").value(term.form());
                    jw.name("html").value(term.hilite());
                    jw.name("docs").value(term.docs());
                    jw.name("fieldDocs").value(term.fieldDocs());
                    jw.name("freq").value(term.freq());
                    jw.name("fieldFreq").value(term.fieldFreq());
                    // score has no sense here
                    jw.endObject();
                }
                jw.endArray();
            }

            jw.endObject();
        }
    }
}
