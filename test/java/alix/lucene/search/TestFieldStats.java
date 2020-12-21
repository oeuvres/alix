package alix.lucene.search;

import java.io.IOException;

import alix.lucene.Alix;
import alix.lucene.TestAlix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldText;

public class TestFieldStats
{

  static public void mini() throws IOException
  {
    Alix alix = TestAlix.miniBase();
    String fieldName = TestAlix.fieldName;
    FieldText fstats = alix.fieldStats(fieldName);
    SortEnum terms = fstats.iterator(-1, null, null, null);
    terms.reset();
    while(terms.hasNext()) {
      terms.next();
      System.out.println(terms.label()+" occs="+terms.occsMatching() + "/"+ terms.docsField() + " docs=" + terms.docsMatching() + "/" + terms.docsField());
    }
    System.out.println(terms);
  }
  
  static public void perfs() throws IOException
  {
    long time;
    Alix alix = Alix.instance("web/WEB-INF/obvil/test", new FrAnalyzer());
    String field = "bibl";
    time = System.nanoTime();
    FieldText fstats = alix.fieldStats(field);
    System.out.println(fstats);
    System.out.println("Terms in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    // TODO optimize terms
    /*
    TermIterator terms terms = fstats.topTerms();
    System.out.println("\n\nDic in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    terms.sortByOccs();
    System.out.println("\n\nSort by score in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    print(terms);
    time = System.nanoTime();
    terms.sortByScores();
    System.out.println("\n\nSort by occ in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    print(terms);
    */
  }
  
  public static void main(String[] args) throws IOException 
  {
    mini();
  }
}
