<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="org.apache.lucene.search.uhighlight.UnifiedHighlighter" %>
<%@ page import="org.apache.lucene.search.uhighlight.DefaultPassageFormatter" %>
<%@ page import="alix.lucene.search.HiliteFormatter" %>
<%
  // parameters
final String q = tools.getString("q", null);
final String sort = request.getParameter("sort");
final int hpp = tools.getInt("hpp", 100);
int start = tools.getInt("start", 1);
if (start < 1) start = 1;
// global variables
final String fieldName = TEXT;
time = System.nanoTime();
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, sort);
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche, <%=props.get("title")%> [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
  </head>
  <body class="results">
    <form id="qform">
      <input id="q" name="q" value="<%=Jsp.escapeHtml(q)%>" autocomplete="off" size="60" autofocus="autofocus" onclick="this.select();"/>
      <label>
       Tri
        <select name="sort" onchange="this.form.submit()">
          <option>Pertinence</option>
          <%= sortOptions(sort) %>
        </select>
      </label>
    </form>
    <main>
    <%
if (topDocs != null) {


  time = System.nanoTime();

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

  for (int i = 0; i < len; i++) {
    int docId = docIds[i];
    Document document = searcher.doc(docId);
    out.println("<article class=\"hit\">");
    // hits[i].doc
    out.println("  <div class=\"bibl\">");
    out.println("<small>"+(start + i)+".</small> ");
    // test if null ?
    out.println("<a href=\"doc.jsp?start="+(i + start)+"&q="+q+"&sort="+sort+"\">");
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
    %>
    </main>
  </body>
  <script src="../static/js/snip.js">//</script>
</html>
