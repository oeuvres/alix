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
String q = getParameter(request, "q", "");
TermList terms = alix.qTerms(q, TEXT);
// choose a field
String facetField = getParameter(request, "facet", "author");
String facetName = facetField;
if (facetField.equals("author")) facetName = "Auteur";
else if (facetField.equals("title")) facetName = "Titre";
if (terms.size() > 0) out.println("<h3>"+facetName+" (occurrences)</h3>");
else out.println("<h3>"+facetName+" (chapitres)</h3>");

%>
    <form id="qform">
      <input type="hidden" id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
    </form>
    <div class="facets">
    <%
// needs the bits of th filter
Corpus corpus = (Corpus)session.getAttribute(CORPUS);
BitSet bits = null;
if (corpus != null) bits = corpus.bits();

Facet facet = alix.facet(facetField, TEXT);
TopTerms facetEnum = facet.topTerms(bits, terms, null);
while (facetEnum.hasNext()) {
  facetEnum.next();
  if (terms.size() > 0) {
    long occs = facetEnum.occs();
    if (occs < 1) break;
    out.println("<div>"+facetEnum.term()+" ("+occs+")</div>");
  }
  else {
    out.println("<div>"+facetEnum.term()+" ("+facetEnum.docs()+")</div>");
  }
}
    %>
    </div>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
    <script src="vendors/Sortable.js">//</script>
  </body>
</html>
