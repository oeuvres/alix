<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8"%>
<%@include file="common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Facettes</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <base target="page"/>
  </head>
  <body class="facet">
  <%
String ord = getParameter(request, "ord", "score", session);
TermList terms = alix.qTerms(q, TEXT);
if (terms.size() < 1 && "score".equals(ord)) ord = "freq";
// choose a field
String facetField = getParameter(request, "facet", "author");
String facetName = facetField;
if (facetField.equals("author")) facetName = "Auteur";
else if (facetField.equals("title")) facetName = "Titre";

%>
    <form id="qform" target="_self">
      <input type="submit" style="position: absolute; left: -9999px; width: 1px; height: 1px;"  tabindex="-1" />
      <input type="hidden" id="q" name="q" value="<%=q%>" autocomplete="off"/>
      <select name="ord" onchange="this.form.submit()">
        <option/>
        <option value="alpha" <%=("alpha".equals(ord))?" selected=\"selected\"  ":""%>>Alphabétique</option>
        <option value="freq" <%=("freq".equals(ord))?" selected=\"selected\"  ":""%>>Fréquence</option>
        <% 
if (terms.size() > 0) {
  String value = "score";
  out.print("<option value=\""+value+"\"");
  if (value.equals(ord)) out.print(" selected=\"selected\"");
  out.println(">Pertinence</option>");
}
        %>
      </select>
    </form>
    <% 
if (terms.size() > 0) out.println("<h3>"+facetName+" (chapitres, occurrences)</h3>");
else out.println("<h3>"+facetName+" (chapitres)</h3>");
    %>
    <div class="facets">
    <%
Facet facet = alix.facet(facetField, TEXT);
// a query
if (terms.size() > 0) { 
  // get a 
  TopDocs topDocs = getTopDocs(session, alix.searcher(), corpus, q, facetField);
  int[] nos = facet.nos(topDocs);
  TopTerms facetEnum = facet.topTerms(filter, terms, null);
  facetEnum.setNos(nos);
  if ("alpha".equals(ord)) facetEnum.sort();
  else if ("score".equals(ord)) facetEnum.sort(facetEnum.getScores());
  else if ("freq".equals(ord)) facetEnum.sort(facetEnum.getOccs());
  else facetEnum.sort();
  while (facetEnum.hasNext()) {
    facetEnum.next();
    int hits = facetEnum.hits();
    int docs = facetEnum.docs();
    long occs = facetEnum.occs();
    if (occs < 1) continue; // in alpha order, try next
    int n = facetEnum.n();
    out.print("<div class=\"term\">");
    out.print("<a href=\"snip.jsp?sort="+facetField+"&amp;q="+q+"&start="+(n+1)+"&amp;hpp="+hits+"\">");
    out.print("<span><span class=\"occs\">"+occs+"</span> ("+hits+" <i>/"+docs+"</i>)</span>.    ");
    out.print(facetEnum.term());
    out.print("</a>");
    out.println("</div>");
  }
}
else {
  TopTerms facetEnum = facet.topTerms(filter, terms, null);
  if ("alpha".equals(ord)) facetEnum.sort();
  else if ("freq".equals(ord)) facetEnum.sort(facetEnum.getDocs());
  else facetEnum.sort();
  while (facetEnum.hasNext()) {
    facetEnum.next();
    int docs = facetEnum.docs();
    if (docs < 1) continue; // in alpha order, try next
    out.print("<div class=\"term\">");
    out.print("<span><i>"+docs+"</i></span>.    ");
    out.print(facetEnum.term());
    out.println("</div>");
  }
}

    %>
    </div>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
  </body>
</html>
