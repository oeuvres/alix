<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="org.apache.lucene.search.uhighlight.UnifiedHighlighter" %>
<%@ page import="org.apache.lucene.search.uhighlight.DefaultPassageFormatter" %>
<%@ page import="alix.lucene.search.HiliteFormatter" %>
<%
// parameters
final String q = tools.getString("q", null);
DocSort sorter = (DocSort)tools.getEnum("sort", DocSort.score, "docSorter");
final int hpp = tools.getInt("hpp", 100);
int start = tools.getInt("start", 1);
if (start < 1) start = 1;
// global variables
final String fieldName = TEXT;
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, sorter);

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche, <%=props.get("title")%> [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <script src="../static/js/common.js">//</script>
  </head>
  <body class="results">
    <form id="qform">
      <input id="q" name="q" type="hidden" value="<%=Jsp.escape(q)%>" autocomplete="off" size="60" autofocus="autofocus" onclick="this.select();"/>
      <label>
       Tri
        <select name="sort" onchange="this.form.submit()">
          <option>Pertinence</option>
          <%= options(sorter) %>
        </select>
      </label>
    </form>
    <main>
    <%
if (topDocs == null) {
  // what shal we do ?
}
else if (q!=null) { // a query, something to hilite

  UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, alix.analyzer());
  uHiliter.setMaxLength(500000); // biggest text size to process
  uHiliter.setFormatter(new  HiliteFormatter());
  Query query = getQuery(alix, q, corpus); // to get the terms to Hilite
  ScoreDoc[] scoreDocs = topDocs.scoreDocs;
  if (start > scoreDocs.length) start = 1;
  int len = Math.min(hpp, 1 + scoreDocs.length - start);
  
  int docIds[] = new int[len];
  for (int i = 0; i < len; i++) {
    docIds[i] = scoreDocs[start - 1 + i].doc;
  }
  Map<String, String[]> res = uHiliter.highlightFields(new String[]{fieldName}, query, docIds, new int[]{5});
  String[] fragments = res.get(fieldName);

  final StringBuilder qhref = new StringBuilder();
  qhref.append("?q="+q);
  final int qhreflength = qhref.length();
  for (int i = 0; i < len; i++) {
    qhref.setLength(qhreflength); // reset query String
    int docId = docIds[i];
    Document document = searcher.doc(docId);
    out.println("<article class=\"hit\">");
    // hits[i].doc
    out.println("  <div class=\"bibl\">");
    out.println("<small>"+(start + i)+".</small> ");
    qhref.append( "&amp;start="+(i + start));
    if (sorter != DocSort.score) qhref.append( "&amp;sort="+sorter.name());
    out.println("<a href=\"doc" + qhref.toString()+"\">");
    out.println(document.get("bibl"));
    out.println("</a>");
    out.println("  </div>");
    if (fragments[i] != null) {
      out.print("<p class=\"frags\">");
      out.println(fragments[i]);
      out.println("</p>");
    }
    out.println("</article>");
  }
}
else { // list title of documents
  ScoreDoc[] scoreDocs = topDocs.scoreDocs;
  // start has here a public value, starting at 1
  if (start > scoreDocs.length) start = 1;
  int limit = Math.min(start + hpp, scoreDocs.length+1);
  
  final StringBuilder qhref = new StringBuilder();
  qhref.append("?");
  final int qhreflength = qhref.length();
  while(start < limit) {
    qhref.setLength(qhreflength); // reset query String
    final int docId = scoreDocs[start - 1].doc;
    Document document = searcher.doc(docId);
    out.println("<article class=\"hit\">");
    out.println("  <div class=\"bibl\">");
    out.println("<small>"+(start)+".</small> ");
    qhref.append( "&amp;start="+(start));
    if (sorter != DocSort.score) qhref.append( "&amp;sort="+sorter.name());
    out.println("<a href=\"doc" + qhref.toString()+"\">");
    out.println(document.get("bibl"));
    out.println("</a>");
    out.println("  </div>");
    /*
    if (fragments[i] != null) {
      out.print("<p class=\"frags\">");
      out.println(fragments[i]);
      out.println("</p>");
    }
    */
    start++;
    out.println("</article>");
  }
}
    %>
    </main>
  </body>
</html>
