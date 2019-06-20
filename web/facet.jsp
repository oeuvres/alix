<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Facettes</title>
    <link rel="stylesheet" type="text/css" href="static/alix.css"/>
  </head>
  <body class="facet">
  <%
  // choose a field
String facet = request.getParameter("facet");
if (facet == null || "".equals(facet.trim())) facet = "author";
String q = request.getParameter("q");
if (q == null) q = "";
else q = q.trim();
TermList terms = lucene.qTerms(q, TEXT);
if (terms.size() > 0) out.println("<h3>Auteur (occurrences)</h3>");
else out.println("<h3>Auteur (chapitres)</h3>");

%>
    <form id="qform">
      <input id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
      Entre
      <input id="start" name="start" size="4" value="<%=(start > -1)?start:""%>"/>
      et
      <input id="end" name="end" size="4" value="<%=(end > -1)?end:""%>"/>
      <button>▶</button>
    </form>
    <%
// needs the bits of th filter
QueryBits filter = null;
if (filterQuery != null) filter = new QueryBits(filterQuery);

FacetResult results = lucene.facet(facet, TEXT, filter, terms, null);
while (results.hasNext()) {
  results.next();
  long weight = results.weight();
  if (weight < 1) break;
  out.println("<div>"+results.term()+" ("+weight+")</div>");
}
    %>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
    <script src="static/js/facet.js">//</script>
  </body>
</html>