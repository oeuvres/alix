package alix.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.BytesRef;

import alix.util.Dir;


public class TestIndex
{
  public final static String TEXT = "text";
  public final static String INT = "int";
  public final static String FACET = "facet";
  static class Indexer implements Runnable
  {
    final static String[] docs = { "go b a c c", "b a c e d ", "zz b aa c ee ", "a b f d e ",
        "b a f e d "};
    final IndexWriter writer;
    final int no;

    public Indexer(final IndexWriter writer, final int no)
    {
      this.writer = writer;
      this.no = no;
    }

    @Override
    public void run()
    {
      int j = 0;
      Document doc = new Document();
      for (String text : docs) {
        doc.clear();
        doc.add(new Field(TEXT, text + " " + no + " ", Alix.ftypeText));
        doc.add(new NumericDocValuesField(TEXT, 2)); // supposed to be length
        int v = j % 4;
        doc.add(new IntPoint(INT, v));
        doc.add(new StoredField(INT, v));
        if (j % 4 == 1) doc.add(new IntPoint(INT, -10));
        doc.add(new SortedSetDocValuesField(FACET, new BytesRef("no"+no)));
        doc.add(new SortedSetDocValuesField(FACET, new BytesRef("doc"+j)));
        try {
          writer.addDocument(doc);
        }
        catch (IOException e) {
          e.printStackTrace();
          Thread t = Thread.currentThread();
          t.getUncaughtExceptionHandler().uncaughtException(t, e);
        }
        j++;
      }
    }
  }

  public static Alix index() throws IOException, InterruptedException
  {
    // test base
    Path path = Paths.get("work/test");
    Dir.rm(path);
    Alix alix = Alix.instance("test", path, new WhitespaceAnalyzer(), null); // .
    IndexWriter writer = alix.writer();
    final int threads = 10;

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(new Indexer(writer, i));
    }
    pool.shutdown();
    pool.awaitTermination(1, TimeUnit.MINUTES);
    writer.commit();
    // writer.forceMerge(1); // do not merge, for testing
    // delete documents
    writer.deleteDocuments(IntPoint.newExactQuery(INT, 3));
    writer.commit();
    // writer.close(); // do not close, will apply deletions
    return alix;
  }

}
