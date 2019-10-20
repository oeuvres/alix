<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="common.jsp" %>
<%!
%>
<% 
IndexSearcher searcher = alix.searcher();
IndexReader reader = alix.reader();
Doc refDoc = null;
int refDocId = getParameter(request, "refdocid", -1);
int fromDoc = getParameter(request, "fromdoc", -1);
int fromScore = getParameter(request, "rfromscore", 0);
final int hpp = 100;
ScoreDoc[] hits = null;
if (refDocId > 0) {
  refDoc = new Doc(alix, refDocId);
  Top<String> topTerms = refDoc.theme(TEXT);
  Query query = Doc.moreLikeThis(TEXT, topTerms, 50);
  TopDocs results;
  if (fromDoc > -1) {
    ScoreDoc from = new ScoreDoc(fromDoc, fromScore);
    results = searcher.searchAfter(from, query, hpp);
  }
  else {
    results = searcher.search(query, hpp);
  }
  hits = results.scoreDocs;
}
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Documents similaires [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
  </head>
  <body class="results">
    <header>
    <h1>Document similaires</h1>
<%
if (refDoc != null) {
  out.println("<b>Documents similaires à :</b>");
  out.println(refDoc.fields().get("bibl"));
}
else {
  out.println("<p>Choisissez un docunent à gauche pour chercher des documents similaires.</p>");

}
%>
    </header>
    <form>
      <input type="hidden" name="refdocid" value="<%=refDocId%>"/>
      <%
// go next
if (hits != null && hits.length == hpp) {
  out.println("<input type=\"hidden\" name=\"fromdoc\" value=\""+hits[hpp - 1].doc+"\"/>");
  out.println("<input type=\"hidden\" name=\"fromscore\" value=\""+hits[hpp - 1].score+"\"/>");
}
      %>
    </form>

    <main>

<%

if (hits != null  && hits.length > 0) {

  String paging = "";
  if (fromDoc > 0) {
    paging = "&amp;fromdoc="+fromDoc+"&amp;fromscore="+fromScore;
  }

  out.println("<ul class=\"results\">");
  for (int i = 0, len = hits.length; i < len; i++) {
    int docId = hits[i].doc;
    // if (docSrc == docId) continue;
    Document doc = reader.document(docId, DOC_SHORT);
    out.append("<li>");
    out.append("<a href=\"simdoc.jsp?id="+doc.get(Alix.ID)+"&amp;refdocid="+docId+paging+"\">");
    out.append(doc.get("bibl"));
    out.append("</a>");
    out.append("</li>\n");
  }
  out.println("</ul>");
}
%>
    </main>
  </body>
</html>