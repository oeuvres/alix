package alix.lucene.search;

import java.io.IOException;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.Freqs;
import alix.lucene.search.TopTerms;

public class TestFreqs
{
  static public void print(TopTerms terms) {
    terms.reset();
    int lines = 100;
    while (terms.hasNext()) {
      terms.next();
      System.out.println(terms.term()+" "+terms.score());
      if (--lines <= 0) break;
    }

  }

  public static void main(String[] args) throws IOException 
  {
    long time;
    Alix alix = Alix.instance("web/WEB-INF/obvil/test", new FrAnalyzer());
    String field = "bibl";
    time = System.nanoTime();
    Freqs freqs = alix.freqs(field);
    System.out.println(freqs);
    System.out.println("Terms in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    TopTerms terms = freqs.topTerms();
    System.out.println("\n\nDic in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    terms.sort(terms.getOccs());
    System.out.println("\n\nSort by score in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    print(terms);
    time = System.nanoTime();
    terms.sort(terms.getScores());
    System.out.println("\n\nSort by occ in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    print(terms);
  }
}
