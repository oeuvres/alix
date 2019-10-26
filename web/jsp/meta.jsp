<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="alix.lucene.analysis.MetaAnalyzer" %>
<%@ page import="alix.lucene.search.Marker" %>
<%@ include file="prelude2.jsp" %>
<%!
final static HashSet<String> DOC_SHORT = new HashSet<String>(Arrays.asList(new String[] {Alix.ID, Alix.BOOKID, "bibl"}));
final static Analyzer ANAMET = new MetaAnalyzer();
%>
<%
String field = "bibl";

String q = tools.getString("q", "");
float fromScore = tools.getFloat("fromscore", 0.0f);
int fromDoc = tools.getInt("fromdoc", -1);
int hpp = tools.getInt("hpp", 30);

String lowbibl = q.toLowerCase();
Query query = Alix.qParse(field, lowbibl, ANAMET, Occur.MUST);

TopDocs results = null;
if (query == null);
else if (fromDoc > -1) {
  ScoreDoc from = new ScoreDoc(fromDoc, fromScore);
  results = searcher.searchAfter(from, query, hpp);
}
else {
  results = searcher.search(query, hpp);
}



if(results == null); // no query
else if(results.totalHits.value == 0); // no results
else {
  long totalHits = results.totalHits.value;
  ScoreDoc[] hits = results.scoreDocs;
  String paging = "";
  if (fromDoc > 0) {
    paging = "&amp;fromdoc="+fromDoc+"&amp;fromscore="+fromScore;
  }
  Marker marker = null;
  // a query to hilite in records
  if (!"".equals(q)) {
    marker = new Marker(ANAMET, q);
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
      out.append("<a class=\"bibl\" href=\"compdoc.jsp?docid="+docId+"&amp;q="+q+paging+"\">");
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
    out.append("<a  class=\"more\" href=\"?q="+q+"&amp;fromscore="+score+"&amp;fromdoc="+docId+"\">⮟</a>\n");
  }
}

out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->");

%>
