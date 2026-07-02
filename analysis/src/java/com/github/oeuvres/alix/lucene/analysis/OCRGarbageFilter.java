package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

public class OCRGarbageFilter extends TokenFilter
{
    /** The current token term. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    
    private final KeywordAttribute keywordAtt = addAttribute(KeywordAttribute.class);
    
    public OCRGarbageFilter(TokenStream input)
    {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken()) return false;
        if (keywordAtt.isKeyword()) return true;
        if (termAtt.isEmpty()) return true;
        
        char lastChar = termAtt.charAt(termAtt.length() - 1);
        if (lastChar == '-' || lastChar == '\'') {
            termAtt.setEmpty();
        }
        
        return true;
    }

    
}
