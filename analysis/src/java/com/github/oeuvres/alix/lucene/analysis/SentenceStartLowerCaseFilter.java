package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.util.Char;

public class SentenceStartLowerCaseFilter extends TokenFilter
{
    private final LemmaLexicon lex;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    // Which way? Factory? Constructor?
    // private final LookupMode lowercase = ???;


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
        
        // DO NOT PROPOSE THINGS FOR HERE, KEEP FOCUS ON LookupMode API for now
        
        // term without initial upper case, do nothing
        if (!Char.isUpperCase(termAtt.charAt(0)) ) return true;
        final int formId = lex.findFormId(termAtt);
        // term not known as a common word, keep as is
        if (formId < 0) return true;
        // copy canonical version of the term
        lex.copyForm(formId, termAtt);

        return true;
    }

}
