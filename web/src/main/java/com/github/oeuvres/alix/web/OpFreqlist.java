package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.Writer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;



public final class OpFreqlist extends Op
{

    
    private TopTerms topTerms(final LuceneIndex index, final HttpPars pars, final MetaUtil meta) throws IOException
    {
        final int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        String textName = pars.getString(FTEXT, index.content());
        final FlucText textFluc = index.flucText(textName);
        if (textFluc == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + textName + "' not found or not a text field");
            return null;
        }
        meta.put("textField", textName);
        // filter query is not yet used
        TermLexicon lexicon = textFluc.termLexicon();
        TermStats stats = textFluc.termStats();
        meta.put("fieldWidth", stats.fieldWidth());
        meta.put("fieldTokens", stats.fieldTokens());
        
        final int vocabSize = lexicon.vocabSize();
        final double[] freqs = new double[vocabSize];
        for (int termId = 1; termId < vocabSize; termId++) {
            String term = lexicon.form(termId);
            if (!Character.isUpperCase(term.charAt(0))) continue;
            freqs[termId] = stats.termFreq(termId);
        }
        TopTerms topTerms = textFluc.topTerms();
        topTerms.ranking(freqs, topK);
        // sort by freq
        return topTerms;
    }
    
    
    @Override
    protected void html(LuceneIndex index, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        final HttpPars pars = (HttpPars) request.getAttribute(ALIX_PARS);
        final MetaUtil meta = (MetaUtil) request.getAttribute(ALIX_META);
        TopTerms topTerms = topTerms(index, pars, meta);
        Writer writer = response.getWriter();
        if (topTerms != null) {
            writer.append("<table class=\"terms\">\n");
            int rank = 1;
            for (TermEntry term : topTerms) {
                writer.append("  <tr>\n")
                  .append("    <th class=\"term\">%d</th>\n".formatted(rank++))
                  .append("    <td class=\"term\">%s</td>\n".formatted(term.form()))
                  .append("    <td class=\"count\" align=\"right\">%d</td>\n".formatted(term.freq()))
                  .append("    <td class=\"score\" align=\"right\">%f</td>\n".formatted(term.score()))
                  .append("  </tr>\n");
            }
            writer.append("</table>\n");
        }
        else {
            writer.append(meta.toString());
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
        final MetaUtil meta = new MetaUtil();
        TopTerms topTerms = topTerms(index, pars, meta);
        TermsUtil.json(request, response, topTerms);
    }
}
