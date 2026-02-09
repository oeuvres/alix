package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.junit.jupiter.api.Test;

import com.github.oeuvres.alix.common.Upos;


public class FilterAposHyphenFrTest
{
    @Test
    public void test() throws IOException {
        String[] tests = {
            "Serait-ce qu’il y a par là-bas le Mont-de-terre ?"
        };
        Analyzer ana = new AposHyphenAnalyzer();
        for (String s: tests) {
            analyze(ana.tokenStream("_cloud", s));
        }
        ana.close();
    }
    
    private static void analyze(TokenStream tokenStream) throws IOException
    {
        final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAttribute = tokenStream.addAttribute(FlagsAttribute.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            final int flags = flagsAttribute.getFlags();
            String tag = Upos.name(flags);
            System.out.print(""
              + termAttribute.toString()
              + "\t" + tag
              + "\n"
            );
        }
    }


    static public class AposHyphenAnalyzer extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML();
            TokenStream ts = tokenizer;
            ts = new FilterAposHyphenFr(tokenizer);
            return new TokenStreamComponents(tokenizer, ts);
        }

    }
}
