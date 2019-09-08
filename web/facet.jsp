<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Facettes</title>
    <link rel="stylesheet" type="text/css" href="static/alix.css"/>
    <base target="page"/>
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
// a query
if (terms.size() > 0) { 
  //get results for a query sorted by this field, to get the index to navigate in it
  TopDocs topDocs = getTopDocs(session, alix.searcher(), corpus, q, facetField);
  int[] nos = facet.nos(topDocs);
  TopTerms facetEnum = facet.topTerms(filter, terms, null);
  facetEnum.setNos(nos);
  while (facetEnum.hasNext()) {
    facetEnum.next();
    long occs = facetEnum.occs();
    System.out.println(occs);
    if (occs < 1) break;
    int n = facetEnum.n();
    int hits = facetEnum.hits();
    out.print("<div>");
    out.print("<a href=\"snip.jsp?sort="+facetField+"&q="+q+"&start="+(n+1)+"&hpp="+hits+"\">");
    out.print(facetEnum.term());
    out.print("</a>");
    out.print(" ("+occs+")");
    out.println("</div>");
  }
}
else {
  TopTerms facetEnum = facet.topTerms(filter, terms, null);
  while (facetEnum.hasNext()) {
    facetEnum.next();
    int docs = facetEnum.docs();
    if (docs < 1) break;
    out.println("<div>"+facetEnum.term()+" ("+docs+")</div>");
  }
}

    %>
    </div>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
  </body>
</html>
