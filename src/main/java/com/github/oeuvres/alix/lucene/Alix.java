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
package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
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

import static com.github.oeuvres.alix.Names.*;

import com.github.oeuvres.alix.lucene.search.FieldFacet;
import com.github.oeuvres.alix.lucene.search.Scale;
import com.github.oeuvres.alix.lucene.search.FieldText;
import com.github.oeuvres.alix.lucene.search.FieldRail;
import com.github.oeuvres.alix.lucene.search.FieldInt;

/**
 * <p>
 * An Alix object is a wrapper around a Lucene index with lexical tools, to be
 * shared across a complex application (ex: web servlet). Instantiation is not
 * public to ensure uniqueness of threadsafe Lucene objects ({@link Directory},
 * {@link IndexReader}, {@link IndexSearcher}, {@link IndexWriter} and
 * {@link Analyzer}). Use {@link #instance(String)} to get an Alix
 * instance, and get from it what you need for your classical Lucene bizness.
 * </p>
 * 
 * <ul>
 * <li>{@link #reader()}</li>
 * <li>{@link #writer()}</li>
 * <li>{@link #searcher()}</li>
 * <li>{@link #analyzer()}</li>
 * </ul>
 * 
 * <p>
 * An Alix object will also produce different lists and stats concerning all
 * index. These results are cached {@link #cache(String, Object)} (to avoid
 * recalculation). Data are usually available as custom objects, optimized for
 * statistics.
 * </p>
 * 
 * <ul>
 * <li>{@link #fieldInt(String)} All values of a unique numeric field per
 * document ({@link IntPoint}, {@link NumericDocValuesField}).</li>
 * <li>{@link #fieldText(String)} All search indexed in a {@link TextField},
 * with stats, useful for list of search and advanced lexical statistics.</li>
 * <li>{@link #fieldFacet(String)} All search of a facet field
 * ({@link SortedDocValuesField} or {@link SortedSetDocValuesField}) with
 * lexical statistics from a {@link TextField} (ex: count of words for an author
 * facet)</li>
 * <li>{@link #scale(String, String)} Data to build chronologies or other
 * charts.</li>
 * </ul>
 */
public class Alix
{
    /** Name of the application (for messages) */
    public static final String NAME = "Alix";
    /** Just the mandatory fields */
    final static HashSet<String> FIELDS_ID = new HashSet<String>(Arrays.asList(new String[] { ALIX_ID }));
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
        ftypeMeta.setIndexOptions(IndexOptions.DOCS_AND_FREQS); // no position needed
        ftypeMeta.setOmitNorms(false); // keep norms for Similarity, http://makble.com/what-is-lucene-norms
        ftypeMeta.setStoreTermVectors(false); // no vectors, hilite done by anlalyzer
        ftypeMeta.setStored(false); // TokenStream fields cannot be stored
        ftypeMeta.freeze();
    }
    /** Pool of instances, unique by path */
    public static final Map<String, Alix> pool = new LinkedHashMap<String, Alix>();
    /** Name of the base */
    public final String name;
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

    public enum FSDirectoryType
    {
        MMapDirectory, NIOFSDirectory, FSDirectory
    }

    private Alix(final String name, final Path path, final Analyzer analyzer) throws IOException
    {
        this(name, path, analyzer, null);
    }

    /**
     * Avoid construction, maintain a pool by file path to ensure unicity.
     * 
     * @param path
     * @param analyzerClass
     * @throws IOException Lucene errors.
     * @throws ClassNotFoundException
     */
    private Alix(final String name, final Path path, final Analyzer analyzer, FSDirectoryType dirType)
            throws IOException
    {
        this.name = name;
        // this default locale will work for English
        this.locale = Locale.FRANCE;
        this.path = path;
        Files.createDirectories(path);
        this.similarity = new BM25Similarity(); // default similarity
        if (dirType == null)
            dirType = FSDirectoryType.FSDirectory;
        switch (dirType) {
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
        // What about reuse strategy ?
        this.analyzer = analyzer;
        this.props = new Properties();
    }

    /**
     * Returns the analyzer shared with this base.
     * 
     * @return The analyzer for this Lucene index.
     */
    public Analyzer analyzer()
    {
        return this.analyzer;
    }

    /**
     * Get docId parent documents (books) of nested documents (chapters), sorted by
     * a sort specification.
     * 
     * @param sort Sort specification to get a list of books.
     * @return An ordered linst of docid.
     * @throws IOException Lucene exceptions.
     */
    public int[] books(Sort sort) throws IOException
    { 
        // Calculation is not really expensive, do not cache.
        IndexSearcher searcher = searcher(); // ensure reader or decache
        Query qBook = new TermQuery(new Term(ALIX_TYPE, BOOK));
        TopFieldDocs top = searcher.search(qBook, MAXBOOKS, sort);
        int length = top.scoreDocs.length;
        ScoreDoc[] docs = top.scoreDocs;
        int[] books = new int[length];
        for (int i = 0; i < length; i++) {
            books[i] = docs[i].doc;
        }
        return books;
    }

    /**
     * Get an Object from a local static cache.
     * 
     * @param key The key of a cached Object.
     * @return A cached Object.
     */
    public Object cache(String key)
    {
        SoftReference<Object> ref = cache.get(key);
        if (ref != null)
            return ref.get();
        return null;
    }

    /**
     * A simple cache. Will be cleared if index reader is renewed. Use SoftReference
     * as a value, so that Garbage Collector will silently delete object references
     * in case of Out of memory.
     * 
     * @param key Name to get back the Object.
     * @param o Cached Object.
     */
    public void cache(String key, Object o)
    {
        cache.put(key, new SoftReference<Object>(o));
    }

    /**
     * Get stored values in docId order. Be careful, not efifcient.
     * 
     * @param field Field name.
     * @param load Optional array to populate.
     * @return An array in docid order with the int value retrieved.
     * @throws IOException Lucene errors.
     */
    public String[] docStore(String field, String[] load) throws IOException
    {
        // 
        IndexReader reader = reader(); // ensure reader, or decache
        int maxDoc = reader.maxDoc();
        if (load == null || load.length < maxDoc)
            load = new String[maxDoc];
        Bits liveDocs = null;
        boolean hasDeletions = reader.hasDeletions();
        if (hasDeletions) {
            liveDocs = MultiBits.getLiveDocs(reader);
        }
        Set<String> fields = new HashSet<String>();
        fields.add(field);
        StoredFields docRead = reader().storedFields();
        for (int i = 0; i < maxDoc; i++) {
            if (hasDeletions && !liveDocs.get(i)) {
                continue;
            }
            Document doc = docRead.document(i, fields);
            // int v = doc.getField(field).numericValue().intValue();
            load[i] = doc.getField(field).stringValue();
        }
        return load;
    }

    /**
     * See {@link #fieldFacet(String, Term)}
     * 
     * @param fieldName Name of a field.
     * @return The FieldFacet for this name, or null.
     * @throws IOException Lucene errors.
     */
    public FieldFacet fieldFacet(final String fieldName) throws IOException
    {
        return fieldFacet(fieldName, null);
    }

    /**
     * Get a “facet” object, a cached list of search from a field of type
     * {@link SortedDocValuesField} or {@link SortedSetDocValuesField} ; to get
     * lexical stats from a text field. An optional “term” (field:value) maybe used
     * to catch a “cover” document (ex: a document carrying metada about a title or
     * an author).
     *
     * @param fieldName A SortedDocValuesField or a SortedSetDocValuesField
     *                   fieldName.
     * @param coverTerm  A couple field:value to catch one document by facet term.
     * @return The facet.
     * @throws IOException Lucene errors.
     */
    public FieldFacet fieldFacet(final String fieldName, final Term coverTerm)
            throws IOException
    {
        String key = "AlixFacet" + fieldName;
        FieldFacet facet = (FieldFacet) cache(key);
        if (facet != null) {
            return facet;
        }
        facet = new FieldFacet(this, fieldName, coverTerm);
        cache(key, facet);
        return facet;
    }

    /**
     * Get a list of intPoint (ex: year) in docid order.
     * 
     * @param fieldName An intPoint field name.
     * @return An array in docId order with the value of an intPoint field
     * @throws IOException Lucene errors.
     */
    public FieldInt fieldInt(final String fieldName) throws IOException
    {
        reader(); // ensure reader, or decache
        String key = "AlixFiedInt" + fieldName;
        FieldInt ints = (FieldInt) cache(key);
        if (ints != null) {
            return ints;
        }
        ints = new FieldInt(this, fieldName);
        cache(key, ints);
        return ints;
    }

    
    /**
     * Get a co-occurrences reader.
     * 
     * @param fieldName Name of a text field.
     * @return A “rail” Object to read co-occurrences.
     * @throws IOException Lucene errors.
     */
    public FieldRail fieldRail(final String fieldName) throws IOException
    {
        String key = "AlixRail" + fieldName;
        FieldRail fieldRail = (FieldRail) cache(key);
        if (fieldRail != null)
            return fieldRail;
        fieldRail = new FieldRail(this, fieldName);
        cache(key, fieldRail);
        return fieldRail;
    }

    /**
     * Get a frequence object.
     * 
     * @param fieldName Name of a text field.
     * @return An Object with lexical frequencies.
     * @throws IOException Lucene errors.
     */
    public FieldText fieldText(final String fieldName) throws IOException
    {
        String key = "AlixFreqs" + fieldName;
        FieldText fieldText = (FieldText) cache(key);
        if (fieldText != null)
            return fieldText;
        fieldText = new FieldText(reader(), fieldName);
        cache(key, fieldText);
        return fieldText;
    }
    
    /**
     * Returns the Alix type of a field name
     * @param fieldName Name of a field.
     * @return Type name.
     * @throws IOException Lucene errors.
     */
    public String ftype(final String fieldName) throws IOException
    {
        reader(); // ensure reader or decache
        FieldInfo info = fieldInfos.fieldInfo(fieldName);
        if (info == null) return NOTFOUND;
        DocValuesType type = info.getDocValuesType();
        if (type == DocValuesType.SORTED_SET || type == DocValuesType.SORTED) {
            return FACET;
        }
        if (info.getDocValuesType() == DocValuesType.NUMERIC || info.getPointDimensionCount() == 1) {
            return INT;
        }
        IndexOptions options = info.getIndexOptions();
        if (options != IndexOptions.NONE && options != IndexOptions.DOCS) {
            return TEXT;
        }
        if (options == IndexOptions.DOCS) {
            return FACET;
        }
        
        return NOTALIX;

    }

    /**
     * Get the internal lucene docid of a document by Alix String id (a reserved
     * field name)
     * 
     * @param id A document id provided at indexation.
     * @return The Lucene docId, or -1 if not found, or -2 if too much found, or -3 if id
     *         was null or empty.
     * @throws IOException Lucene errors.
     */
    public int getDocId(final String id) throws IOException
    {
        if (id == null || id.trim().length() == 0)
            return -3;
        TermQuery qid = new TermQuery(new Term(ALIX_ID, id));
        TopDocs search = searcher().search(qid, 1);
        ScoreDoc[] hits = search.scoreDocs;
        if (hits.length == 0)
            return -1;
        if (hits.length > 1)
            return -2;
        return hits[0].doc;
    }

    /**
     * Get the the id of a document by the lucene internal docid.
     * 
     * @param docId Lucene internal number.
     * @return Document id provided at index time.
     * @throws IOException Lucene errors.
     */
    public String getId(final int docId) throws IOException
    {
        // new lucene API, not teted
        Document doc = reader().storedFields().document(docId, FIELDS_ID);
        if (doc == null)
            return null;
        return doc.get(ALIX_ID);
    }

    /**
     * Is an Alix index already cached for this key?
     * 
     * @param key Key for cache.
     * @return True if an index is cached for this key.
     */
    public static boolean hasInstance(String key)
    {
        return pool.containsKey(key);
    }

    /**
     * Get infos for a field.
     * 
     * @param field A field.
     * @return Info for the field or null.
     * @throws IOException Lucene errors.
     */
    public FieldInfo info(Enum<?> field) throws IOException
    {
        reader(); // ensure reader or decache
        return fieldInfos.fieldInfo(field.name());
    }

    /**
     * Get infos for a field.
     * 
     * @param fieldName Name of a field.
     * @return Info for the field or null.
     * @throws IOException Lucene errors.
     */
    public FieldInfo info(String fieldName) throws IOException
    {
        reader(); // ensure reader or decache
        return fieldInfos.fieldInfo(fieldName);
    }
    
    /**
     * Retrieve by key an alix instance from the pool. 
     * 
     * @param key Name for cache.
     * @return An Alix instance.
     */
    public static Alix instance(String key)
    {
        return pool.get(key);
    }

    /**
     * Get a a lucene directory index by file path, from cache, or created.
     * 
     * @param key Name for cache.
     * @param path File directory of lucene index.
     * @param analyzer A lucene Analyzer.
     * @param dirType Lucene directory type.
     * @return An Alix instance.
     * @throws IOException Lucene errors.
     */
    public static Alix instance(final String key, final Path path, final Analyzer analyzer, FSDirectoryType dirType)
            throws IOException
    {
        Alix alix = pool.get(key);
        if (alix == null) {
            alix = new Alix(key, path, analyzer, dirType);
            pool.put(key, alix);
        }
        return alix;
    }

    /**
     * @see IndexReader#maxDoc()
     * 
     * @return Max number fo a docId in this index.
     * @throws IOException Lucene errors.
     */
    public int maxDoc() throws IOException
    {
        if (reader == null)
            reader();
        return maxDoc;
    }

    /**
     * 
     * @return Name of this Alix index.
     */
    public String name()
    {
        return name;
    }

    /**
     * 
     * @param fieldName Name of a text field.
     * @param q User query String.
     * @return A lucene Query.
     * @throws IOException Lucene errors.
     */
    public Query query(final String fieldName, final String q) throws IOException
    {
        return query(fieldName, q, this.analyzer);
    }

    static public Query query(final String field, final String q, final Analyzer analyzer) throws IOException
    {
        return query(field, q, analyzer, Occur.SHOULD);
    }

    /**
     * 
     * @param fieldName Name of a text field.
     * @param q User query String.
     * @param analyzer A Lucene analyzer.
     * @param occur Boolean operator between terms.
     * @return A lucene Query.
     * @throws IOException Lucene errors.
     */
    static public Query query(final String fieldName, final String q, final Analyzer analyzer, final Occur occur)
            throws IOException
    {
        if (q == null || "".equals(q.trim())) {
            return null;
        }
        // float[] boosts = { 2.0f, 1.5f, 1.0f, 0.7f, 0.5f };
        // int boostLength = boosts.length;
        // float boostDefault = boosts[boostLength - 1];
        TokenStream ts = analyzer.tokenStream(SEARCH, q);
        CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
        // FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

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
                if (token.length() == 0)
                    continue;
                if (bq == null && qTerm != null) { // second term, create boolean
                    bq = new BooleanQuery.Builder();
                    bq.add(qTerm, op);
                }
                String word;
                if (token.charAt(0) == '-') {
                    op = Occur.MUST_NOT;
                    word = token.subSequence(1, token.length()).toString();
                    neg++;
                } else if (token.charAt(0) == '+') {
                    op = Occur.MUST;
                    word = token.subSequence(1, token.length()).toString();
                    aff++;
                } else {
                    op = occur;
                    word = token.toString();
                    aff++;
                }

                // word.replace('_', ' ');
                int len = word.length();
                while (--len >= 0 && word.charAt(len) != '*')
                    ;
                if (len > 0)
                    qTerm = new WildcardQuery(new Term(fieldName, word));
                else
                    qTerm = new TermQuery(new Term(fieldName, word));

                if (bq != null) { // more than one term
                    bq.add(qTerm, op);
                }

            }
            ts.end();
        } finally {
            ts.close();
        }
        if (neg > 0 && aff == 0 && bq != null)
            bq.add(new MatchAllDocsQuery(), Occur.MUST);
        if (bq != null)
            return bq.build();

        if (neg > 0 && aff == 0) {
            bq = new BooleanQuery.Builder();
            bq.add(new MatchAllDocsQuery(), Occur.MUST);
            bq.add(qTerm, occur);
            return bq.build();
        }
        return qTerm;
    }

    /**
     * See {@link #reader(boolean)}
     * 
     * @return A Lucene reader.
     * @throws IOException Lucene errors.
     */
    public DirectoryReader reader() throws IOException
    {
        return reader(false);
    }

    /**
     * Get a reader for this lucene index, cached or new. Allow to force renew if
     * force is true.
     * 
     * @param force Renew cache if true.
     * @return A Lucene reader.
     * @throws IOException Lucene errors.
     */
    public DirectoryReader reader(final boolean force) throws IOException
    {
        if (!force && reader != null)
            return reader;
        cache.clear(); // clean cache on renew the reader
        reader = DirectoryReader.open(dir);
        fieldInfos = FieldInfos.getMergedFieldInfos(reader);
        maxDoc = reader.maxDoc();
        return reader;
    }

    /**
     * A real time reader only used for some updates.
     * 
     * @param writer A Lucene writer.
     * @return A reader for writing.
     * @throws IOException Lucene errors.
     */
    public IndexReader reader(IndexWriter writer) throws IOException
    {
        return DirectoryReader.open(writer, true, true);
    }

    /**
     * Get a Scale object, useful to build graphs and chronology with an int field.
     * 
     * @param fieldInt  A NumericDocValuesField used as a sorted value.
     * @param fieldText A Texfield to count occurences, used as a size for docs.
     * @return A Scale object.
     * @throws IOException Lucene errors.
     */
    public Scale scale(final String fieldInt, final String fieldText) throws IOException
    {
        String key = "AlixScale" + fieldInt + fieldText;
        Scale scale = (Scale) cache(key);
        if (scale != null)
            return scale;
        scale = new Scale(this, null, fieldInt, fieldText);
        cache(key, scale);
        return scale;
    }

    /**
     * See {@link #searcher(boolean)}
     * 
     * @return Cached Lucene searcher.
     * @throws IOException Lucene errors.
     */
    public IndexSearcher searcher() throws IOException
    {
        return searcher(false);
    }

    /**
     * Get the searcher for this lucene index, allow to force renew if force is
     * true.
     * 
     * @param force Renew cache if true.
     * @return A lucene searcher.
     * @throws IOException Lucene errors.
     */
    public IndexSearcher searcher(final boolean force) throws IOException
    {
        if (!force && searcher != null)
            return searcher;
        reader(force);
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(similarity);
        return searcher;
    }

    /**
     * Analyze a search according to the default analyzer of this base, is
     * especially needed for multi-words ex: "en effet" return search as an array of
     * string, supposing that caller knows the field he wants to search.
     * 
     * @param q A search query.
     * @param fieldName Name of text field.
     * @return Analyzed terms to search in index.
     * @throws IOException Lucene errors.
     */
    public String[] tokenize(final String q, final String fieldName) throws IOException
    {
        return tokenize(q, this.analyzer, fieldName);
    }

    /**
     * Analyze a search according to the current analyzer of this base ; return
     * search
     * 
     * @param q A search query.
     * @param analyzer An analyzer.
     * @param fieldName Name of text field.
     * @return Analyzed terms to search in index.
     * @throws IOException Lucene errors.
     */
    public static String[] tokenize(final String q, final Analyzer analyzer, String fieldName) throws IOException
    {
        // create an arrayList on each search and let gc works
        ArrayList<String> forms = new ArrayList<String>();
        // what should mean null here ?
        if (q == null || "".equals(q.trim()))
            return null;
        // if
        if (fieldName == null)
            fieldName = SEARCH;
        TokenStream ts = analyzer.tokenStream(fieldName, q); // keep punctuation to group search
        CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
        // not generic for other analyzers but may become interesting for a search
        // parser
        // CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
        // FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);
        ts.reset();
        try {
            while (ts.incrementToken()) {
                // empty token ? bad analyzer
                if (token.length() == 0)
                    continue;
                // a negation term
                if (token.charAt(0) == '-')
                    continue;
                String word;
                if (token.charAt(0) == '+') {
                    word = token.subSequence(1, token.length()).toString();
                } else {
                    word = token.toString();
                }
                /*
                 * final int tag = flags.getFlags(); if (Tag.isPun(tag)) { // start a new line
                 * if (token.equals(";") || tag == Tag.PUNsent) { search.add(null); } continue;
                 * }
                 */
                /*
                 * if (",".equals(word) || ";".equals(word)) { continue; }
                 */
                forms.add(word);
            }
            ts.end();
        } finally {
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
        } catch (Exception e) {
        }
        for (FieldInfo info : fieldInfos) {
            sb.append(info.name + " PointDataDimensionCount=" + info.getPointDimensionCount() + " DocValuesType="
                    + info.getDocValuesType() + " IndexOptions=" + info.getIndexOptions() + "\n");
        }
        sb.append(fieldInfos.toString());
        return sb.toString();
    }

    /**
     * See {@link #writer(Similarity)}
     * 
     * @return A lucene writer
     * @throws IOException Lucene errors.
     */
    public IndexWriter writer() throws IOException
    {
        return writer(null);
    }

    /**
     * Get a lucene writer with the best options in Alix context.
     * 
     * @param similarity Optional
     * @return A lucene writer
     * @throws IOException Lucene errors.
     */
    public IndexWriter writer(final Similarity similarity) throws IOException
    {
        if (writer != null && writer.isOpen())
            return writer;
        IndexWriterConfig conf = new IndexWriterConfig(analyzer);
        // Use false for batch indexing with very large ram buffer settings.
        conf.setUseCompoundFile(false);
        // may needed, increase the max heap size to the JVM (eg add -Xmx512m or
        // -Xmx1g):
        conf.setRAMBufferSizeMB(1024.0);
        conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
        //
        if (similarity != null)
            conf.setSimilarity(similarity);
        else
            conf.setSimilarity(this.similarity);
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
     * Give a new writer to create a lucene index, could be closed.
     * @param path
     * @param analyzer
     * @return
     * @throws IOException
     */
    public static IndexWriter writer(Path path, Analyzer analyzer) throws IOException
    {
        IndexWriterConfig conf = new IndexWriterConfig(analyzer);
        // Use false for batch indexing with very large ram buffer settings.
        conf.setUseCompoundFile(false);
        // may needed, increase the max heap size to the JVM (eg add -Xmx512m or
        // -Xmx1g):
        conf.setRAMBufferSizeMB(1024.0);
        conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
        // default similarity
        Similarity similarity = new BM25Similarity();
        conf.setSimilarity(similarity);
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
        return new IndexWriter(FSDirectory.open(path), conf);
    }
}
