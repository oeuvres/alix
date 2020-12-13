package alix.lucene.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldStats;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;
import alix.util.Dir;
import alix.util.TopInt;

public class TestRail
{
  static Path path = Paths.get("/tmp/alix/test");
  static final String fieldName = "text";
  private static final long MB = 1024L * 1024L;
  static DecimalFormat df = new DecimalFormat("###,###,###");
  
  public static void miniWrite() throws IOException
  {
    Dir.rm(path);
    Alix alix = Alix.instance(path, new SimpleAnalyzer());
    IndexWriter writer = alix.writer();
    Field field = new Field(fieldName, "", Alix.ftypeText);
    Document doc = new Document();
    doc.add(field);
    int docId = 0;
    for (String text: new String[] {"B C A", "C B E", "C D"}) {
      field.setStringValue(text);
      writer.addDocument(doc);
      System.out.println("add(docId=" + docId + ")   {" + text + "}");
      docId++;
    }
    writer.addDocument(new Document()); // add empty doc with no value for this field
    writer.commit();
    writer.close();
    Cooc cooc = new Cooc(alix, fieldName);
    cooc.write();

  }
  
  public static void miniRead() throws IOException
  {
    Alix alix = Alix.instance(path, new SimpleAnalyzer());
    int maxDoc = alix.maxDoc();
    Rail rail = new Rail(alix, fieldName);
    FieldStats fstats = alix.fieldStats(fieldName);
    for (int docId = 0; docId < maxDoc; docId++) {
      System.out.println("rail(docId=" + docId + ")   {" + rail.toString(docId) + "}");
    }
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    int[] freqs = rail.freqs(filter);
    showFreqs(fstats, new TopInt(10, freqs));

    AtomicIntegerArray freqs2 = rail.freqsParallel(filter);
    showFreqs(fstats, new TopInt(10, freqs2));
  }
  
  public static void miniCooc() throws IOException
  {
    Alix alix = Alix.instance(path, new SimpleAnalyzer());
    FieldStats fstats = alix.fieldStats(fieldName);
    System.out.println(fstats.topTerms().sortByOccs());
    
    TermList terms = alix.qTermList(fieldName, "B");
    Rail rail = new Rail(alix, fieldName);
    int[] freqs = rail.cooc(terms, 1, 1, null);
    System.out.println("Cooc by rail");
    System.out.println(Arrays.toString(freqs));
    showFreqs(fstats, new TopInt(10, freqs));
    
    Cooc cooc = new Cooc(alix, fieldName);
    TopTerms dic = cooc.topTerms(terms, 1, 1, null);
    dic.sortByOccs();
    System.out.println("Cooc by cooc");
    System.out.println(dic);
  }
  

  public static void showFreqs(FieldStats fstats, final TopInt top)
  {
    BytesRefHash dic = fstats.hashDic();
    BytesRef ref = new BytesRef();
    for(TopInt.Entry entry: top) {
      dic.get(entry.id(), ref);
      System.out.println(ref.utf8ToString() + " " +  df.format(entry.score()));
    }
  }

  
  public static void freqs() throws IOException
  {
    long time;
    time = System.nanoTime();
    System.out.print("Lucene index loaded in ");
    String path = "/home/fred/code/ddrlab/WEB-INF/bases/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer(), Alix.FSDirectoryType.MMapDirectory);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    final String field = "text";
    time = System.nanoTime();
    System.out.print("Calculate freqs in ");
    FieldStats fstats = alix.fieldStats(field);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    int maxDoc = alix.reader().maxDoc();
    
    
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    
    // Get the Java runtime
    Runtime runtime = Runtime.getRuntime();
    runtime.gc();
    long mem0 = runtime.totalMemory() - runtime.freeMemory();
    time = System.nanoTime();
    System.out.print("Rail loaded in ");
    Rail rail = new Rail(alix, field);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    runtime.gc();
    long mem1 = runtime.totalMemory() - runtime.freeMemory();
    int[] freqs = null;
    
    System.out.print("Freqs by rail file.map in ");
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs = rail.freqs(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    System.out.println("mem0=" + ((float)mem0 / MB) +" Mb, mem1=" + ((float)mem1 / MB) + " Mb, diff="+ ((float)(mem1 - mem0) / MB));
    showFreqs(fstats, new TopInt(10, freqs));

    
    System.out.print("Freqs by rail parallel in ");
    AtomicIntegerArray freqs2 = null;
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs2 = rail.freqsParallel(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    showFreqs(fstats, new TopInt(10, freqs2));

    System.out.print("Freqs by cooc in ");
    Cooc cooc = new Cooc(alix, field);
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs = cooc.freqs(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    showFreqs(fstats, new TopInt(10, freqs));
    
    TopTerms top = null;
    System.out.print("Freqs by term vector in ");
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      top = fstats.topTerms(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    top.sortByHits();
    System.out.println(top);
  }

  public static void coocs() throws IOException
  {
    long time;
    String path = "/home/fred/code/ddrlab/WEB-INF/bases/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer(), Alix.FSDirectoryType.MMapDirectory);
    FieldStats fstats = alix.fieldStats(fieldName);

    for (String word: new String[] {"poire", "vie", "esprit", "vie esprit", "de"}) {
      TermList terms = alix.qTermList(fieldName, word);
      int[] freqs = null;
      Rail rail = new Rail(alix, fieldName);
      System.out.print(word + ": coocs by rail in ");
      for (int i=0; i < 10; i++) {
        time = System.nanoTime();
        freqs = rail.cooc(terms, 20, 20, null);
        System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
      }
      System.out.println();
      showFreqs(fstats, new TopInt(10, freqs));
    }

    /*
    TermList terms = alix.qTermList(fieldName, "de");
    Cooc cooc = new Cooc(alix, fieldName);
    TopTerms dic = null;
    System.out.print("Coocs by rail in ");
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      dic = cooc.topTerms(terms, 5, 5, null);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    dic.sortByOccs();
    System.out.println(dic);
    */
  }

  
  public static void main(String[] args) throws Exception
  {
    // miniWrite();
    // miniCooc();
    coocs();
    // miniRead();
    // freqs();
  }


}
