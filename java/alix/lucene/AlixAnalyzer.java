package alix.lucene;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

public class AlixAnalyzer extends Analyzer
{

  @Override
  protected TokenStreamComponents createComponents(String fieldName)
  {
    return new TokenStreamComponents(new FrTokenizer());
  }

  public static void main(String[] args) throws IOException
  {
    // text to tokenize
    final String text = "<p>" + "C’est un paragraphe. Avec de <i>l'italique</i>." + "</p>";

    AlixAnalyzer analyzer = new AlixAnalyzer();
    TokenStream stream = analyzer.tokenStream("field", new StringReader(text));

    // get the CharTermAttribute from the TokenStream
    CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
    OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
    PositionIncrementAttribute pos = stream.addAttribute(PositionIncrementAttribute.class);

    try {
      stream.reset();
      // print all tokens until stream is exhausted
      while (stream.incrementToken()) {
        System.out.println(term.toString()+" "+pos.getPositionIncrement()+" "+offset.startOffset()+" "+offset.endOffset());
      }
      stream.end();
    }
    finally {
      stream.close();
      analyzer.close();
    }
  }

}
