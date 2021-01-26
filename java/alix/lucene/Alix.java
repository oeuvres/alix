/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.AlixReuseStrategy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerReuseControl;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Bits;

import alix.fr.Tag;
import alix.lucene.search.FieldFacet;
import alix.lucene.search.Scale;
import alix.lucene.search.FieldText;
import alix.lucene.search.FieldRail;
import alix.lucene.search.FieldInt;

/**
 * <p>
 * An Alix object is a wrapper around a Lucene index with lexical tools,
 * to be shared across a complex application (ex: web servlet).
 * Instantiation is not public to ensure uniqueness of threadsafe Lucene objects
 * ({@link Directory}, {@link IndexReader}, {@link IndexSearcher}, {@link IndexWriter}
 * and {@link Analyzer}).
 * Use {@link #instance(Path, Analyzer)} to get an Alix instance, 
 * and get from it what you need for your classical Lucene bizness.
 * </p>
 * 
 * <ul>
 *   <li>{@link #reader()}</li>
 *   <li>{@link #writer()}</li>
 *   <li>{@link #searcher()}</li>
 *   <li>{@link #analyzer()}</li>
 * </ul>
 * 
 * <p>
 * An Alix object will also produce 
 * different lists and stats concerning all index.
 * These results are cached {@link #cache(String, Object)} 
 * (to avoid recalculation). 
 * Data are usually available as custom objects, optimized for statistics.
 * </p>
 * 
 * <ul>
 *   <li>{@link #fieldInt(String)} All values of a unique numeric field per document 
 *   ({@link IntPoint}, {@link NumericDocValuesField}).</li>
 *   <li>{@link #fieldText(String)} All search indexed in a {@link TextField}, with stats,
 *   useful for list of search and advanced lexical statistics.</li>
 *   <li>{@link #docOccs(String)} Size (in tokens) of indexed documents in a {@link TextField}</li>
 *   <li>{@link #fieldFacet(String, String)} All search of a facet field
 *   ({@link SortedDocValuesField} or {@link SortedSetDocValuesField}) with lexical statistics from a
 *   {@link TextField} (ex: count of words for an author facet)</li>
 *   <li>{@link #scale(String, String)} Data to build chronologies or other charts.</li>
 * </ul>
 * 
 * @author Pierre Dittgen (2009, original idea, creation)
 */
public class Alix
{
  /** Name of the application (for messages) */
  public static final String NAME = "Alix";
  /** Mandatory field, XML source file name, used for update */
  public static final String FILENAME = "alix.filename";
  /** Mandatory field, unique id for a book and its chapters */
  public static final String BOOKID = "alix.bookid";
  /** Mandatory field, unique id provide by user for all documents */
  public static final String ID = "alix.id";
  /** Mandatory field, define the level of a leaf (book/chapter, article) */
  public static final String TYPE = "alix.type";
  /** Just the mandatory fields */
  final static HashSet<String> FIELDS_ID = new HashSet<String>(Arrays.asList(new String[] {Alix.ID}));
  /** Max books */
  private static final int MAXBOOKS = 10000;
  /** Lucene field type for alix text field */
  public static final FieldType ftypeText = new FieldType();
  static {
    ftypeText.setTokenized(true);
    // freqs required, position needed for co-occurrences
    ftypeText.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    ftypeText.setOmitNorms(false); // keep norms for Similarity, http://makble.com/what-is-lucene-norms
    ftypeText.setStoreTermVectors(true);
    ftypeText.setStoreTermVectorPositions(true);
    ftypeText.setStoreTermVectorOffsets(true);
    ftypeText.setStored(false); // TokenStream fields cannot be stored 
    ftypeText.freeze();
  }
  /** lucene field type for alix meta type */
  public static final FieldType ftypeMeta = new FieldType();
  static {
    ftypeMeta.setTokenized(true); // token
    // freqs required, position needed for co-occurrences
    ftypeMeta.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
    ftypeMeta.setOmitNorms(false); // keep norms for Similarity, http://makble.com/what-is-lucene-norms
    ftypeMeta.setStoreTermVectors(false); // no vectors, hilite done by anlalyzer
    ftypeMeta.setStored(false); // TokenStream fields cannot be stored 
    ftypeMeta.freeze();
  }
  /** Pool of instances, unique by path */
  public static final HashMap<String, Alix> pool = new HashMap<String, Alix>();
  /** Normalized path */
  public final Path path;
  /** User properties for the base, freely set or modified */
  public final Properties props;
  /** Shared Similarity for indexation and searching */
  public final Similarity similarity;
  /** A locale used for sorting search */
  public final Locale locale;
  /** A global cache for objects */
  private final ConcurrentHashMap<String, SoftReference<Object>> cache = new ConcurrentHashMap<>();
  /** The lucene directory, kept private, to avoid closing by a thread */
  private Directory dir;
  /** The IndexReader if requested */
  private DirectoryReader reader;
  /** Max for docId */
  private int maxDoc;
  /** The infos on field */
  private FieldInfos fieldInfos;
  /** The IndexSearcher if requested */
  private IndexSearcher searcher;
  /** The IndexWriter if requested */
  private IndexWriter writer;
  /** Analyzer for indexation and search */
  final private Analyzer analyzer;

  public enum FSDirectoryType {
    MMapDirectory,
    NIOFSDirectory,
    FSDirectory
  }
  
  private Alix(final Path path, final Analyzer analyzer) throws IOException
  {
    this(path, analyzer, null);
  }

  /**
   * Avoid construction, maintain a pool by file path to ensure unicity.
   * 
   * @param path
   * @param analyzerClass
   * @throws IOException
   * @throws ClassNotFoundException 
   */
  private Alix(final Path path, final Analyzer analyzer, FSDirectoryType dirType) throws IOException
  {
    // this default locale will work for English
    this.locale = Locale.FRANCE;
    this.path = path;
    Files.createDirectories(path);
    this.similarity = new BM25Similarity(); // default similarity
    if(dirType == null) dirType = FSDirectoryType.FSDirectory;
    switch(dirType) {
      case MMapDirectory:
        dir = MMapDirectory.open(path);
        break;
      case NIOFSDirectory:
        dir = NIOFSDirectory.open(path);
        break;
      default:
        dir = FSDirectory.open(path);
        break;
    }
    this.analyzer = new AnalyzerReuseControl(analyzer, new AlixReuseStrategy());
    this.props = new Properties();
  }


  public static Alix instance(String name) 
  {
    return pool.get(name);
  }

  /**
   *  Get a a lucene directory index by file path, from cache, or created.
   * @param path
   * @param analyzer
   * @return
   * @throws IOException
   */
  public static Alix instance(String name, Path path, final Analyzer analyzer, FSDirectoryType dirType) throws IOException 
  {
    Alix alix = pool.get(name);
    if (alix == null) {
      alix = new Alix(path, analyzer, dirType);
      pool.put(name, alix);
    }
    return alix;
  }

  /**
   * See {@link #reader(boolean)}
   * 
   * @return
   * @throws IOException
   */
  public DirectoryReader reader() throws IOException
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
  public DirectoryReader reader(final boolean force) throws IOException
  {
    if (!force && reader != null) return reader;
    cache.clear(); // clean cache on renew the reader
    reader = DirectoryReader.open(dir);
    fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    maxDoc = reader.maxDoc();
    return reader;
  }

  /**
   * A real time reader only used for some updates.
   * 
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
   * Returns the analyzer shared with this base.
   * @return
   */
  public Analyzer analyzer()
  {
    return this.analyzer;
  }
  
  /** 
   * @return @see IndexReader#maxDoc() 
   * @throws IOException 
   */
  public int maxDoc() throws IOException
  {
    if (reader == null) reader();
    return maxDoc;
  }

  /**
   * Get the internal lucene docid of a document by Alix String id 
   * (a reserved field name)
   * @param id
   * @return the docId, or -1 if not found, or -2 if too much found, or -3 if id was null or empty.
   * @throws IOException
   */
  public int getDocId(final String id) throws IOException
  {
    if (id == null || id.trim().length() == 0) return -3;
    TermQuery qid = new TermQuery(new Term(Alix.ID, id));
    TopDocs search = searcher().search(qid, 1);
    ScoreDoc[] hits = search.scoreDocs;
    if (hits.length == 0) return -1;
    if (hits.length > 1) return -2;
    return hits[0].doc;
  }

  /**
   * Get the the Alix String id of a document by the lucene internal docid.
   * @param docId
   * @return
   * @throws IOException
   */
  public String getId(final int docId) throws IOException
  {
    Document doc = reader().document(docId, FIELDS_ID);
    if (doc == null) return null;
    return doc.get(Alix.ID);
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
   * Get infos for a field.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public FieldInfo info(Enum<?> field) throws IOException
  {
    reader(); // ensure reader or decache
    return fieldInfos.fieldInfo(field.name());
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
   * See {@link #fieldFacet(String, String, Term)}
   * 
   * @param facetField
   * @param textField
   * @return
   * @throws IOException
   */
  public FieldFacet fieldFacet(final String facetField, final String textField) throws IOException
  {
    return fieldFacet(facetField, textField, null);
  }

  /**
   * Get a “facet” object, a cached list of search from a field of type
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
  public FieldFacet fieldFacet(final String facetField, final String textField, final Term coverTerm) throws IOException
  {
    String key = "AlixFacet" + facetField + textField;
    FieldFacet facet = (FieldFacet) cache(key);
    if (facet != null) return facet;
    facet = new FieldFacet(this, facetField, textField, coverTerm);
    cache(key, facet);
    return facet;
  }

  /**
   * Returns an array in docId order with the value of an intPoint field (ex:
   * year).
   * 
   * @return
   * @throws IOException
   */
  public FieldInt fieldInt(final String fintName, final String ftextName) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    String key = "AlixFiedInt" + fintName + "_" + ftextName;
    FieldInt ints = (FieldInt) cache(key);
    if (ints != null) return ints;
    ints = new FieldInt(this, fintName, ftextName);
    cache(key, ints);
    return ints;
  }

  /**
   * Get a frequence object.
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public FieldText fieldText(final String field) throws IOException
  {
    String key = "AlixFreqs" + field;
    FieldText fieldText = (FieldText) cache(key);
    if (fieldText != null) return fieldText;
    fieldText = new FieldText(reader(), field);
    cache(key, fieldText);
    return fieldText;
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
   * Get a co-occurrences reader.
   * 
   * @param field
   * @return
u   * @throws IOException
   */
  public FieldRail fieldRail(final String field) throws IOException
  {
    String key = "AlixRail" + field;
    FieldRail fieldRail = (FieldRail) cache(key);
    if (fieldRail != null) return fieldRail;
    fieldRail = new FieldRail(this, field);
    cache(key, fieldRail);
    return fieldRail;
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
  public int[] docOccs(String field) throws IOException
  {
    return fieldText(field).docOccs;
  }

  /**
   * Get docId parent documents (books) of nested documents (chapters), sorted by
   * a sort specification. Calculation is not really expensive, do not cache.
   * 
   * @throws IOException
   */
  public int[] books(Sort sort) throws IOException
  {
    IndexSearcher searcher = searcher(); // ensure reader or decache
    Query qBook = new TermQuery(new Term(Alix.TYPE, DocType.book.name()));
    TopFieldDocs top = searcher.search(qBook, MAXBOOKS, sort);
    int length = top.scoreDocs.length;
    ScoreDoc[] docs = top.scoreDocs;
    int[] books = new int[length];
    for (int i = 0; i < length; i++) {
      books[i] = docs[i].doc;
    }
    return books;
  }
  public Query query(final String field, final String q) throws IOException
  {
    return query(field, q, this.analyzer);
  }
  
  static public Query query(final String field, final String q, final Analyzer analyzer) throws IOException
  {
    return query(field, q, analyzer, Occur.SHOULD);
  }

  /**
   * 
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  static public Query query(final String field, final String q, final Analyzer analyzer, final Occur occur) throws IOException
  {
    if (q == null || "".equals(q.trim())) return null;
    // float[] boosts = { 2.0f, 1.5f, 1.0f, 0.7f, 0.5f };
    // int boostLength = boosts.length;
    // float boostDefault = boosts[boostLength - 1];
    TokenStream ts = analyzer.tokenStream(AlixReuseStrategy.QUERY, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

    ts.reset();
    Query qTerm = null;
    BooleanQuery.Builder bq = null;
    Occur op = null;
    bq = null;
    int neg = 0;
    int aff = 0;
    try {
      while (ts.incrementToken()) {
        // if (Tag.isPun(flags.getFlags())) continue;
        // position may have been striped
        if (token.length() == 0) continue;
        if (bq == null && qTerm != null) { // second term, create boolean
          bq = new BooleanQuery.Builder();
          bq.add(qTerm, op);
        }
        String word;
        if (token.charAt(0) == '-') {
          op = Occur.MUST_NOT;
          word = token.subSequence(1, token.length()).toString();
          neg++;
        }
        else if (token.charAt(0) == '+') {
          op = Occur.MUST;
          word = token.subSequence(1, token.length()).toString();
          aff++;
        }
        else {
          op = occur;
          word = token.toString();
          aff++;
        }
        
        
        int len = word.length();
        while(--len >= 0 && word.charAt(len) != '*');
        if (len > 0) qTerm = new WildcardQuery(new Term(field, word));
        else qTerm = new TermQuery(new Term(field, word));

        if (bq != null) { // more than one term
          bq.add(qTerm, op);
        }

      }
      ts.end();
    }
    finally {
      ts.close();
    }
    if (neg > 0 && aff == 0 && bq != null) bq.add(new MatchAllDocsQuery(), Occur.MUST);
    if (bq != null) return bq.build();
    
    if (neg > 0 && aff == 0) {
      bq = new BooleanQuery.Builder();
      bq.add(new MatchAllDocsQuery(), Occur.MUST);
      bq.add(qTerm, occur);
      return bq.build();
    }
    return qTerm;
  }
  
  /**
   * Analyze a search according to the default analyzer of this base,
   * is especially needed for multi-words ex: "en effet" 
   * return search as an array of string,
   * supposing that caller knows the field he wants to search.
   * 
   * @param q
   * @param fieldName
   * @return
   * @throws IOException
   */
  public String[] forms(final String q) throws IOException
  {
    return forms(q, this.analyzer);
  }

  /**
   * Analyze a search according to the current analyzer of this base ; return search 
   * 
   * @param q
   * @param fieldName
   * @return
   * @throws IOException
   */
  public static String[] forms(final String q, final Analyzer analyzer) throws IOException
  {
    // create an arrayList on each search and let gc works
    ArrayList<String> forms = new ArrayList<String>();
    // what should mean null here ?
    if (q == null || "".equals(q.trim())) return null;
    TokenStream ts = analyzer.tokenStream(AlixReuseStrategy.QUERY, q); // keep punctuation to group search
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    // not generic for other analyzers but may become interesting for a search parser
    // CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
    // FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);
    ts.reset();
    try {
      while (ts.incrementToken()) {
        // a negation term
        if (token.charAt(0) == '-') continue;
        String word;
        if (token.charAt(0) == '+') {
          word = token.subSequence(1, token.length()).toString();
        }
        else {
          word = token.toString();
        }
        /*
        final int tag = flags.getFlags();
        if (Tag.isPun(tag)) {
          // start a new line
          if (token.equals(";") || tag == Tag.PUNsent) {
            search.add(null);
          }
          continue;
        }
        */
        /* 
        if (",".equals(word) || ";".equals(word)) {
          continue;
        }
        */
        forms.add(word);
      }
      ts.end();
    }
    finally {
      ts.close();
    }
    return forms.toArray(new String[forms.size()]);
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(path + "\n");
    sb.append(dir + "\n");
    try {
      reader(); // get FieldInfos
    }
    catch (Exception e) {
    }
    for (FieldInfo info : fieldInfos) {
      sb.append(info.name + " PointDataDimensionCount=" + info.getPointDimensionCount() + " DocValuesType="
          + info.getDocValuesType() + " IndexOptions=" + info.getIndexOptions() + "\n");
    }
    sb.append(fieldInfos.toString());
    return sb.toString();
  }
}
