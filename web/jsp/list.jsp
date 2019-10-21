<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="common.jsp" %>
<%!
static Analyzer anameta = new MetaAnalyzer();
%>
<%
String pageType = (String)request.getAttribute("pageType");
IndexSearcher searcher = alix.searcher();
IndexReader reader = alix.reader();
Doc refDoc = null;
int refDocId = getParameter(request, "refdocid", -1);
int fromDoc = getParameter(request, "fromdoc", -1);
int fromScore = getParameter(request, "rfromscore", 0);
final int hpp = 1000;
ScoreDoc[] hits = null;
Query query = null;
String[] terms = null;


if (refDocId > 0) {
  refDoc = new Doc(alix, refDocId);
  Top<String> topTerms = refDoc.theme(TEXT);
  query = Doc.moreLikeThis(TEXT, topTerms, 50);
}
else if (!"".equals(q)) {
  String lowbibl = q.toLowerCase();
  query = Alix.qParse("bibl", lowbibl, anameta);
  terms = lowbibl.split("[ ,;]+");
}

if (query != null) {
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
<%
if ("bibl".equals(pageType)) {
  out.println("<p>Chercher un document par ses métadonnées.</p>");

}
else if (refDoc != null) {
  out.println("<h1>Document similaires</h1>");
  out.println("<b>Documents similaires à :</b>");
  out.println(refDoc.fields().get("bibl"));
}
else {
  // out.println("<h1>Document similaires</h1>");
  out.println("<p>À gauche, choisissez un document pour comparer</p>");
}
%>
    </header>
    <form>
      
      <%
if (refDoc != null) {
  out.println("<input type=\"hidden\" name=\"refdocid\" value=\"" +refDocId+"\"/>");
}
else {
  out.println("<input size=\"50\" type=\"text\" id=\"q\" onfocus=\"var len = this.value.length * 2; this.setSelectionRange(len, len); \" autofocus spellcheck=\"false\"  name=\"q\" value=\"" +q+"\"/>");
  out.println("<br/>" + query);
}

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
  Marker marker = null;
  // a query to hilite in records
  if (!"".equals(q)) {
    marker = new Marker(anameta, q);
  }
  out.println("<ul class=\"results\">");
  for (int i = 0, len = hits.length; i < len; i++) {
    int docId = hits[i].doc;
    out.append("<li>888");
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

out.println("  \"time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\"");

%>
    </main>
    <script src="../static/js/list.js">//</script>
  </body>
</html>