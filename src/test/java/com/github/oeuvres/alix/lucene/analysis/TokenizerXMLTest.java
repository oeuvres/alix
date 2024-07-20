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
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsOrthAtt;

public class TokenizerXMLTest {

    public static void main(String[] args) throws IOException
    {
        TokenizerML tokenizer = new TokenizerML();
        String text = "<p><b>Lexical tokenization</b> is conversion of a text into (semantically or syntactically) meaningful <i>lexical tokens</i> belonging to categories defined by a \"lexer\" program. In case of a <a href=\"/wiki/Natural_language\" title=\"Natural language\">natural language</a>, those categories include nouns, verbs, adjectives, punctuations etc.";
        
        tokenizer.setReader(new StringReader(text));
        analyze(tokenizer, text);
        System.out.println("-------------");
        Analyzer ana = new TestAnalyzer();
        analyze(ana.tokenStream("field", text), text);
        ana.close();
    }
    
    static void analyze(TokenStream tokenStream, String text) throws IOException
    {
        

        final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAttribute = tokenStream.addAttribute(FlagsAttribute.class);
        final OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
        // final TypeAttribute typeAttribute = tokenStream.addAttribute(TypeAttribute.class);
        final PositionIncrementAttribute posIncAttribute = tokenStream.addAttribute(PositionIncrementAttribute.class);
        // final PositionLengthAttribute posLenAttribute = tokenStream.addAttribute(PositionLengthAttribute.class);
        final CharsAtt orthAtt = (CharsAtt) tokenStream.addAttribute(CharsOrthAtt.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            System.out.print(""
              + termAttribute.toString() + "\t" 
              + Tag.name(flagsAttribute.getFlags()) + "\t" 
              // + orthAtt.toString() + "|\t|" 
              // + text.substring(offsetAttribute.startOffset(),  offsetAttribute.endOffset()) + "|\t" 
              // + offsetAttribute.startOffset() + "\t"
              // + offsetAttribute.endOffset() + "\t" 
              // + posIncAttribute.getPositionIncrement()
              + "\n"
            );
        }
    }

    static public class TestAnalyzer extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML(); // segment words
            TokenStream result = new FilterLemmatize(tokenizer); // provide lemma+pos
            return new TokenStreamComponents(tokenizer, result);
        }

    }

}
