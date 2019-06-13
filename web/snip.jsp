<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Alix, test</title>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="results">
    <div id="results">
      <%
long time = System.nanoTime();
String q = request.getParameter("q");
if (q == null || q.trim() == "") q = "théâtre acteur ; lettres ; littérature ; poésie poème ; roman";
      %>
      <form id="qform">
        <input id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"/>
      </form>
    
    <%
String fieldName = TEXT;
Query query = Alix.qParse(q, fieldName);
IndexReader reader = lucene.reader();


IndexSearcher searcher = lucene.searcher();



TopDocs topDocs = searcher.search(query, 100);
ScoreDoc[] hits = topDocs.scoreDocs;



UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, Alix.qAnalyzer);
uHiliter.setFormatter(new  HiliteFormatter());
String[] fragments = uHiliter.highlight(fieldName, query, topDocs, 3);
// TODO, get matching occurences coumt

String value;
for (int i = 0; i < hits.length; i++) {
  int docId = hits[i].doc;
  Document doc = searcher.doc(docId);
  out.println("<article class=\"hit\">");
  // hits[i].doc
  out.println("  <div class=\"bibl\">");
  // test if null ?
  value = doc.get("byline");
  if (value != null) {
    out.print("    <span class=\"byline\">");
    out.print(value);
    out.println("</span>");
  }
  value = doc.get("title");
  if (value != null) {
    out.print("    <span class=\"title\">");
    out.print(value);
    out.println("</span>");
  }

  value = doc.get(YEAR);
  if (value != null) {
    out.print("    <span class=\"year\">(");
    out.print(value);
    out.println(")</span>");
  }
  
  value = doc.get("pages");
  if (value != null) {
    out.print("    <span class=\"pages\">pp. ");
    out.print(value);
    out.println("</span>");
  }
  
  IndexableField[] parents = doc.getFields("parent");
  int length = parents.length;
  if (length > 0) {
    out.print("<span class=\"chapter\"> : « ");
    for (int j = 0; j < length; j++) {
      if (j > 0) out.print(" — ");
      out.print(parents[j].stringValue().trim());
    }
    out.println(" »</span>");
  }
  out.println("  </div>");
  out.print("<p class=\"frags\">");
  out.println(fragments[i]);
  out.println("</p>");
  
  out.println("</article>");
}


    %>
    </div>
  </body>
</html>
<% out.println("<!--  \"time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>