package alix.lucene;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

public class XmlAnalyzer extends Analyzer
{

  @Override
  protected TokenStreamComponents createComponents(String fieldName)
  {
    return new TokenStreamComponents(new XmlTokenizer());
  }

  public static void main(String[] args) throws IOException
  {
    // text to tokenize
    final String text = "<p>" + "C’est un paragraphe. Avec de <i>l'italique</i>." + "</p>";

    XmlAnalyzer analyzer = new XmlAnalyzer();
    TokenStream stream = analyzer.tokenStream("field", new StringReader(text));

    // get the CharTermAttribute from the TokenStream
    CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);

    try {
      stream.reset();
      // print all tokens until stream is exhausted
      while (stream.incrementToken()) {
        System.out.println(termAtt.toString());
      }
      stream.end();
    }
    finally {
      stream.close();
      analyzer.close();
    }
  }

}
