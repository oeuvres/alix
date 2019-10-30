/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
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
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Bits;

import alix.fr.Tag;
import alix.lucene.search.Facet;
import alix.lucene.search.Scale;
import alix.lucene.search.Freqs;
import alix.lucene.search.IntSeries;
import alix.lucene.search.TermList;
import alix.lucene.util.Cooc;

/**
 * An Alix instance represents a Lucene base {@link Directory} with other useful data.
 * Instantiation is not public, use {@link #instance(Path, String)} instead.                                                                                                                                                       
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
 * @author Pierre Dittgen (2009, original idea, creation)
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
  /** A binary stored field with an array of offsets */
  public static final String _OFFSETS = ":offsets";
  /** A binary stored with {@link Tag} by position */
  public static final String _TAGS = ":tags";
  /** Suffix for a text field containing only names */
  public static final String _NAMES = ":names";
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
    // do not store here to allow fine grain control
    ftypeText.setStored(false); // store not allowed 
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
    ftypeMeta.setStored(false); // store not allowed when indexoing token stream
    ftypeMeta.freeze();
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
   * @param analyzerClass
   * @throws IOException
   * @throws ClassNotFoundException 
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
    this.analyzer = new AnalyzerReuseControl(analyzer, new AlixReuseStrategy());
  }

  /**
   * See {@link #instance(Path, String)}
   * @param path
   * @param analyzerClass
   * @throws IOException 
   * @throws ClassNotFoundException 
   */
  public static Alix instance(final String path, final Analyzer analyzer) throws IOException 
  {
    return instance(Paths.get(path), analyzer);
  }

  /**
   *  Get a a lucene directory index by file path, from cache, or created.
   *  
   * @param path
   * @param analyzerClass
   * @return
   * @throws IOException
   * @throws ClassNotFoundException
   */
  public static Alix instance(Path path, final Analyzer analyzer) throws IOException 
  {
    path = path.toAbsolutePath().normalize(); // normalize path to be a key
    Alix alix = pool.get(path);
    if (alix == null) {
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
  public IntSeries intSeries(String field) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    String key = "AlixIntSeries" + field;
    IntSeries ints = (IntSeries) cache(key);
    if (ints != null) return ints;
    ints = new IntSeries(reader, field);
    cache(key, ints);
    return ints;
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
u   * @throws IOException
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
  public Query qParse(final String field, final String q) throws IOException
  {
    return qParse(field, q, this.analyzer);
  }

  static public Query qParse(final String field, final String q, final Analyzer analyzer) throws IOException
  {
    return qParse(field, q, analyzer, Occur.SHOULD);
  }
  /**
   * 
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  static public Query qParse(final String field, final String q, final Analyzer analyzer, final Occur occur) throws IOException
  {
    if (q == null || "".equals(q.trim())) return null;
    // float[] boosts = { 2.0f, 1.5f, 1.0f, 0.7f, 0.5f };
    // int boostLength = boosts.length;
    // float boostDefault = boosts[boostLength - 1];
    TokenStream ts = analyzer.tokenStream("q", q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

    ts.reset();
    Query qTerm = null;
    BooleanQuery.Builder bq = null;
    try {
      while (ts.incrementToken()) {
        if (Tag.isPun(flags.getFlags())) continue;
        if (bq == null && qTerm != null) { // second term, create boolean
          bq = new BooleanQuery.Builder();
          bq.add(qTerm, occur);
        }
        int len = token.length();
        while(--len >= 0 && token.charAt(len) != '*');
        if (len > 0) qTerm = new WildcardQuery(new Term(field, token.toString()));
        else qTerm = new TermQuery(new Term(field, token.toString()));
        
        if (bq != null) { // more than one term
          bq.add(qTerm, occur);
        }
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
    if (bq != null) return bq.build();
    else return qTerm;
  }

  /**
   * Provide tokens as a table of terms
   * 
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  public TermList qTerms(String q, String field) throws IOException
  {
    TermList terms = new TermList(freqs(field));
    // what is null here ? returns an empty term list
    if (q == null || "".equals(q.trim())) return terms;
    TokenStream ts = analyzer.tokenStream("pun", q); // keep punctuation to group terms
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    // not generic for other analyzers but may become interesting for a query parser
    // CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
    // FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);
    ts.reset();
    try {
      while (ts.incrementToken()) {
        final String tok = token.toString();
        /*
        final int tag = flags.getFlags();
        if (Tag.isPun(tag)) {
          // start a new line
          if (token.equals(";") || tag == Tag.PUNsent) {
            terms.add(null);
          }
          continue;
        }
        */
        if (",".equals(tok) || ";".equals(tok)) {
          terms.add(null);
          continue;
        }
        terms.add(new Term(field, tok));
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
