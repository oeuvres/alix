package alix.lucene;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.util.FixedBitSet;

import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.Freqs;
import alix.lucene.search.TopTerms;
import alix.lucene.util.Cooc;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.map.*;
import net.openhft.chronicle.values.Values;

public final class TestStore
{
  private static final String DB_NAME = "Alix";
  private static final String DB_PATH = "/home/fred/code/ddrlab/WEB-INF/bases/rails.dat";
  
  
  static public void put() throws IOException {
    int[] rail = new int[100000];
    ChronicleMap<IntValue, int[]> store = ChronicleMapBuilder
      .of(IntValue.class, int[].class)
      .name("rails")
      .averageValue(rail)
      .entries(12000)
      .createPersistedTo(new File(DB_PATH));
    for (int i = 0 ; i < 10000; i++) rail[i] = 2 * i;
    IntValue key = Values.newHeapInstance(IntValue.class);
    for (int i = 0 ; i < 10000; i++)
    {
      key.setValue(i);
      store.put(key, rail);
    }
    
    store.close();
  }
  
  public static void writeCooc() throws IOException {
    String path = "/home/fred/code/ddrlab/WEB-INF/bases/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer());
    final String field = "text";
    
    Cooc cooc = new Cooc(alix, field);
    System.out.println(cooc);
    long time;

    final int maxDoc = alix.reader().maxDoc();
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);

    Freqs freqs = alix.freqs(field);
    
    for (int i = 0; i < 10; i++) {
      System.out.println("---");
      time = System.nanoTime();
      TopTerms top = freqs.topTerms(filter);
      System.out.println("By term vector in " + ((System.nanoTime() - time) / 1000000) + " ms.");
      top.sortByOccs();

      time = System.nanoTime();
      cooc.freqsField(filter);
      System.out.println("By lucene Binary field in " + ((System.nanoTime() - time) / 1000000) + " ms.");

      time = System.nanoTime();
      cooc.freqsStore(filter);
      System.out.println("By chronicle store " + ((System.nanoTime() - time) / 1000000) + " ms.");
    }
}
  
  public static void main(String[] args) throws IOException
  {
    /*
    String path = "/home/fred/code/ddrlab/WEB-INF/bases/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer());
    final String field = "text";
    Freqs stats = alix.freqs(field);
    int maxDoc = alix.reader().maxDoc();
    System.out.println(maxDoc);
    */
    // put();
    writeCooc();
  }
  
}
