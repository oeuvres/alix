package alix.lucene.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.Freqs;
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
    for (String text: new String[] {"A B", "C B E", "C D"}) {
      field.setStringValue(text);
      writer.addDocument(doc);
      System.out.println("add(docId=" + docId + ")   {" + text + "}");
      docId++;
    }
    writer.addDocument(new Document()); // add empty doc with no value for this field
    writer.commit();
    writer.close();
  }
  
  public static void miniRead() throws IOException
  {
    Alix alix = Alix.instance(path, new SimpleAnalyzer());
    int maxDoc = alix.maxDoc();
    Rail rail = new Rail(alix, fieldName);
    rail.store();
    rail.load();
    for (int docId = 0; docId < maxDoc; docId++) {
      System.out.println("rail(docId=" + docId + ")   {" + rail.toString(docId) + "}");
    }
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    int[] freqs = rail.freqs(filter);
    TopInt top = new TopInt(10, freqs);
    for(TopInt.Entry entry: top) {
      System.out.println(rail.form(entry.id()) + " " + df.format(entry.score()));
    }

    AtomicIntegerArray freqs2 = rail.freqsParallel(filter);
    top = new TopInt(10, freqs2);
    for(TopInt.Entry entry: top) {
      System.out.println(rail.form(entry.id()) + " " + df.format(entry.score()));
    }
}
  
  public static void freqs() throws IOException
  {
    long time;
    time = System.nanoTime();
    System.out.print("Load a big index in ");
    String path = "/home/fred/code/ddrlab/WEB-INF/bases/critique";
    Alix alix = Alix.instance(path, new FrAnalyzer());
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    final String field = "text";
    time = System.nanoTime();
    System.out.print("Calculate freqs in ");
    Freqs stats = alix.freqs(field);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    int maxDoc = alix.reader().maxDoc();
    
    
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    
    // Get the Java runtime
    Runtime runtime = Runtime.getRuntime();
    runtime.gc();
    long mem0 = runtime.totalMemory() - runtime.freeMemory();
    time = System.nanoTime();
    System.out.print("Rail build in ");
    Rail rail = new Rail(alix, field);
    rail.store();
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    rail.load();
    System.out.println("Rail load in "+((System.nanoTime() - time) / 1000000) + " ms.");
    runtime.gc();
    long mem1 = runtime.totalMemory() - runtime.freeMemory();
    int[] freqs = null;
    System.out.print("By rail file.map in ");
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs = rail.freqs(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    System.out.println("mem0=" + ((float)mem0 / MB) +" Mb, mem1=" + ((float)mem1 / MB) + " Mb, diff="+ ((float)(mem1 - mem0) / MB));
    TopInt topi = new TopInt(10, freqs);
    for(TopInt.Entry entry: topi) {
      System.out.println(rail.form(entry.id()) + " " +  df.format(entry.score()));
    }

    
    System.out.print("By rail parallel in ");
    AtomicIntegerArray freqs2 = null;
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs2 = rail.freqsParallel(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    topi = new TopInt(10, freqs2);
    for(TopInt.Entry entry: topi) {
      System.out.println(rail.form(entry.id()) + " " +  df.format(entry.score()));
    }

    
    
    
    TopTerms top = null;
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      top = stats.topTerms(filter);
      System.out.println("By term vector in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    }
    top.sortByHits();
    System.out.println(top);
  }

  
  public static void main(String[] args) throws Exception
  {
    // miniWrite();
    // miniRead();
    freqs();
  }


}
