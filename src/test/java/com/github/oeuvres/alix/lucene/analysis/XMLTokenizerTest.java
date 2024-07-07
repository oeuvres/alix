package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

public class XMLTokenizerTest {

    public static void main(String[] args) throws IOException
    {
        XMLTokenizer tokenizer = new XMLTokenizer();
        String text = "le moindre fossé sont peuplés de <i>stagnalis</i> typiques ou très <mark id=\"pos22862\">allongées<a class=\"noteref\" href=\"#note24\" id=\"note24_\" epub:type=\"noteref\">22</a>.</mark> Bien plus, la forme de Noville";
        tokenizer.setReader(new StringReader(text));
        analyze(tokenizer, text);
        System.out.println("-------------");
        Analyzer ana = new TestAnalyzer();
        analyze(ana.tokenStream("field", text), text);
    }
    
    static void analyze(TokenStream tokenStream, String text) throws IOException
    {
        

        final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
        final OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
        // final TypeAttribute typeAttribute = tokenStream.addAttribute(TypeAttribute.class);
        final PositionIncrementAttribute posIncAttribute = tokenStream.addAttribute(PositionIncrementAttribute.class);
        // final PositionLengthAttribute posLenAttribute = tokenStream.addAttribute(PositionLengthAttribute.class);
        
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            System.out.print(
                "|"
              + termAttribute.toString() + "|\t|" 
              + text.substring(offsetAttribute.startOffset(),  offsetAttribute.endOffset()) + "|\t" 
              + offsetAttribute.startOffset() + "\t"
              + offsetAttribute.endOffset() + "\t" 
              + posIncAttribute.getPositionIncrement() + "\n"
            );
        }
    }

    static public class TestAnalyzer extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new XMLTokenizer(); // segment words
            TokenStream result = new FrLemFilter(tokenizer); // provide lemma+pos
            return new TokenStreamComponents(tokenizer, result);
        }

    }

}
