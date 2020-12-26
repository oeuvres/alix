package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.TestAlix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldText;

public class TestFieldText
{

  static public void mini() throws IOException
  {
    Alix alix = TestAlix.miniBase(new WhitespaceAnalyzer());
    TestAlix.write(alix, new String[] {
      "C C A", 
      "A A A B", 
      "B A A",
      "C B B", 
      "A B A A A",
      "B B B",
    });
    String fieldName = TestAlix.fieldName;
    FieldText fstats = alix.fieldText(fieldName);
    FormEnum terms = fstats.iterator(-1, null, null, null);
    System.out.println("terms, alpha");
    System.out.println(terms);
    FixedBitSet bits = new FixedBitSet(alix.maxDoc());
    bits.set(2);
    bits.set(3);
    for (int docId = 0; docId < bits.length(); docId++) {
      if (bits.get(docId)) System.out.print('1');
      else System.out.print('·');
    }
    System.out.println("Filtered terms, occurrences");
    terms = fstats.iterator(-1, bits, null, null);
    System.out.println(terms);
    System.out.println("Filtered terms, with a scorer");
    terms = fstats.iterator(-1, bits, new SpecifBinomial(), null);
    System.out.println(terms);

    System.out.println("Filtered terms, reverse chi2");
    terms = fstats.iterator(3, bits, new SpecifChi2(), null, true);
    System.out.println(terms);
    while (terms.hasNext()) {
      terms.next();
      System.out.println(terms.label()+" "+terms.score());
    }
  }
  
  static public void perfs() throws IOException
  {
    long time;
    Alix alix = Alix.instance("web/WEB-INF/obvil/test", new FrAnalyzer());
    String field = "bibl";
    time = System.nanoTime();
    FieldText fstats = alix.fieldText(field);
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
