package alix.lucene.analysis;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import alix.fr.Tag;

public class TestTokenCompound
{
  static class AnalyzerCompound extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrTokenLem(source);
      result = new TokenCompound(result, 5);
      return new TokenStreamComponents(source, result);
    }

  }

  public static void main(String[] args) throws IOException
  {
    // text to tokenize
    final String text = "V. Hugo. Victor Hugo. Jules Marie, Pierre de Martin ou Peut-être lol ? Les U.S.A., un grand pays. L'orange et l'Europe de l'acier. ";

    Analyzer[] analyzers = { new AnalyzerCompound() };
    for (Analyzer analyzer : analyzers) {
      System.out.println(analyzer.getClass());
      System.out.println();
      TokenStream stream = analyzer.tokenStream("content", new StringReader(text));

      // get the CharTermAttribute from the TokenStream
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
      FlagsAttribute flags = stream.addAttribute(FlagsAttribute.class);
      PositionIncrementAttribute posInc = stream.addAttribute(PositionIncrementAttribute.class);
      PositionLengthAttribute posLen = stream.addAttribute(PositionLengthAttribute.class);
      try {
        stream.reset();
        // print all tokens until stream is exhausted
        while (stream.incrementToken()) {
          System.out.print("\"" + term + "\" +" + posInc.getPositionIncrement() + " " + posLen.getPositionLength() + " "
              + offset.startOffset() + "-" + offset.endOffset() + " " + Tag.label(flags.getFlags()) + " |");
          System.out.println(text.substring(offset.startOffset(), offset.endOffset()) + "|");
        }

        stream.end();
      }
      finally {
        stream.close();
        analyzer.close();
      }
      System.out.println();
    }
  }

}
