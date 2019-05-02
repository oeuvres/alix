package alix.lucene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.util.BytesRef;

/**
 * Send the co-ocurences of pivot query (single or multi-terms).
 * Requires 
 * @author fred
 *
 */
public class Cooc
{
  /** Searcher */
  private final IndexSearcher searcher;
  /** Reader */
  private final IndexReader reader;
  /** Number of positions left to pivot */
  private final int left = 5;
  /** Number of positions right to pivot */
  private final int right = 5;
  /** Docs of the pivot query */
  private final BitSet filter;
  /** Number of hits (documents) containing the pivot query */
  private final int hits;
  /** Number of occurrences of the pivot */
  private int occs;
  /** For each docs, possible positions of terms for the concordance */
  private final LinkedHashMap<Integer,BitSet> docMaps = new LinkedHashMap<Integer,BitSet>();
  
  /**
   * Store position of the pivot queries
   * @param pivotQuery
   * @param left
   * @param right
   * @throws IOException 
   */
  Cooc(final IndexSearcher searcher, final Query pivotQuery, String field, final int left, final int right) throws IOException
  {
    this.searcher = searcher;
    this.reader = searcher.getIndexReader();
    final IndexReader reader = this.reader;
    

    
    final BitsCollector collector = new BitsCollector(searcher);
    searcher.search(pivotQuery, collector);
    this.filter = collector.bits();
    BitSet filter = this.filter;
    this.hits = collector.hits();
    // Get the positions of the pivot query
    Weight weight = pivotQuery.createWeight(searcher, ScoreMode.COMPLETE, 0);
    
    Iterator<LeafReaderContext> contexts = reader.leaves().iterator();
    LeafReaderContext ctx = contexts.next();
    int docBase = ctx.docBase;
    for (int docid = filter.nextSetBit(0); docid >= 0; docid = filter.nextSetBit(docid+1)) {
      Matches matches = weight.matches(ctx, docid - docBase);
      while (matches == null) {
        ctx = contexts.next();
        docBase = ctx.docBase;
        matches = weight.matches(ctx, docid - docBase);
      }
      BitSet positions = new BitSet();
      MatchesIterator mi = matches.getMatches(field);
      while(mi.next()) {
        int fromIndex = Math.max(0, mi.startPosition() - left);
        int toIndex = mi.endPosition() + right;
        positions.set(fromIndex, toIndex+1);
        occs++;
      }
      docMaps.put(docid, positions);
    }
    System.out.println(docMaps);
  }
  /**
   * Get the count for a term in the 
   * @param field
   * @param bytes
   * @return
   * @throws IOException
   */
  public long count(String field, BytesRef bytes) throws IOException
  {
    long count = 0;
    Iterator<LeafReaderContext> contexts = reader.leaves().iterator();
    ImpactsEnum impacts = null;
    int docBase = 0;
    int cursor = -1;
    noleaf:
    for (int docid = filter.nextSetBit(0); docid >= 0; docid = filter.nextSetBit(docid+1)) {
      if (docid < (cursor + docBase)) continue;
      if (docid > (cursor + docBase) && impacts != null) cursor = impacts.advance(docid - docBase);
      if (cursor == ImpactsEnum.NO_MORE_DOCS) impacts = null;
      while (impacts == null) {
        if (!contexts.hasNext()) break noleaf;
        LeafReaderContext ctx = contexts.next();
        docBase = ctx.docBase;
        LeafReader segReader = ctx.reader();
        TermsEnum tenum = segReader.terms(field).iterator();
        if (!tenum.seekExact(bytes)) continue; // term not found in this leaf
        impacts = tenum.impacts(PostingsEnum.POSITIONS);
        cursor = impacts.advance(docid - docBase);
        if (cursor == ImpactsEnum.NO_MORE_DOCS) impacts = null;
      }
      // we should have the leaf cursor as near as possible
      if (docid != (cursor + docBase)) continue;
      BitSet positions = docMaps.get(docid);
      int freq = impacts.freq();
      for (int i = 0; i < freq; i++) {
        int pos = impacts.nextPosition();
        if (positions.get(pos)) count++;
      }
    }
    return count;
  }
  
  static class Indexer implements Runnable {
    final static String content = "text";
    final static String tag = "int";
    final static String[] docs = {
        "a b c d e ",
        "aa b c d ee zz zz",
        "b a c e d ",
        "zz b aa c ee d zz",
        "a b f d e ",
        "b a f e d ",
    };
    final IndexWriter writer;
    final int no;
    public Indexer(final IndexWriter writer, final int no)
    {
      this.writer = writer;
      this.no= no;
    }
    @Override
    public void run()
    {
      int j = 0;
      Document doc = new Document();
      for(String text: docs) {
        doc.clear();
        doc.add(new Field(content, text+" "+no+" ", Alix.ftypeText));
        doc.add(new IntPoint(tag, j % 3));
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

  public static void main(String args[]) throws Exception
  {
    // test base
    Path path = Paths.get("work/test");
    Alix alix = Alix.instance(path);

    Files.walk(path)
    .map(Path::toFile)
    .forEach(File::delete);
     
    IndexWriter writer = alix.writer();
    final int threads = 10;

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for(int i=0; i<threads; i++){
      pool.execute(new Indexer(writer, i));
    }
    pool.shutdown();
    boolean finished = pool.awaitTermination(1, TimeUnit.MINUTES);


    
    writer.commit();
    // writer.forceMerge(1); // useful for terms
    writer.close();
    
    BooleanQuery filterQuery = new BooleanQuery.Builder()
      .add(IntPoint.newExactQuery(Indexer.tag, 0), Occur.SHOULD)
      .add(IntPoint.newExactQuery(Indexer.tag, 1), Occur.SHOULD)
      .build();
    BooleanQuery wordQuery = new BooleanQuery.Builder()
      .add(new TermQuery(new Term(Indexer.content, "zz")), Occur.SHOULD)
      .add(new TermQuery(new Term(Indexer.content, "f")), Occur.SHOULD)
      .build();
    BooleanQuery pivotQuery = new BooleanQuery.Builder()
      .add(filterQuery, Occur.FILTER)
      .add(wordQuery, Occur.MUST)
      .build();
    
    
    Cooc cooc = new Cooc(alix.searcher(), pivotQuery, Indexer.content, 1, 1);
    BytesRef bytes = new BytesRef();
    Dic dic = alix.dic(Indexer.content);
    while (dic.next()) {
      dic.term(bytes);
      System.out.print(bytes.utf8ToString()+":"+dic.count(bytes)+" - ");
      System.out.println(cooc.count(Indexer.content, bytes));
      
    }
  }
}
