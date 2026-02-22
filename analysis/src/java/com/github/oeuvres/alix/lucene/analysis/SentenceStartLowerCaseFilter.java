package com.github.oeuvres.alix.lucene.analysis;

import static com.github.oeuvres.alix.common.Upos.*;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.util.TermProbe;

/**
 * Suppose a Tokenizer like MLTokenizer that keep punctuation to have sentence boudaries.
 */

public class SentenceStartLowerCaseFilter extends TokenFilter
{
    private final LemmaLexicon lex;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final TermProbe termProbe = new TermProbe();
    private boolean lastTokenIsSentenceEnd = true;


    public SentenceStartLowerCaseFilter(TokenStream input, LemmaLexicon lex)
    {
        super(input);
        this.lex = lex;
    }
    
    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken()) return false;
        if (keywordAtt.isKeyword()) return true;
        final int posId = posAtt.getPos();
        // let’s try to get first word after the end of a sentence
        if (posId == XML.code) {
            // a filter should have interpret here the XML tags which are sentence boundaries like <td>
            // should be something like <i>
            return true;
        }
        if (posId == PUNCTclause.code) {
            // a sentence may start by parenthesis and some other clause punctuation
            return true;
        }
        if (posId == PUNCTsent.code || posId == PUNCTsection.code) {
            lastTokenIsSentenceEnd = true;
            return true;
        }
        if (!lastTokenIsSentenceEnd) {
            // words in sentence, do nothing
            return true;
        }
        lastTokenIsSentenceEnd = false; // do not forget to reset flag
        // test lower case version of that word
        termProbe.copyFrom(termAtt).toLowerCase();
        final int formId = lex.findFormId(termProbe);
        // term not known as a common word, keep as is
        if (formId < 0) return true;
        // copy canonical version of the term
        lex.copyForm(formId, termAtt);
        return true;
    }

}
