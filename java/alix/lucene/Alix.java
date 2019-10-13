/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.lucene;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Bits;

import alix.fr.Tag;
import alix.lucene.analysis.CharsLemAtt;
import alix.lucene.search.Facet;
import alix.lucene.search.Scale;
import alix.lucene.search.Freqs;
import alix.lucene.search.TermList;
import alix.lucene.util.Cooc;

/**
 * An Alix instance represents a Lucene base {@link Directory} with other useful data.
 * Instantiation is not public, use {@link #instance(Path, Class)} instead.
 * A static pool of lucene directories is kept to ensure uniqueness of Alix objects.
 * <p>
 * To keep only one instance of {@link IndexReader}, {@link IndexSearcher}, {@link IndexWriter}
 * and {@link Analyzer}
 * across all application (avoiding cost of opening and closing index, use :
 * <ul>
 *   <li>{@link #reader()}</li>
 *   <li>{@link #writer()}</li>
 *   <li>{@link #searcher()}</li>
 *   <li>{@link #analyzer()}</li>
 * </ul>
 * Different lists and stats concerning all index are cached {@link #cache(String, Object)}, 
 * to avoid recalculation. Data are usually available as custom objects, optimized for statistics.
 * <ul>
 *   <li>{@link #docInt(String)} All values of a unique numeric field per document 
 *   ({@link IntPoint}, {@link NumericDocValuesField}).
 *   {@link #min(String)} and {@link #max(String)} returns the minimum and maximum values
 *   of this vector.</li>
 *   <li>{@link #freqs(String)} All terms indexed in a {@link TextField}, with stats,
 *   useful for list of terms and advanced lexical statistics.</li>
 *   <li>{@link #docLength(String)} Size of indexed documents in a {@link TextField}</li>
 *   <li>{@link #facet(String, String)} All terms of a facet field
 *   ({@link SortedDocValuesField} or {@link SortedSetDocValuesField}) with lexical statistics from a
 *   {@link TextField} (ex: count of words for an author facet)</li>
 *   <li>{@link #scale(String, String)} Data to build chronologies or other charts.</li>
 *   <li>{@link #cooc(String)}} Returns a co-occurrences reader (needs a specific indexation, {@link Cooc}).</li>
 * </ul>
 * 
 * @author Pierre DITTGEN (2009, original idea, creation)
 */
public class Alix
{
  /** Mandatory field, XML source file name, used for update */
  public static final String FILENAME = "alix:filename";
  /** Mandatory field, unique id for a book and its chapters */
  public static final String BOOKID = "alix:bookid";
  /** Mandatory field, unique id provide by user for all documents */
  public static final String ID = "alix:id";
  /** Mandatory field, define the level of a leaf (book/chapter, article) */
  public static final String LEVEL = "alix:level";
  /** Level type, book containing chapters */
  public static final String BOOK = "book";
  /** Level type, chapter in a book */
  public static final String CHAPTER = "chapter";
  /** Level type, independent article */
  public static final String ARTICLE = "article";
  /** A binary field */
  public static final String _OFFSETS = "_offsets";
  /** Suffix for a numeric field containing length of a text field in token */
  public static final String _WIDTH = "_width";
  /** Suffix for a text field containing only names */
  public static final String _NAMES = "_names";
  /** Max books */
  private static final int MAXBOOKS = 10000;
  /** Current filename proceded */
  public static final FieldType ftypeAll = new FieldType();
  static {
    // inverted index
    ftypeAll.setTokenized(true);
    // keep norms for Similarity, http://makble.com/what-is-lucene-norms
    ftypeAll.setOmitNorms(false);
    // position needed for phrase query, take also
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
  /** Shared Similarity for indexation and searching */
  public final Similarity similarity;
  /** A locale used for sorting terms */
  public final Locale locale;
  /** A global cache for objects */
  private final ConcurrentHashMap<String, SoftReference<Object>> cache = new ConcurrentHashMap<>();
  /** The lucene directory, kept private, to avoid closing by a thread */
  private Directory dir;
  /** The IndexReader if requested */
  private IndexReader reader;
  /** The infos on field */
  private FieldInfos fieldInfos;
  /** The IndexSearcher if requested */
  private IndexSearcher searcher;
  /** The IndexWriter if requested */
  private IndexWriter writer;
  /** Analyzer for indexation and query */
  final private Analyzer analyzer;

  /**
   * Avoid construction, maintain a pool by file path to ensure unicity.
   * 
   * @param path
   * @throws IOException
   */
  private Alix(final Path path, final Analyzer analyzer) throws IOException
  {
    // this default locale will work for English
    this.locale = Locale.FRANCE;
    this.path = path;
    Files.createDirectories(path);
    this.similarity = new BM25Similarity(); // default similarity
    // dir = FSDirectory.open(indexPath);
    // open directory as a memory map, very efficient, https://dzone.com/articles/use-lucene’s-mmapdirectory
    dir = MMapDirectory.open(path);
    this.analyzer = analyzer;
  }

  /**
   * See {@link #instance(Path, Class)}
   * @param path
   * @param analyzerClass
   * @throws ClassNotFoundException 
   */
  public static Alix instance(final String path, final String analyzerClass) throws IOException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, ClassNotFoundException
  {
    return instance(Paths.get(path), analyzerClass);
  }

  /**
   *  Get a a lucene directory index by file path, from cache, or created.
   *  
   * @param path
   * @param analyzerClass
   * @return
   * @throws ClassNotFoundException 
   * @throws SecurityException 
   * @throws NoSuchMethodException 
   * @throws InvocationTargetException 
   * @throws IllegalArgumentException 
   * @throws IllegalAccessException 
   * @throws InstantiationException 
   * @throws IOException 
   */
  public static Alix instance(Path path, final String analyzerClass) throws ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, IOException 
  {
    path = path.toAbsolutePath().normalize(); // normalize path to be a key
    Alix alix = pool.get(path);
    if (alix == null) {
      Class<?> cls = Class.forName(analyzerClass);
      Analyzer analyzer = (Analyzer) cls.getDeclaredConstructor().newInstance();
      alix = new Alix(path, analyzer);
      pool.put(path, alix);
    }
    return alix;
  }

  /**
   * See {@link #writer(Similarity)}
   * 
   * @return
   * @throws IOException
   */
  public IndexWriter writer() throws IOException
  {
    return writer(null);
  }

  /**
   * Get a lucene writer
   * 
   * @throws IOException
   */
  public IndexWriter writer(final Similarity similarity) throws IOException
  {
    if (writer != null && writer.isOpen()) return writer;
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    conf.setUseCompoundFile(false); // show separate file by segment
    // may needed, increase the max heap size to the JVM (eg add -Xmx512m or
    // -Xmx1g):
    conf.setRAMBufferSizeMB(48);
    conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
    //
    if (similarity != null) conf.setSimilarity(similarity);
    else conf.setSimilarity(this.similarity);
    // no effect found with modification ConcurrentMergeScheduler
    /*
     * int threads = Runtime.getRuntime().availableProcessors() - 1;
     * ConcurrentMergeScheduler cms = new ConcurrentMergeScheduler();
     * cms.setMaxMergesAndThreads(threads, threads); cms.disableAutoIOThrottle();
     * conf.setMergeScheduler(cms);
     */
    // order docId by a field after merge ? No functionality should rely on such
    // order
    // conf.setIndexSort(new Sort(new SortField(YEAR, SortField.Type.INT)));
    writer = new IndexWriter(dir, conf);
    return writer;
  }

  /**
   * See {@link #reader(boolean)}
   * 
   * @return
   * @throws IOException
   */
  public IndexReader reader() throws IOException
  {
    return reader(false);
  }

  /**
   * Get a reader for this lucene index, cached or new.
   * Allow to force renew if force is true.
   * 
   * @param force
   * @return
   * @throws IOException
   */
  public IndexReader reader(final boolean force) throws IOException
  {
    if (!force && reader != null) return reader;
    cache.clear(); // clean cache on renew the reader
    reader = DirectoryReader.open(dir);
    fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    return reader;
  }

  /**
   * A real time reader only used for some updates (see {@link Cooc#write()}).
   * 
   * @param force
   * @return
   * @throws IOException
   */
  public IndexReader reader(IndexWriter writer) throws IOException
  {
    return DirectoryReader.open(writer, true, true);
  }

  /**
   * See {@link #searcher(boolean)}
   * 
   * @return
   * @throws IOException
   */
  public IndexSearcher searcher() throws IOException
  {
    return searcher(false);
  }

  /**
   * Get the searcher for this lucene index, allow to force renew if force is true.
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
    searcher.setSimilarity(similarity);
    return searcher;
  }
  
  /**
   * Returns the analyzer shared with this base.
   * @return
   */
  public Analyzer analyzer()
  {
    return this.analyzer;
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
   * Get infos for a field.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public FieldInfo info(String field) throws IOException
  {
    reader(); // ensure reader or decache
    return fieldInfos.fieldInfo(field);
  }

  /**
   * Returns an array in docId order with the value of an intPoint field (ex:
   * year).
   * 
   * @return
   * @throws IOException
   */
  public int[] docInt(String field) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    String key = "AlixDocInt" + field;
    int[] ints = (int[]) cache(key);
    if (ints != null) return ints;
    // build the list
    FieldInfo info = fieldInfos.fieldInfo(field);
    // check infos
    if (info.getPointDataDimensionCount() <= 0 && info.getDocValuesType() != DocValuesType.NUMERIC) {
      throw new IllegalArgumentException(
          "Field \"" + field + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
    }
    int maxDoc = reader.maxDoc();
    final int[] docInt = new int[maxDoc];
    // fill with min value for deleted docs or with no values
    Arrays.fill(docInt, Integer.MIN_VALUE);
    final int[] minMax = { Integer.MAX_VALUE, Integer.MIN_VALUE };
    if (info.getDocValuesType() == DocValuesType.NUMERIC) {
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        NumericDocValues docs4num = leaf.getNumericDocValues(field);
        // no values for this leaf, go next
        if (docs4num == null) continue;
        final Bits liveDocs = leaf.getLiveDocs();
        final int docBase = context.docBase;
        int docLeaf;
        while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
          int v = (int) docs4num.longValue(); // long value is force to int;
          docInt[docBase + docLeaf] = v;
          if (minMax[0] > v) minMax[0] = v;
          if (minMax[1] < v) minMax[1] = v;
        }
      }
    }
    else if (info.getPointDataDimensionCount() > 0) {
      if (info.getPointDataDimensionCount() > 1) {
        throw new IllegalArgumentException("Field \"" + field + "\" " + info.getPointDataDimensionCount()
            + " dimensions, too much for an int tag by doc.");
      }
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
          public void visit(int docLeaf, byte[] packedValue)
          {
            if (liveDocs != null && !liveDocs.get(docLeaf)) return;
            // in case of multiple values, take the lower one
            if (docInt[docBase + docLeaf] > Integer.MIN_VALUE) return;
            int v = IntPoint.decodeDimension(packedValue, 0);
            docInt[docBase + docLeaf] = v;
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue)
          {
            int v = IntPoint.decodeDimension(minPackedValue, 0);
            if (minMax[0] > v) minMax[0] = v;
            v = IntPoint.decodeDimension(maxPackedValue, 0);
            if (minMax[1] < v) minMax[1] = v;
            // Answer that the query needs further infornmation to visit doc with values
            return Relation.CELL_CROSSES_QUERY;
          }
        });
      }
    }
    cache(key, docInt);
    cache("AlixMinMax" + field, minMax);
    return docInt;
  }

  /** 
   * Return the min value of an IntPoint field.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public int min(String field) throws IOException
  {
    return minMax(field, 0);
  }

  /** 
   * Returns the max value of an IntPoint field.
   * @param field
   * @return
   * @throws IOException
   */
  public int max(String field) throws IOException
  {
    return minMax(field, 1);
  }

  /**
   * Get min-max from the cache.
   * @param field
   * @param i
   * @return
   * @throws IOException
   */
  private int minMax(String field, int i) throws IOException
  {
    int[] minMax = (int[]) cache("AlixMinMax" + field);
    if (minMax == null) {
      docInt(field); // ensure calculation
      minMax = (int[]) cache("AlixMinMax" + field);
    }
    return minMax[i];
  }

  /**
   * Get value by docId of a unique store field, desired type is given by the
   * array to load. Very slow, ~1.5 s. / 1000 books
   * @param field
   * @param load
   * @return
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
      int v = doc.getField(field).numericValue().intValue();
      load[i] = v;
    }
    return load;
  }

  /**
   * See {@link #facet(String, String, Term)}
   * 
   * @param facetField
   * @param textField
   * @return
   * @throws IOException
   */
  public Facet facet(final String facetField, final String textField) throws IOException
  {
    return facet(facetField, textField, null);
  }

  /**
   * Get a “facet” object, a cached list of terms from a field of type
   * {@link SortedDocValuesField} or {@link SortedSetDocValuesField} ; to get lexical stats from a
   * text field. An optional “term” (field:value) maybe used to catch a “cover”
   * document (ex: a document carrying metada about a title or an author).
   *
   * @param facetField
   *          A SortedDocValuesField or a SortedSetDocValuesField fieldName.
   * @param textField
   *          A indexed TextField.
   * @param coverTerm
   *          A couple field:value to catch one document by facet term.
   * @return
   * @throws IOException
   */
  public Facet facet(final String facetField, final String textField, final Term coverTerm) throws IOException
  {
    String key = "AlixFacet" + facetField + textField;
    Facet facet = (Facet) cache(key);
    if (facet != null) return facet;
    facet = new Facet(this, facetField, textField, coverTerm);
    cache(key, facet);
    return facet;
  }

  /**
   * Get a Scale object, useful to build graphs and chronology with an int field.
   * 
   * @param fieldInt
   *          A NumericDocValuesField used as a sorted value.
   * @param fieldText
   *          A Texfield to count occurences, used as a size for docs.
   * @return
   * @throws IOException
   */
  public Scale scale(final String fieldInt, final String fieldText) throws IOException
  {
    String key = "AlixScale" + fieldInt + fieldText;
    Scale scale = (Scale) cache(key);
    if (scale != null) return scale;
    scale = new Scale(this, null, fieldInt, fieldText);
    cache(key, scale);
    return scale;
  }

  /**
   * Get a frequence object.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public Freqs freqs(final String field) throws IOException
  {
    String key = "AlixFreqs" + field;
    Freqs freqs = (Freqs) cache(key);
    if (freqs != null) return freqs;
    freqs = new Freqs(reader(), field);
    cache(key, freqs);
    return freqs;
  }

  /**
   * Get a co-occurrences reader.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public Cooc cooc(final String field) throws IOException
  {
    String key = "AlixCooc" + field;
    Cooc cooc = (Cooc) cache(key);
    if (cooc != null) return cooc;
    cooc = new Cooc(this, field);
    cache(key, cooc);
    return cooc;
  }

  /**
   * For a field, return an array in docId order, with the total number of tokens
   * by doc. Term vector cost 1 s. / 1000 books and is not precise. Norms for
   * similarity is not enough precise (1 byte) see SimilarityBase.computeNorm()
   * https://github.com/apache/lucene-solr/blob/master/lucene/core/src/java/org/apache/lucene/search/similarities/SimilarityBase.java#L185
   * A field could be recorded at indexation, then user knows its name and get it
   * by docInt(). Solution: pre-calculate the lengths by a cached Freqs object, which
   * have loop
   * 
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public int[] docLength(String field) throws IOException
  {
    return freqs(field).docLength;
  }

  /**
   * Get docId parent documents (books) of nested documents (chapters), sorted by
   * a sort specification.
   * 
   * @throws IOException
   */
  public int[] books(Sort sort) throws IOException
  {
    IndexSearcher searcher = searcher(); // ensure reader or decache
    String key = "AlixBooks" + sort;
    int[] books = (int[]) cache(key);
    if (books != null) return books;
    Query qBook = new TermQuery(new Term(Alix.LEVEL, Alix.BOOK));
    TopFieldDocs top = searcher.search(qBook, MAXBOOKS, sort);
    int length = top.scoreDocs.length;
    ScoreDoc[] docs = top.scoreDocs;
    books = new int[length];
    for (int i = 0; i < length; i++) {
      books[i] = docs[i].doc;
    }
    cache(key, books);
    return books;
  }

  /**
   * 
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  public Query qParse(String field, String q) throws IOException
  {
    // float[] boosts = { 2.0f, 1.5f, 1.0f, 0.7f, 0.5f };
    // int boostLength = boosts.length;
    // float boostDefault = boosts[boostLength - 1];
    TokenStream ts = analyzer.tokenStream(field, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

    ts.reset();
    Query qTerm = null;
    BooleanQuery.Builder bq = null;
    try {
      while (ts.incrementToken()) {
        if (Tag.isPun(flags.getFlags())) continue;
        if (qTerm != null) {
          if (bq == null) bq = new BooleanQuery.Builder();
          bq.add(qTerm, Occur.SHOULD);
        }
        qTerm = new TermQuery(new Term(field, token.toString()));
        /*
         * float boost = boostDefault; if (i < boostLength) boost = boosts[i]; qTerm =
         * new BoostQuery(qTerm, boost);
         */
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
   * 
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  @SuppressWarnings("unlikely-arg-type")
  public TermList qTerms(String q, String field) throws IOException
  {

    TokenStream ts = analyzer.tokenStream(field, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

    TermList terms = new TermList(freqs(field));
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
        if (lem.length() > 0) terms.add(new Term(field, lem.toString()));
        else terms.add(new Term(field, token.toString()));
      }
      ts.end();
    }
    finally {
      ts.close();
    }
    return terms;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(path + "\n");
    try {
      reader(); // get FieldInfos
    }
    catch (Exception e) {
    }
    for (FieldInfo info : fieldInfos) {
      sb.append(info.name + " PointDataDimensionCount=" + info.getPointDataDimensionCount() + " DocValuesType="
          + info.getDocValuesType() + " IndexOptions=" + info.getIndexOptions() + "\n");
    }
    sb.append(fieldInfos.toString());
    return sb.toString();
  }
}
