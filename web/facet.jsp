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
      <input type="hidden" id="q" name="q" value="<%=q%>" autocomplete="off"/>
    </form>
    <div class="facets">
    <%
Facet facet = alix.facet(facetField, TEXT);
TopTerms facetEnum = facet.topTerms(filter, terms, null);
while (facetEnum.hasNext()) {
  facetEnum.next();
  if (terms.size() > 0) {
    long occs = facetEnum.occs();
    if (occs < 1) break;
    out.println("<div>"+facetEnum.term()+" ("+occs+")</div>");
  }
  else {
    int docs = facetEnum.docs();
    if (docs < 1) break;
    out.println("<div>"+facetEnum.term()+" ("+docs+")</div>");
  }
}
    %>
    </div>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
    <script src="vendors/Sortable.js">//</script>
  </body>
</html>
