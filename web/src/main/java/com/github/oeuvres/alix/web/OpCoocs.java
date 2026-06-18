package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.FTEXT;
import static com.github.oeuvres.alix.web.Pars.SLOP;
import static com.github.oeuvres.alix.web.Pars.SLOP_DEFAULT;
import static com.github.oeuvres.alix.web.Pars.SLOP_RANGE;

import java.io.IOException;
import java.io.Writer;

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
import com.github.oeuvres.alix.util.CoocMat;
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
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        TopTerms topTerms = OpTerms.topTerms(index, pars, meta);
        final Writer writer = response.getWriter();
        if (topTerms == null) {
            meta.toString(writer, pars);
            return;
        }
        
        
        final int terms = topTerms.size();
        final IntList termIds= new IntList(terms);
        for (TermEntry term : topTerms) {
            termIds.push(term.termId());
        }
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        Snippets snippets = new Snippets(Snippets.Usage.POSITIONS, slop);
        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        final String contentFname = pars.getString(FTEXT, index.content());
        final FlucText contentFluc = index.flucText(contentFname);

        final CoocMat coocMat = new CoocMat(termIds);
        final CoocMatSnippets coocRecorder = new CoocMatSnippets(
            coocMat, contentFluc.termRail(), slop, slop
        );
        

        
        
        if (spanQuery == null) {
            writer.append("TODO\n");
            meta.toString(writer, pars);
            return;
        }
        
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            snippets,
            filterQuery
        );
        walker.walk(coocRecorder);
        
        TermLexicon lexicon = contentFluc.termLexicon();
        for (int col = 0; col < terms; col++) {
            final int termId=coocMat.id(col);
            writer.append(',').append(lexicon.form(termId));
        }
        writer.append('\n');
        for (int row = 0; row < terms; row++) {
            final int termId=coocMat.id(row);
            writer.append(lexicon.form(termId));
            for (int col = 0; col < terms; col++) {
                writer.append(',').append(Integer.toString(coocMat.countByRank(row, col)));
            }
            writer.append('\n');
        }


    }

}
