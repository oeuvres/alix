<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="obvil" uri="/tags/obvil"%>
<%@ page import="java.io.IOException" %>
<%@ page import="java.util.Arrays" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.Properties" %>
<%@ page import="org.apache.lucene.analysis.Analyzer" %>
<%@ page import="org.apache.lucene.document.Document" %>
<%@ page import="org.apache.lucene.index.IndexReader" %>
<%@ page import="org.apache.lucene.index.Term" %>
<%@ page import="org.apache.lucene.search.BooleanClause.Occur" %>
<%@ page import="org.apache.lucene.search.BooleanQuery" %>
<%@ page import="org.apache.lucene.search.IndexSearcher" %>
<%@ page import="org.apache.lucene.search.Query" %>
<%@ page import="org.apache.lucene.search.ScoreDoc" %>
<%@ page import="org.apache.lucene.search.TermQuery" %>
<%@ page import="org.apache.lucene.search.TopDocs" %>
<%@ page import="org.apache.lucene.util.BitSet" %>
<%@ page import="alix.web.JspTools" %>
<%@ page import="alix.lucene.Alix" %>
<%@ page import="alix.lucene.analysis.FrAnalyzer" %>
<%@ page import="alix.lucene.search.Corpus" %>
<%@ page import="alix.lucene.search.CorpusQuery" %>
<%@ page import="obvil.web.Obvil" %>
<%!
/** Field name containing canonized text */
public static String TEXT = "text";
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

    
%>
<%
long time = System.nanoTime();
JspTools tools = new JspTools(pageContext);
String obvilDir = (String)request.getAttribute(Obvil.OBVIL_DIR);
String base = (String)request.getAttribute(Obvil.BASE);
Properties props = (Properties)request.getAttribute(Obvil.PROPS);

Alix alix = Alix.instance(obvilDir +"/"+ base, new FrAnalyzer());
IndexSearcher searcher = alix.searcher();
IndexReader reader = alix.reader();
// base properties
String baseTitle = props.getProperty("title", null);
if (baseTitle == null) {
  baseTitle = props.getProperty("name", null);
  if (baseTitle == null) baseTitle =  base;
  props.setProperty("title", baseTitle);
}
Corpus corpus = (Corpus)session.getAttribute(CORPUS_+base);

%>