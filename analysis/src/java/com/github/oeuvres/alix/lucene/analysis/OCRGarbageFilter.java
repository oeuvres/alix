package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import com.github.oeuvres.alix.util.Char;

public class OCRGarbageFilter extends FilteringTokenFilter 
{
    /** The current token term. */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    
    public OCRGarbageFilter(TokenStream input)
    {
        super(input);
    }

    @Override
    protected boolean accept() throws IOException
    {
        final int len = termAtt.length();
        if (len == 0) return false;
        char lastChar = termAtt.charAt(len -1);
        if (lastChar == '-' || lastChar == '.') return false;
        int vowels = 0;
        for (int pos = 0; pos < len; pos++) {
            final char c = termAtt.charAt(pos);
            if (c == '.') return false;
            // P1AGET
            if (Char.isDigit(c) || Char.isMath(c)) return false;
            // πjimage-souvenir
            if (Char.isLetter(c) && !Char.isLatin(c)) return false;
            // count vowels
            // if (??) vowels++;
        }
        // if (vowels < 1) return false;
        return true;
    }


    
}
