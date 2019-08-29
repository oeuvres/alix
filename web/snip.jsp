<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche [Alix]</title>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="results">
    <div id="results">
      <%
String sort = request.getParameter("sort");
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
    
    <%
String fieldName = TEXT;
if (!"".equals(q)) {
  // renew searcher for this experiment on similarity
  IndexSearcher searcher = alix.searcher(true);
  Query query = getQuery(corpus, q);
  TopDocs topDocs;
  Sort sorter = getSort(sort);
  Similarity similarity = getSimilarity("sort");
  Similarity oldSim = null;
  if (similarity != null) {
    searcher.setSimilarity(similarity);
    oldSim = searcher.getDefaultSimilarity();
  }
  if (sorter == null ) {
    topDocs = searcher.search(query, 100);
  }
  else {
    topDocs = searcher.search(query, 100, sorter);
  }
  if (similarity != null) {
    searcher.setSimilarity(oldSim);
  }


  ScoreDoc[] hits = topDocs.scoreDocs;



  UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, Alix.qAnalyzer);
  uHiliter.setMaxLength(500000); // biggest text size to process
  uHiliter.setFormatter(new  HiliteFormatter());
  String[] fragments = uHiliter.highlight(fieldName, query, topDocs, 5);

  for (int i = 0; i < hits.length; i++) {
    int docId = hits[i].doc;
    Document document = searcher.doc(docId);
    out.println("<article class=\"hit\">");
    // hits[i].doc
    out.println("  <div class=\"bibl\">");
    // test if null ?
    out.println("<a href=\"doc.jsp?n="+(i + 1)+"&q="+q+"\">");
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
    </div>
    <% out.println("time : " + (System.nanoTime() - time) / 1000000.0 + " ms "); %>  </body>
    <script src="static/js/snip.js">//</script>
</html>
