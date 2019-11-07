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
        <input id="q" name="q" value="<%=Jsp.escape(q)%>" autocomplete="off" size="60" autofocus="autofocus" onclick="this.select();"/>
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
  // compile automaton for the seraches terms
  // get the index in results
  // load a doc 
 
}
    %>
    </main>
  </body>
  <script src="../static/js/snip.js">//</script>
</html>
