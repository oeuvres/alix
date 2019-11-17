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
<%@ page import="org.apache.lucene.search.Collector" %>
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
<%@ page import="alix.web.Mime" %>
<%@ page import="alix.lucene.Alix" %>
<%@ page import="alix.lucene.DocType" %>
<%@ page import="alix.lucene.analysis.FrAnalyzer" %>
<%@ page import="alix.lucene.search.CollectorBits" %>
<%@ page import="alix.lucene.search.Corpus" %>
<%@ page import="alix.lucene.search.CorpusQuery" %>
<%@ page import="alix.lucene.search.SimilarityOccs" %>
<%@ page import="alix.lucene.search.SimilarityTheme" %>
<%@ page import="alix.lucene.search.TopTerms" %>
<%@ page import="alix.util.ML" %>
<%@ page import="alix.util.EnumOption" %>
<%@ page import="obvil.web.Obvil" %>
<%@ page import="obvil.web.WordClass" %>
<%@ page import="obvil.web.FacetSort" %>
<%@ page import="obvil.web.DocSort" %>

<%!

/** Field name containing canonized text */
public static String TEXT = "text";
/** Field Name with int date */
final static String YEAR = "year";
/** Key prefix for current corpus in session */
public static String CORPUS_ = "corpus_";
/** A filter for documents */
final static Query QUERY_CHAPTER = new TermQuery(new Term(Alix.TYPE, DocType.chapter.name()));

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
 * Sort options for facets or corpus
 */
public static String options(final EnumOption option) throws IOException
{
  StringBuilder sb = new StringBuilder();
  for (EnumOption opt : option.list()) {
    String value = opt.toString();
    sb.append("<option");
    if (option == opt) sb.append(" selected=\"selected\"");
    sb.append(" value=\"");
    sb.append(value);
    sb.append("\">");
    sb.append(opt.label());
    sb.append("</option>\n");
  }
  return sb.toString();
}


/**
 * Build a text query fron a String and an optional Corpus.
 * Will return null if there is no terms in the query,
 * even if there is a corpus.
 */
public static Query getQuery(Alix alix, String q, Corpus corpus) throws IOException
{
  String fieldName = TEXT;
  Query qWords = alix.qParse(fieldName, q);
  if (qWords == null) {
    return null;
    // return filter;
  }
  if (corpus != null) {
    Query filter = new CorpusQuery(corpus.name(), corpus.bits());
    return new BooleanQuery.Builder()
      .add(filter, Occur.FILTER)
      .add(qWords, Occur.MUST)
      .build();
  }
  return qWords;
}


/**
 * Get a bitSet of a query. Seems quite fast (2ms), no cache needed.
 */
public BitSet bits(Alix alix, Corpus corpus, String q) throws IOException
{
  Query query = getQuery(alix, q, corpus);
  if (query == null) {
    if (corpus == null) return null;
    return corpus.bits();
  }
  IndexSearcher searcher = alix.searcher();
  CollectorBits collector = new CollectorBits(searcher);
  searcher.search(query, collector);
  return collector.bits();
}

/**
 * Get a cached set of results.
 * Ensure to always give something
 */
public TopDocs getTopDocs(PageContext page, Alix alix, Corpus corpus, String q, DocSort sorter) throws IOException
{
  // build the key 
  Query query = getQuery(alix, q, corpus);
  if (query != null); // get a query, nothing to do
  else if (corpus != null) query = new CorpusQuery(corpus.name(), corpus.bits());
  else query = QUERY_CHAPTER;
  Sort sort = sorter.sort();
  String key = ""+page.getRequest().getAttribute(Obvil.BASE)+"?"+query;
  if (sort != null)  key+= " " + sort;
  /*
  Similarity oldSim = null;
  Similarity similarity = getSimilarity(sortSpec);
  if (similarity != null) {
    key += " <"+similarity+">";
  }
  */
  TopDocs topDocs;
  
  topDocs = (TopDocs)page.getSession().getAttribute(key);
  if (topDocs != null) return topDocs;

  IndexSearcher searcher = alix.searcher();
  int totalHitsThreshold = Integer.MAX_VALUE;
  final int numHits = alix.reader().maxDoc();
  // TODO allDocs collector
  TopDocsCollector<?> collector;
  SortField sf2 = new SortField(Alix.ID, SortField.Type.STRING);
  Sort sort2 = new Sort(sf2);
  if (sort != null) {
    collector = TopFieldCollector.create(sort, numHits, totalHitsThreshold);
  }
  else {
    collector = TopScoreDocCollector.create(numHits, totalHitsThreshold);
  }
  /*
  if (similarity != null) {
    oldSim = searcher.getSimilarity();
    searcher.setSimilarity(similarity);
    searcher.search(query, collector);
    // will it be fast enough to not affect other results ?
    searcher.setSimilarity(oldSim);
  }
  else {
  }
  */
  searcher.search(query, collector);
  topDocs = collector.topDocs();
  page.getSession().setAttribute(key, topDocs);
  return topDocs;
}

/**
 * Was used for testing the similarities.
 */
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

%>
<%
final long time = System.nanoTime();
final Jsp tools = new Jsp(request, response, pageContext);
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