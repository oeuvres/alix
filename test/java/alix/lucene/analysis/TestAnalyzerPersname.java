package alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;


public class TestAnalyzerPersname {
  static class AnalyzerNames extends Analyzer
  {
  
    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      // return new TokenStreamComponents(source);
      TokenStream result = new FrLemFilter(source);
      result = new FrPersnameFilter(result);
      return new TokenStreamComponents(source, result);
    }
  
  }
  static public void test(String text) throws IOException
  {
    TestAnalyzer.vertical(text, new AnalyzerNames());
  }
  
  public static void main(String[] args) throws IOException
  {
    test("En 1815, M. Charles-François-Bienvenu Myriel était évêque de Digne. "
        + "C’était un vieillard d’environ soixante-quinze ans ; il occupait le siège de Digne depuis 1806.");
    test("<p>La chambre que les <name>Jondrette</name> habitaient dans la masure <name>Gorbeau</name> était"
        + " la dernière au bout du corridor. La cellule d’à côté était occupée par un jeune homme très pauvre "
        + " qu’on nommait <name>Marius</name>.</p>\n"
        + " <p>Disons ce que c’était que <persName>monsieur Marius</persName>.</p>"
    );
    test(" mère Sainte-Chantal (Mlle de Suzon), devenue folle");
    test("<p>— Amen, dit Fauchelevent.</p>");
    test("— Également ! s’écria G., et si la balance doit pencher, que ce soit du côté du peuple.");
  }
}
