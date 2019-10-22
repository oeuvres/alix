<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="obvil" uri="/tags/obvil"%>
<%-- Import common to all pages 

--%>
<%@ page import="org.apache.lucene.analysis.Analyzer" %>
<%@ page import="org.apache.lucene.search.IndexSearcher" %>
<%@ page import="org.apache.lucene.search.Query" %>
<%@ page import="org.apache.lucene.search.TopDocs" %>
<%@ page import="org.apache.lucene.util.BitSet" %>
<%@ page import="alix.lucene.Alix" %>
<%@ page import="alix.lucene.analysis.FrAnalyzer" %>
<%@ page import="alix.lucene.analysis.MetaAnalyzer" %>
<%@ page import="obvil.web.Obvil" %>
<%-- Import specific to the page --%>
<%!
static Analyzer anameta = new MetaAnalyzer();
%>
<obvil:hello/>
<%
String obvilDir = (String)request.getAttribute(Obvil.OBVIL_DIR);
String base = (String)request.getAttribute(Obvil.BASE);
Alix alix = Alix.instance(obvilDir +"/"+ base, new FrAnalyzer());


/*

String lowbibl = q.toLowerCase();
Query query = Alix.qParse("bibl", lowbibl, anameta);
TopDocs results;
if (fromDoc > -1) {
  ScoreDoc from = new ScoreDoc(fromDoc, fromScore);
  results = searcher.searchAfter(from, query, hpp);
}
else {
  results = searcher.search(query, hpp);
}
hits = results.scoreDocs;


StringBuilder sb = new StringBuilder();
if (hits != null  && hits.length > 0) {
  String paging = "";
  if (fromDoc > 0) {
    paging = "&amp;fromdoc="+fromDoc+"&amp;fromscore="+fromScore;
  }
  Marker marker = null;
  // a query to hilite in records
  if (!"".equals(q)) {
    marker = new Marker(anameta, q);
  }
  out.println("<ul class=\"results\">");
  for (int i = 0, len = hits.length; i < len; i++) {
    int docId = hits[i].doc;
    out.append("<li>");
    Document doc = reader.document(docId, DOC_SHORT);
    
    String text = doc.get("bibl");
    if (marker != null) {
      out.append("<a href=\"refdoc.jsp?docid="+docId+"&amp;q="+q+paging+"\">");
      out.append(marker.mark(text));
      out.append("</a>");
    }
    else {
      out.append("<a href=\"simdoc.jsp?docid="+docId+"&amp;refdocid="+refDocId+paging+"\">");
      out.append(text);
      out.append("</a>");
    }
    out.append("</li>\n");
  }
  out.println("</ul>");
}
*/

%>
