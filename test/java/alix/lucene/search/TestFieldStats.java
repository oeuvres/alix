package alix.lucene.search;

import java.io.IOException;

import alix.lucene.Alix;
import alix.lucene.TestAlix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldStats;
import alix.lucene.search.TopTerms;

public class TestFieldStats
{
  static public void print(TopTerms terms) {
    terms.reset();
    int lines = 100;
    while (terms.hasNext()) {
      terms.next();
      System.out.println(terms.term()+" occs="+terms.occs()+" score="+terms.score());
      if (--lines <= 0) break;
    }
  }

  static public void mini() throws IOException
  {
    Alix alix = TestAlix.miniBase();
    String fieldName = TestAlix.fieldName;
    FieldStats fstats = alix.fieldStats(fieldName);
    TopTerms terms = fstats.topTerms();
    terms.sortByOccs();
    print(terms);
  }
  
  static public void perfs() throws IOException
  {
    long time;
    Alix alix = Alix.instance("web/WEB-INF/obvil/test", new FrAnalyzer());
    String field = "bibl";
    time = System.nanoTime();
    FieldStats fstats = alix.fieldStats(field);
    System.out.println(fstats);
    System.out.println("Terms in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    TopTerms terms = fstats.topTerms();
    System.out.println("\n\nDic in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    terms.sortByOccs();
    System.out.println("\n\nSort by score in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    print(terms);
    time = System.nanoTime();
    terms.sortByScores();
    System.out.println("\n\nSort by occ in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    print(terms);
  }
  
  public static void main(String[] args) throws IOException 
  {
    mini();
  }
}
