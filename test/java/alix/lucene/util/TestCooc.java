package alix.lucene.util;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.TestIndex;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldText;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;
import alix.lucene.util.Cooc;

public class TestCooc
{
  /**
   * initialize a smaller piece of the array and use the System.arraycopy 
   * call to fill in the rest of the array in an expanding binary fashion
   */
  public static void bytefill(byte[] array, byte value) {
    int len = array.length;
    array[0] = value;

    //Value of i will be [1, 2, 4, 8, 16, 32, ..., len]
    for (int i = 1; i < len; i += i) {
      System.arraycopy(array, 0, array, i, ((len - i) < i) ? (len - i) : i);
    }
  }
  
  public static void small() throws IOException, ClassNotFoundException, InterruptedException
  {
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    String field = TestIndex.TEXT;
    Alix alix = TestIndex.index();
    alix.writer().close(); // close writer before writing coos
    Cooc cooc = new Cooc(alix, field);
    cooc.write();
    // show all rails
    IndexReader reader = alix.reader();
    String fieldRail = field + Cooc._RAIL;
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      int docLeaf;
      LeafReader leaf = context.reader();
      BinaryDocValues binDocs = leaf.getBinaryDocValues(fieldRail);
      if (binDocs == null) continue; 
      while ( (docLeaf = binDocs.nextDoc()) != END) {
        BytesRef ref = binDocs.binaryValue();
        String[] strings = cooc.strings(ref);
        for (String w: strings) {
          System.out.print(w+" ");
        }
        System.out.println();
      }

    }
    TermList terms = alix.qTermList("a", TestIndex.TEXT);
    TopTerms dic = cooc.topTerms(terms, 1, 1, null);
    // dic.sort(dic.occs()); // ???
    System.out.println(dic);
  }
  
  public static void big() throws ClassNotFoundException, IOException
  {
    String path = "web/WEB-INF/obvil/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer());
    String field = "text";
    long time;
    Cooc cooc = new Cooc(alix, field);
    time = System.nanoTime();
    // cooc.prepare();
    System.out.println("Prepare in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    TermList terms = alix.qTermList("justice", field);
    TopTerms dic = cooc.topTerms(terms, 5, 5, null);
    // 
    
    System.out.println("\n\nCoocs in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    System.out.println(dic);
  }

  /**
   * Compare freqlist algos
   * @throws IOException 
   */
  public static void freqs() throws IOException
  {
    String path = "/home/fred/code/ddrlab/WEB-INF/bases/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer());
    final String field = "text";
    FieldText stats = alix.fieldText(field);
    int maxDoc = alix.reader().maxDoc();
    
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    
    long time;
    
    time = System.nanoTime();
    TopTerms top = stats.topTerms(filter);
    System.out.println("By term vector in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    top.sortByOccs();
    System.out.println(top);

    Cooc cooc = new Cooc(alix, field);
    time = System.nanoTime();
    cooc.freqs(filter);
    System.out.println("By rails in " + ((System.nanoTime() - time) / 1000000) + " ms.");
  }

  
  static public void test() throws ClassNotFoundException, IOException
  {
    String path = "web/WEB-INF/obvil/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer());
    String field = "text";
    Cooc cooc = new Cooc(alix, field);
    String[] rail = cooc.sequence(0);
    if (rail == null) {
      System.out.println("NULL");
      System.exit(0);
    }
    int max = Math.max(200, rail.length);
    for (int i = 0; i < max; i++) {
      System.out.println(rail[i]);
    }
  }
  public static void main(String[] args) throws Exception
  {
    freqs();
  }

}
