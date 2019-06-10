/*
 * © Pierre DITTGEN <pierre@dittgen.org> 
 * 
 * Alix : [A] [L]ucene [I]ndexer for [X]ML documents
 * 
 * Alix is a command-line tool intended to parse XML documents and to index
 * them into a Lucene Index
 * 
 * This software is governed by the CeCILL-C license under French law and
 * abiding by the rules of distribution of free software.  You can  use, 
 * modify and/ or redistribute the software under the terms of the CeCILL-C
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info". 
 * 
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability. 
 * 
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or 
 * data to be ensured and,  more generally, to use and operate it in the 
 * same conditions as regards security. 
 * 
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-C license and that you accept its terms.
 * 
 */
package alix.lucene;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import alix.fr.dic.Tag;

/**
 * Alix entry-point
 * 
 * @author Pierre DITTGEN (2012, original idea, creation)
 * @author glorieux-f
 */
public class Alix
{
  /** Mandatory content, XML file name, maybe used for update */
  public static final String FILENAME = "FILENAME";
  /** Madatory field for sorting the index */
  public static final String YEAR = "year";
  /** A global cache for objects */
  private final ConcurrentHashMap<String, SoftReference<Object>> cache = new ConcurrentHashMap<>();
  /** Key prefix for a cached object */
  public static final String CACHE_DOC_INT = "alixDocInt";
  /** Key prefix for a cached object */
  public static final String CACHE_MIN_MAX = "alixMinMax";
  /** Key prefix for a cached object */
  public static final String CACHE_DOC_LENGTH = "alixDocLength";
  /** Key prefix for a cached object */
  public static final String CACHE_DIC = "alixDic";
  /** Current filename proceded */
  public static final FieldType ftypeAll = new FieldType();
  static {
    // inverted index
    ftypeAll.setTokenized(true);
    // http://makble.com/what-is-lucene-norms, omit norms (length normalization)
    ftypeAll.setOmitNorms(true);
    // position needed for phrase query
    ftypeAll.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    ftypeAll.setStoreTermVectors(true);
    ftypeAll.setStoreTermVectorOffsets(true);
    ftypeAll.setStoreTermVectorPositions(true);
    // do not store in this field
    ftypeAll.setStored(false);
    ftypeAll.freeze();
  }
  /** Pool of instances, unique by path */
  public static final HashMap<Path, Alix> pool = new HashMap<Path, Alix>();
  /** Normalized path */
  public final Path path;
  /** The lucene directory, kept private, to avoid closing by a thread */
  private Directory dir;
  /** The IndexReader if requested */
  private IndexReader reader;
  /** The IndexSearcher if requested */
  private IndexSearcher searcher;
  /** The IndexWriter if requested */
  private IndexWriter writer;

  /**
   * Avoid construction, maintain a pool by file path to ensure unicity.
   * 
   * @param path
   * @throws IOException
   */
  private Alix(final Path path) throws IOException
  {
    this.path = path;
    Files.createDirectories(path);
    // dir = FSDirectory.open(indexPath);
    dir = MMapDirectory.open(path); // https://dzone.com/articles/use-lucene’s-mmapdirectory
  }

  /**
   * Get a wrapper on a lucene index by file path.
   * 
   * @param path
   * @throws IOException
   */
  public static Alix instance(final String path) throws IOException
  {
    return instance(Paths.get(path));
  }

  /**
   * Get a wrapper on a lucene index by file path.
   * 
   * @param path
   * @throws IOException
   */
  public static Alix instance(Path path) throws IOException
  {
    path = path.toAbsolutePath();
    Alix alix = pool.get(path);
    if (alix == null) {
      alix = new Alix(path);
      pool.put(path, alix);
    }
    return alix;
  }

  /**
   * Get the reader for this lucene index.
   * 
   * @return
   * @throws IOException
   */
  public IndexReader reader() throws IOException
  {
    return reader(false);
  }

  /**
   * Get a reader for this lucene index, allow to force renew if force is true.
   * 
   * @param force
   * @return
   * @throws IOException
   */
  public IndexReader reader(final boolean force) throws IOException
  {
    if (!force && reader != null) return reader;
    cache.clear(); // clean cache
    // near real time reader
    if (writer != null && writer.isOpen()) reader = DirectoryReader.open(writer, true, true);
    else reader = DirectoryReader.open(dir);
    return reader;
  }

  /**
   * Get the searcher for this lucene index.
   * 
   * @return
   * @throws IOException
   */
  public IndexSearcher searcher() throws IOException
  {
    return searcher(false);
  }

  /**
   * Get a searcher for this lucene index, allow to force renew if force is true.
   * 
   * @param force
   * @return
   * @throws IOException
   */
  public IndexSearcher searcher(final boolean force) throws IOException
  {
    if (!force && searcher != null) return searcher;
    reader(force);
    searcher = new IndexSearcher(reader);
    return searcher;
  }

  /**
   * A simple cache. Will be cleared if index reader is renewed. Use SoftReference
   * as a value, so that Garbage Collector will silently delete object references
   * in case of Out of memory.
   * 
   * @param key
   * @param o
   */
  public void cache(String key, Object o)
  {
    cache.put(key, new SoftReference<Object>(o));
  }

  /**
   * Get a cached object
   * 
   * @param key
   * @return
   */
  public Object cache(String key)
  {
    SoftReference<Object> ref = cache.get(key);
    if (ref != null) return ref.get();
    return null;
  }

  /**
   * Get a lucene writer
   * 
   * @throws IOException
   */
  public IndexWriter writer() throws IOException
  {
    if (writer != null && writer.isOpen()) return writer;
    Analyzer analyzer = new AnalyzerAlix();
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    conf.setUseCompoundFile(false); // show separate file by segment
    // may needed, increase the max heap size to the JVM (eg add -Xmx512m or
    // -Xmx1g):
    conf.setRAMBufferSizeMB(48);
    conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
    //
    conf.setSimilarity(new BM25Similarity());
    // no effet found with modification ConcurrentMergeScheduler
    /*
     * int threads = Runtime.getRuntime().availableProcessors() - 1;
     * ConcurrentMergeScheduler cms = new ConcurrentMergeScheduler();
     * cms.setMaxMergesAndThreads(threads, threads); cms.disableAutoIOThrottle();
     * conf.setMergeScheduler(cms);
     */
    // order docid by date after merge
    conf.setIndexSort(new Sort(new SortField(YEAR, SortField.Type.INT)));
    writer = new IndexWriter(dir, conf);
    return writer;
  }

  /**
   * Returns an array in docid order with the value of an int sort field (year).
   * 
   * @return
   * @throws IOException
   */
  public int[] docInt(String field) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    String key = CACHE_DOC_INT + field;
    int[] ints = (int[]) cache(key);
    if (ints != null) return ints;
    int maxDoc = reader.maxDoc();
    final int[] docInt = new int[maxDoc];
    // fill with min value as deleted or absent
    Arrays.fill(docInt, Integer.MIN_VALUE);
    final int[] minMax = { Integer.MAX_VALUE, Integer.MIN_VALUE };
    for (LeafReaderContext context : reader.leaves()) {
      final int docBase = context.docBase;
      LeafReader leaf = context.reader();
      final Bits liveDocs = leaf.getLiveDocs();
      PointValues points = leaf.getPointValues(field);
      points.intersect(new IntersectVisitor()
      {

        @Override
        public void visit(int docid)
        {
          // visit if inside the compare();
        }

        @Override
        public void visit(int docid, byte[] packedValue)
        {
          if (liveDocs != null && !liveDocs.get(docid)) return;
          // in case of multiple values, take the lower one
          if (docInt[docBase + docid] > Integer.MIN_VALUE) return;
          int v = IntPoint.decodeDimension(packedValue, 0);
          docInt[docBase + docid] = v;
        }

        @Override
        public Relation compare(byte[] minPackedValue, byte[] maxPackedValue)
        {
          int v = IntPoint.decodeDimension(minPackedValue, 0);
          if (minMax[0] > v) minMax[0] = v;
          v = IntPoint.decodeDimension(minPackedValue, 0);
          if (minMax[1] < v) minMax[1] = v;
          // Answer that the query needs further infornmation to visit doc with values
          return Relation.CELL_CROSSES_QUERY;
        }
      });
    }
    cache(key, docInt);
    cache(CACHE_MIN_MAX + field, minMax);
    return docInt;
  }

  /** Return the min value of an IntPoint field. */
  public int min(String field) throws IOException
  {
    return minMax(field, 0);
  }

  /** Returns the max value of an IntPoint field. */
  public int max(String field) throws IOException
  {
    return minMax(field, 1);
  }
  /** Get min-max from the cache. */
  private int minMax(String field, int i) throws IOException
  {
    int[] minMax = (int[]) cache(CACHE_MIN_MAX + field);
    if (minMax == null) {
      docInt(field); // ensure calculation
      minMax = (int[]) cache(CACHE_MIN_MAX + field);
    }
    return minMax[i];
  }

  /**
   * Get value by docid of a unique store field, desired type is given by the
   * array to load. Very slow, ~1.5 s. / 1000 books
   * 
   * @throws IOException
   */
  public int[] docStore(String field, int[] load) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    int maxDoc = reader.maxDoc();
    if (load == null || load.length < maxDoc) load = new int[maxDoc];
    Bits liveDocs = null;
    boolean hasDeletions = reader.hasDeletions();
    if (hasDeletions) {
      liveDocs = MultiBits.getLiveDocs(reader);
    }
    Set<String> fields = new HashSet<String>();
    fields.add(field);
    for (int i = 0; i < maxDoc; i++) {
      if (hasDeletions && !liveDocs.get(i)) {
        load[i] = Integer.MIN_VALUE;
        continue;
      }
      Document doc = reader.document(i, fields);
      int v = doc.getField(Alix.YEAR).numericValue().intValue();
      load[i] = v;
    }
    return load;
  }

  /**
   * For a field, return an array in docid order, with the total number of tokens
   * by doc. Is cached, cost 1 s. / 1000 books
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public long[] docLength(String field) throws IOException
  {
    IndexReader reader = reader(); // ensure reader or decache
    String key = CACHE_DOC_LENGTH + field;
    long[] docLength = (long[]) cache(key);
    if (docLength != null) return docLength;
    int maxDoc = reader.maxDoc();
    // index by year
    docLength = new long[maxDoc];
    Bits liveDocs = null;
    boolean hasDeletions = reader.hasDeletions();
    if (hasDeletions) {
      liveDocs = MultiBits.getLiveDocs(reader);
    }

    // get sum of terms by doc
    for (int i = 0; i < maxDoc; i++) {
      if (hasDeletions && !liveDocs.get(i)) {
        docLength[i] = -1;
        continue;
      }
      Terms vector = reader.getTermVector(i, field);
      docLength[i] = vector.getSumTotalTermFreq(); // expensive on first call
    }
    cache(key, docLength);
    return docLength;
  }

  /**
   * For a field, try to get a dictionary of indexed terms.
   * Is cached.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public BytesDic dic(final String field) throws IOException
  {
    String key = CACHE_DIC + field;
    BytesDic dic = (BytesDic) cache(key);
    if (dic != null) return dic;
    dic = new BytesDic(field);
    IndexReader reader = reader(); // ensure reader
    // indexed field
    if (reader.getDocCount(field) > 0) {
      dic.docs = reader.getDocCount(field);
      dic.occs = reader.getSumTotalTermFreq(field);
      BytesRef bytes;
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        Terms terms = leaf.terms(field);
        if (terms == null) continue;
        TermsEnum tenum = terms.iterator();
        // because terms are sorted, we could merge dics more efficiently
        // but index is merged
        while ((bytes = tenum.next()) != null) {
          dic.add(bytes, tenum.totalTermFreq());
        }
      }
      dic.sort();
      cache(key, dic);
      return dic;
    }
    // facet field
    int docs = 0;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(field);
      if (docs4terms == null) break;
      BytesRef bytes;
      // terms.termsEnum().docFreq() not implemented, should loop on docs to have it
      while (docs4terms.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        long ord;
        docs++;
        // each term
        while ((ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
          dic.add(docs4terms.lookupOrd(ord), 1);
        }
      }
    }
    dic.docs = docs;
    dic.sort();
    cache(key, dic);
    return dic;
  }

  /**
   * An analyzer used for query parsing
   */
  static class QueryAnalyzer extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new TokenizerFr();
      TokenStream result = new TokenLem(source);
      result = new TokenCompound(result, 5);
      return new TokenStreamComponents(source, result);
    }

  }

  private static QueryAnalyzer qAnalyzer = new QueryAnalyzer();

  public static Query qParse(String q, String field) throws IOException
  {

    TokenStream ts = qAnalyzer.tokenStream(field, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);
    OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);

    ts.reset();
    TermQuery qTerm = null;
    BooleanQuery.Builder bq = null;
    try {
      while (ts.incrementToken()) {
        if (Tag.isPun(flags.getFlags())) continue;
        if (qTerm != null) {
          if (bq == null) bq = new BooleanQuery.Builder();
          bq.add(qTerm, Occur.SHOULD);
        }
        qTerm = new TermQuery(new Term(field, token.toString()));
      }
      ts.end();
    }
    finally {
      ts.close();
    }
    if (bq != null) {
      bq.add(qTerm, Occur.SHOULD);
      return bq.build();
    }
    return qTerm;
  }
  
  
  /**
   * Provide tokens as a table of terms
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  public TermList qTerms(String q, String field) throws IOException
  {

    TokenStream ts = qAnalyzer.tokenStream(field, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);
    OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);

    TermList terms = new TermList(dic(field));
    ts.reset();
    try {
      while (ts.incrementToken()) {
        final int tag = flags.getFlags();
        if (Tag.isPun(tag)) {
          // start a new line
          if (token.equals(";") || tag == Tag.PUNsent) {
            terms.add(null);
          }
          continue;
        }
        terms.add(new Term(field, token.toString()));
      }
      ts.end();
    }
    finally {
      ts.close();
    }
    return terms;
  }

  /** A row of data for a crossing axis */
  public static class Tick
  {
    public final int docid;
    public final int rank;
    public final long length;
    public long cumul;

    public Tick(final int docid, final int rank, final long length)
    {
      this.docid = docid;
      this.rank = rank;
      this.length = length;
    }

    @Override
    public String toString()
    {
      return "docid=" + docid + " rank=" + rank + " length=" + length + " cumul=" + cumul;
    }
  }

  /**
   * Data for an axis across all index. Measure is token count of a text field.
   * Order is given by an intPoint field (ex : year). Is returned in order of
   * docid. Not too expensive to calculate (~1 ms/1000 docs), but good to cache to
   * avoid multiplication of objects in web context. Cache is not assured here,
   * because data could be used in different order (docid order, or int field
   * order).
   *
   * @param textField
   * @param intPoint
   * @return
   * @throws IOException
   */
  public Tick[] axis(String textField, String intPoint) throws IOException
  {
    long total = reader.getSumTotalTermFreq(textField);
    int maxDoc = reader().maxDoc();
    long[] docLength = docLength(textField);
    int[] docInt = docInt(intPoint);
    Tick[] axis = new Tick[maxDoc];
    // populate the index
    for (int i = 0; i < maxDoc; i++) {
      axis[i] = new Tick(i, docInt[i], docLength[i]);
    }
    // sort the index by date
    Arrays.sort(axis, new Comparator<Tick>()
    {
      @Override
      public int compare(Tick tick1, Tick tick2)
      {
        if (tick1.rank < tick2.rank) return -1;
        if (tick1.rank > tick2.rank) return +1;
        if (tick1.docid < tick2.docid) return -1;
        if (tick1.docid > tick2.docid) return +1;
        return 0;
      }
    });
    // update cumul
    long cumul = 0;
    for (int i = 0; i < maxDoc; i++) {
      long length = axis[i].length;
      if (length > 0) cumul += length;
      axis[i].cumul = cumul;
    }
    // resort in doc order
    Arrays.sort(axis, new Comparator<Tick>()
    {
      @Override
      public int compare(Tick tick1, Tick row2)
      {
        if (tick1.docid < row2.docid) return -1;
        if (tick1.docid > row2.docid) return +1;
        return 0;
      }
    });
    // update
    return axis;
  }

  @Override
  public String toString()
  {
    return "lucene@" + path;
  }
}
