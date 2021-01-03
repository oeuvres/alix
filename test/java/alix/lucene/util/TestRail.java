package alix.lucene.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Arrays;

import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldText;
import alix.lucene.search.FieldRail;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;
import alix.util.Dir;
import alix.util.TopArray;

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
    FieldRail fieldRail = new FieldRail(alix, fieldName);
    FieldText fstats = alix.fieldText(fieldName);
    for (int docId = 0; docId < maxDoc; docId++) {
      System.out.println("rail(docId=" + docId + ")   {" + fieldRail.toString(docId) + "}");
    }
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    long[] freqs = fieldRail.freqs(filter);
    showTop(fstats, freqs, 10);

    /*
    AtomicIntegerArray freqs2 = rail.freqsParallel(filter);
    showTop(fstats, freqs2, 10);
    */
  }
  
  public static void miniCooc() throws IOException
  {
    System.out.println("Mini cooc");
    Alix alix = Alix.instance(path, new SimpleAnalyzer());
    System.out.println("Field stats, load");
    FieldText fstats = alix.fieldText(fieldName);
    System.out.println(fstats.topTerms().sortByOccs());
    
    FieldRail fieldRail = new FieldRail(alix, fieldName);
    long[] cooc = fieldRail.cooc("b", 1, 1, null);
    System.out.println("Cooc by rail");
    System.out.println(Arrays.toString(cooc));
    showTop(fstats, cooc, 10);
    fstats = alix.fieldText(fieldName);
  }
  

  public static void showTop(FieldText fstats, final long[] cooc, int limit)
  {
    TopArray top = new TopArray(limit);
    for (int termId = 0, length = cooc.length; termId < length; termId++) {
      if (fstats.isStop(termId)) continue;
      top.push(termId, cooc[termId]);
    }
    BytesRefHash dic = fstats.formDic;
    BytesRef ref = new BytesRef();
    for(TopArray.Entry entry: top) {
      dic.get(entry.id(), ref);
      System.out.print(ref.utf8ToString() + " " +  df.format(entry.score())+", ");
    }
  }

  public static void showJaccard(FieldText fstats, long pivotFreq, final long[] freqs, int limit)
  {
    TopArray top = new TopArray(limit);
    for (int id = 0, length = freqs.length; id < length; id++) {
      double score = (double)2 * freqs[id] / (fstats.occs(id) * fstats.occs(id) + pivotFreq * pivotFreq);
      top.push(id, score);
    }
    BytesRefHash dic = fstats.formDic;
    BytesRef ref = new BytesRef();
    for(TopArray.Entry entry: top) {
      dic.get(entry.id(), ref);
      System.out.println(ref.utf8ToString() + " — " + freqs[entry.id()] + " / " + fstats.occs(entry.id()) + " — " + entry.score());
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
    FieldText fstats = alix.fieldText(field);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    int maxDoc = alix.reader().maxDoc();
    
    
    FixedBitSet filter = new FixedBitSet(maxDoc);
    filter.set(0, maxDoc);
    
    
    // Get the Java runtime
    Runtime runtime = Runtime.getRuntime();
    runtime.gc();
    long mem0 = runtime.totalMemory() - runtime.freeMemory();
    time = System.nanoTime();
    System.out.print("FieldRail loaded in ");
    FieldRail fieldRail = new FieldRail(alix, field);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms.");
    runtime.gc();
    long mem1 = runtime.totalMemory() - runtime.freeMemory();
    long[] freqs = null;
    
    System.out.print("Freqs by rail file.map in ");
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs = fieldRail.freqs(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    System.out.println("mem0=" + ((float)mem0 / MB) +" Mb, mem1=" + ((float)mem1 / MB) + " Mb, diff="+ ((float)(mem1 - mem0) / MB));
    showTop(fstats, freqs, 10);

    /*
    System.out.print("Freqs by rail parallel in ");
    AtomicIntegerArray freqs2 = null;
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs2 = rail.freqsParallel(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    showTop(fstats, new TopArray(10, freqs2));
    */
    
    /*
    System.out.print("Freqs by cooc in ");
    Cooc cooc = new Cooc(alix, field);
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      freqs = cooc.freqs(filter);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    showTop(fstats, new TopArray(10, freqs));
    */
    
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
    FieldText fstats = alix.fieldText(fieldName);
    
    time = System.nanoTime();
    System.out.print("Build rail in ");
    FieldRail fieldRail = new FieldRail(alix, fieldName);
    System.out.println(((System.nanoTime() - time) / 1000000) + "ms, ");

    for (String q: new String[] {"vie", "poire", "esprit", "vie esprit", "de"}) {
      String[] terms = alix.forms(q);
      // get freq for the pivot
      long freq1 = 0;
      long freq2 = 0;
      System.out.println("Coocs by rail");
      for (String term : terms) {
        if (term == null) continue;
        freq1 = alix.reader().totalTermFreq(new Term(fieldName, term));
        freq2 = fstats.occs(term);
        System.out.println(term+ ": freq1=" + freq1 + " freq2=" + freq2 );
      }
      System.out.print(q + " coocs by rail in ");
      long[] freqs = null;
      for (int i=0; i < 10; i++) {
        time = System.nanoTime();
        freqs = fieldRail.cooc(terms, 5, 5, null, null);
        System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
      }
      System.out.println("\n--- Top normal");
      showTop(fstats, freqs, 100);
      System.out.println("\n--- Top Jaccard");
      showJaccard(fstats, freq1, freqs, 20);
      System.out.println();
    }

    /*
    TermList search = alix.qTermList(fieldName, "de");
    Cooc cooc = new Cooc(alix, fieldName);
    TopTerms dic = null;
    System.out.print("Coocs by rail in ");
    for (int i=0; i < 10; i++) {
      time = System.nanoTime();
      dic = cooc.topTerms(search, 5, 5, null);
      System.out.print(((System.nanoTime() - time) / 1000000) + "ms, ");
    }
    System.out.println();
    dic.sortByOccs();
    System.out.println(dic);
    */
  }

  
  public static void main(String[] args) throws Exception
  {
    /*
    miniWrite();
    miniRead();
    miniCooc();
    */
    coocs();
    // freqs();
  }


}
