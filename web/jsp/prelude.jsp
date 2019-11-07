<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ taglib prefix="obvil" uri="/tags/obvil"%>
<%@ page import="java.io.IOException" %>
<%@ page import="java.util.Arrays" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.Properties" %>
<%@ page import="java.util.Set" %>
<%@ page import="org.apache.lucene.analysis.Analyzer" %>
<%@ page import="org.apache.lucene.document.Document" %>
<%@ page import="org.apache.lucene.index.IndexReader" %>
<%@ page import="org.apache.lucene.index.Term" %>
<%@ page import="org.apache.lucene.search.BooleanClause.Occur" %>
<%@ page import="org.apache.lucene.search.BooleanQuery" %>
<%@ page import="org.apache.lucene.search.IndexSearcher" %>
<%@ page import="org.apache.lucene.search.Query" %>
<%@ page import="org.apache.lucene.search.ScoreDoc" %>
<%@ page import="org.apache.lucene.search.similarities.*" %>
<%@ page import="org.apache.lucene.search.Sort" %>
<%@ page import="org.apache.lucene.search.SortField" %>
<%@ page import="org.apache.lucene.search.TermQuery" %>
<%@ page import="org.apache.lucene.search.TopDocs" %>
<%@ page import="org.apache.lucene.search.TopDocsCollector" %>
<%@ page import="org.apache.lucene.search.TopFieldCollector" %>
<%@ page import="org.apache.lucene.search.TopScoreDocCollector" %>
<%@ page import="org.apache.lucene.util.BitSet" %>
<%@ page import="alix.web.Jsp" %>
<%@ page import="alix.lucene.Alix" %>
<%@ page import="alix.lucene.analysis.FrAnalyzer" %>
<%@ page import="alix.lucene.search.Corpus" %>
<%@ page import="alix.lucene.search.CorpusQuery" %>
<%@ page import="alix.lucene.search.SimilarityOccs" %>
<%@ page import="alix.lucene.search.SimilarityTheme" %>
<%@ page import="alix.lucene.search.TopTerms" %>
<%@ page import="alix.util.ML" %>
<%@ page import="obvil.web.Obvil" %>
<%!/** Field name containing canonized text */
public static String TEXT = "text";
/** Field Name with int date */
final static String YEAR = "year";
/** Key prefix for current corpus in session */
public static String CORPUS_ = "corpus_";

/**
 * Build a filtering query with a corpus
 */
public static Query corpusQuery(Corpus corpus, Query query) throws IOException
{
  if (corpus == null) return query;
  BitSet filter= corpus.bits();
  if (filter == null) return query;
  if (query == null) return new CorpusQuery(corpus.name(), filter);
  return new BooleanQuery.Builder()
    .add(new CorpusQuery(corpus.name(), filter), Occur.FILTER)
    .add(query, Occur.MUST)
  .build();
}

/**
 * Used by snip.jsp, and doc.jsp
 */
public static String sortOptions(String sortSpec) throws IOException
{
  StringBuilder sb = new StringBuilder();
  String[] value = {
    "year", "year-inv", "author", "author-inv", "occs", "theme",
    // "tf-idf", "bm25", "dfi_chi2", "dfi_std", "dfi_sat", 
    // "lmd", "lmd0.1", "lmd0.7", "dfr", "ib"
  };
  String[] label = {
    "Année (+ ancien)", "Année (+ récent)", "Auteur (A-Z)", "Auteur (Z-A)", "Occurrences", "Thème",
    // "tf-idf", "BM25", "DFI chi²", "DFI standard", "DFI saturé", 
    // "LMD", "LMD λ=0.1", "LMD λ=0.7", "DFR", "IB"
  };
  for (int i = 0, length = value.length; i < length; i++) {
    sb.append("<option");
    if (value[i].equals(sortSpec)) sb.append(" selected=\"selected\"");
    sb.append(" value=\"");
    sb.append(value[i]);
    sb.append("\">");
    sb.append(label[i]);
    sb.append("</option>");
  }
  return sb.toString();
}

public static String posOptions(String sortSpec) throws IOException
{
  StringBuilder sb = new StringBuilder();
  String[] value = {
    "nostop", "sub", "name", "verb", "adj", "adv", "all",
  };
  String[] label = {
    "Mots pleins", "Substantifs", "Noms propres", "Verbes", "Adjectifs", "Adverbes", "Tout",
  };
  for (int i = 0, length = value.length; i < length; i++) {
    sb.append("<option");
    if (value[i].equals(sortSpec)) sb.append(" selected=\"selected\"");
    sb.append(" value=\"");
    sb.append(value[i]);
    sb.append("\">");
    sb.append(label[i]);
    sb.append("</option>\n");
  }
  return sb.toString();
}


/**
 * Build a text query fron a String and an optional Corpus.
 */
public static Query getQuery(Alix alix, String q, Corpus corpus) throws IOException
{
  String fieldName = TEXT;
  Query qWords = alix.qParse(fieldName, q);
  if (qWords == null) return null;
  Query query;
  BitSet filter= null;
  if (corpus != null) filter = corpus.bits();
  if (filter != null) {
    query = new BooleanQuery.Builder()
      .add(new CorpusQuery(corpus.name(), filter), Occur.FILTER)
      .add(qWords, Occur.MUST)
      .build();
  }
  else {
    query = qWords;
  }
  return query;
}


/**
 * Get a sort specification by a name
 */
public static Sort getSort(final String sortSpec)
{
  if ("year".equals(sortSpec)) {
    return new Sort(new SortField(YEAR, SortField.Type.INT));
  }
  else if ("year-inv".equals(sortSpec)) {
    return new Sort(new SortField(YEAR, SortField.Type.INT, true));
  }
  else if ("author".equals(sortSpec)) {
    return new Sort(new SortField(Alix.ID, SortField.Type.STRING));
  }
  else if ("author-inv".equals(sortSpec)) {
    return new Sort(new SortField(Alix.ID, SortField.Type.STRING, true));
  }
  else if ("length".equals(sortSpec)) {
    return new Sort(new SortField(TEXT, SortField.Type.INT));
  }
  return null;
}

public static Similarity getSimilarity(final String sortSpec)
{
  Similarity similarity = null;
  if ("dfi_chi2".equals(sortSpec)) similarity = new DFISimilarity(new IndependenceChiSquared());
  else if ("dfi_std".equals(sortSpec)) similarity = new DFISimilarity(new IndependenceStandardized());
  else if ("dfi_sat".equals(sortSpec)) similarity = new DFISimilarity(new IndependenceSaturated());
  else if ("tfidf".equals(sortSpec)) similarity = new ClassicSimilarity();
  else if ("lmd".equals(sortSpec)) similarity = new LMDirichletSimilarity();
  else if ("lmd0.1".equals(sortSpec)) similarity = new LMJelinekMercerSimilarity(0.1f);
  else if ("lmd0.7".equals(sortSpec)) similarity = new LMJelinekMercerSimilarity(0.7f);
  else if ("dfr".equals(sortSpec)) similarity = new DFRSimilarity(new BasicModelG(), new AfterEffectB(), new NormalizationH1());
  else if ("ib".equals(sortSpec)) similarity = new IBSimilarity(new DistributionLL(), new LambdaDF(), new NormalizationH3());
  else if ("theme".equals(sortSpec)) similarity = new SimilarityTheme();
  else if ("occs".equals(sortSpec)) similarity = new SimilarityOccs();
  return similarity;
}

/**
 * Get a cached set of results.
 */
public TopDocs getTopDocs(PageContext page, Alix alix, Corpus corpus, String q, String sortSpec) throws IOException
{
  Query query = getQuery(alix, q, corpus);
  if (query == null) return null;
  IndexSearcher searcher = alix.searcher();
  int totalHitsThreshold = Integer.MAX_VALUE;
  Sort sort = getSort(sortSpec);
  String key = ""+page.getRequest().getAttribute(Obvil.BASE)+"?"+query;
  if (sort != null)  key+= " " + sort;
  Similarity oldSim = null;
  Similarity similarity = getSimilarity(sortSpec);
  if (similarity != null) {
    key += " <"+similarity+">";
  }
  TopDocs topDocs = (TopDocs)page.getSession().getAttribute(key);
  if (topDocs != null) return topDocs;
  
  final int numHits = 12000;
  TopDocsCollector<?> collector;
  if (sort != null) {
    collector = TopFieldCollector.create(sort, numHits, totalHitsThreshold);
  }
  else {
    collector = TopScoreDocCollector.create(numHits, totalHitsThreshold);
  }
  if (similarity != null) {
    oldSim = searcher.getSimilarity();
    searcher.setSimilarity(similarity);
    searcher.search(query, collector);
    // will it be fast enough to not affect other results ?
    searcher.setSimilarity(oldSim);
  }
  else {
    searcher.search(query, collector);
  }
  topDocs = collector.topDocs();
  page.getSession().setAttribute(key, topDocs);
  return topDocs;
}%>
<%
final long time = System.nanoTime();
final Jsp tools = new Jsp(pageContext);
final String obvilDir = (String)request.getAttribute(Obvil.OBVIL_DIR);
final String base = (String)request.getAttribute(Obvil.BASE);
final Properties props = (Properties)request.getAttribute(Obvil.PROPS);
{
  final String baseName = props.getProperty("name", null);
  if (baseName == null) props.setProperty("name", base);
}
final Alix alix = Alix.instance(obvilDir +"/"+ base, new FrAnalyzer());
final IndexSearcher searcher = alix.searcher();
final IndexReader reader = alix.reader();
final String corpusKey = CORPUS_ + base;
%>