<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="obvil" uri="/tags/obvil"%>
<%-- Import common to all pages --%>
<%@ page import="java.util.Arrays" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="org.apache.lucene.analysis.Analyzer" %>
<%@ page import="org.apache.lucene.document.Document" %>
<%@ page import="org.apache.lucene.search.IndexSearcher" %>
<%@ page import="org.apache.lucene.search.Query" %>
<%@ page import="org.apache.lucene.search.ScoreDoc" %>
<%@ page import="org.apache.lucene.search.TopDocs" %>
<%@ page import="org.apache.lucene.util.BitSet" %>
<%@ page import="alix.lucene.Alix" %>
<%@ page import="alix.lucene.analysis.FrAnalyzer" %>
<%@ page import="alix.lucene.analysis.MetaAnalyzer" %>
<%@ page import="alix.lucene.search.Marker" %>
<%@ page import="alix.web.JspTools" %>
<%@ page import="obvil.web.Obvil" %>
<%-- Import specific to the page --%>
<%!
final static HashSet<String> DOC_SHORT = new HashSet<String>(Arrays.asList(new String[] {Alix.ID, Alix.BOOKID, "bibl"}));
final static Analyzer anameta = new MetaAnalyzer();
%>
<%
long time = System.nanoTime();

JspTools tools = new JspTools(pageContext);
String obvilDir = (String)request.getAttribute(Obvil.OBVIL_DIR);
String base = (String)request.getAttribute(Obvil.BASE);
Alix alix = Alix.instance(obvilDir +"/"+ base, new FrAnalyzer());

IndexSearcher searcher = alix.searcher();
String field = "bibl";

ScoreDoc[] hits = null;

String q = tools.getString("q", "");
float fromScore = tools.getFloat("fromScore", 0.0f);
int fromDoc = tools.getInt("fromDoc", -1);
int hpp = tools.getInt("hpp", 10);

String lowbibl = q.toLowerCase();
Query query = Alix.qParse(field, lowbibl, anameta);
TopDocs results = null;
if (query == null) {
  
}
else if (fromDoc > -1) {
  ScoreDoc from = new ScoreDoc(fromDoc, fromScore);
  results = searcher.searchAfter(from, query, hpp);
}
else {
  results = searcher.search(query, hpp);
}



if(results == null); // no query
else if(results.totalHits.value == 0) {
  // no results
}
else {
  long totalHits = results.totalHits.value;
  hits = results.scoreDocs;
  String paging = "";
  if (fromDoc > 0) {
    paging = "&amp;fromdoc="+fromDoc+"&amp;fromscore="+fromScore;
  }
  Marker marker = null;
  // a query to hilite in records
  if (!"".equals(q)) {
    marker = new Marker(anameta, q);
  }
  int docId = 0;
  float score = 0;
  for (int i = 0, len = hits.length; i < len; i++) {
    docId = hits[i].doc;
    score = hits[i].score;
    // out.append("<li>");
    Document doc = searcher.doc(docId, DOC_SHORT);
    
    String text = doc.get("bibl");
    if (marker != null) {
      out.append("<a class=\"bibl\" href=\"refdoc.jsp?docid="+docId+"&amp;q="+q+paging+"\">");
      out.append(marker.mark(text));
      out.append("</a>");
    }
    else {
      out.append("<a class=\"bibl\" href=\"simdoc.jsp?docid="+docId+paging+"\">");
      out.append(text);
      out.append("</a>");
    }
    // out.append("</li>\n");
  }
  if (hits.length < totalHits) {
    out.append("<a  class=\"more\" href=\"?q="+q+"&amp;fromScore="+score+"&amp;fromDoc="+docId+"\">▼</a>\n");
  }
}

out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->");

%>
