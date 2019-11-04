package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;

public class TestScale
{
  public static void main(String args[]) throws IOException
  {
    Path path = Paths.get("web/WEB-INF/lucene");
    Alix alix = Alix.instance(path, new FrAnalyzer());
    Corpus corpus = new Corpus(alix, Alix.BOOKID, "test", null);
    corpus.add("geoffroy_cours");
    corpus.add("ghil_en-methode");
    corpus.add("UNFINDABLE");
    corpus.add("geoffroy_cours-litterature-dramatique-02");
    
    TermList terms = alix.qTerms("le;moi,je;;de", "text");
    System.out.println(terms);
    System.out.println(terms.groups());
    Scale scale = new Scale(alix, corpus.bits(), "year", "text");
    long[][] data = scale.curves(terms, 200);
    
    int rows = data[0].length;
    int cols = data.length;
    
    for (int i = 0; i < rows; i++) {
      System.out.println("");
      for (int j = 0; j < cols; j++) {
        System.out.print(data[j][i]+" ");
      }
    }

  }

}
