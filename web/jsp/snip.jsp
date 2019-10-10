<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
  </head>
  <body class="results">
      <%
String sort = request.getParameter("sort");
int hpp = getParameter(request, "hpp", 100);
int start = getParameter(request, "start", 1);
if (start < 1) start = 1;
      %>
      <form id="qform">
        <input id="q" name="q" value="<%=escapeHtml(q)%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
        <label>
         Tri
          <select name="sort" onchange="this.form.submit()">
            <option>Pertinence</option>
            <% sortOptions(out, sort); %>
          </select>
        </label>
      </form>
    <main>
    <%
String fieldName = TEXT;
if (!"".equals(q)) {

  time = System.nanoTime();
  IndexSearcher searcher = alix.searcher();
  TopDocs topDocs = getTopDocs(session, searcher, corpus, q, sort);

  time = System.nanoTime();

  UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, Alix.qAnalyzer);
  uHiliter.setMaxLength(500000); // biggest text size to process
  uHiliter.setFormatter(new  HiliteFormatter());
  Query query = getQuery(corpus, q); // to get the terms to Hilite
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
    /*
    out.println("<small>");
    out.println(document.get(Alix.FILENAME));
    out.println("</small>");
    */
    out.println("</article>");
  }
}
    %>
    </main>
  </body>
  <script src="../static/js/snip.js">//</script>
</html>
