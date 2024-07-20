package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

public class FrTokenizerTest {

    public static void main(String[] args) throws IOException
    {
        Analyzer analyzer = new AnalyzerAlix();
        analyze(analyzer, "text_cloud", "le moindre fossé sont peuplés de <i>stagnalis</i> typiques ou très <mark id=\"pos22862\">allongées<a class=\"noteref\" href=\"#note24\" id=\"note24_\" epub:type=\"noteref\">22</a>.</mark> Bien plus, la forme de Noville");
    }
    
    static void analyze(Analyzer analyzer, String field, String text) throws IOException
    {
        TokenStream tokenStream = analyzer.tokenStream(field, text);
        

        final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
        final OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
        // final TypeAttribute typeAttribute = tokenStream.addAttribute(TypeAttribute.class);
        final PositionIncrementAttribute posIncAttribute = tokenStream.addAttribute(PositionIncrementAttribute.class);
        // final PositionLengthAttribute posLenAttribute = tokenStream.addAttribute(PositionLengthAttribute.class);
        
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            System.out.print(
                termAttribute.toString() + "\t" 
              + offsetAttribute.startOffset() + "\t" 
              + offsetAttribute.endOffset() + "\t" 
              + posIncAttribute.getPositionIncrement() + "\n"
            );
        }
    }

}
