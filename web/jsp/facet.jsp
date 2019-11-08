<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%@ page import="alix.lucene.search.Facet" %>
<%@ page import="alix.lucene.search.TermList" %>
<%@ page import="alix.lucene.search.TopTerms" %>
<%
//Params for the page
String q = tools.getString("q", null);
String ord = tools.getString("ord", "alpha", "facetSort");
String facetField = tools.getString("facet", "author"); 

//global variables
String facetName = facetField;
if (facetField.equals("author")) facetName = "Auteur";
else if (facetField.equals("title")) facetName = "Titre";

Corpus corpus = (Corpus)session.getAttribute(corpusKey);
BitSet filter = null;
if (corpus != null) filter = corpus.bits();
TermList terms = alix.qTerms(q, TEXT);

// is there a score (= query) ?
final boolean score =  (terms != null && terms.size() > 1);
if (!score && "score".equals(ord)) ord = "freq";

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Facettes</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/js/common.js">//</script>
    <base target="page"/>
  </head>
  <body class="facet">
    <form id="qform" target="_self">
      <input type="submit" style="position: absolute; left: -9999px; width: 1px; height: 1px;"  tabindex="-1" />
      <input type="hidden" id="q" name="q" value="<%=q%>" autocomplete="off"/>
      <select name="ord" onchange="this.form.submit()">
        <option/>
        <%= biblSortOptions(ord, score) %>
      </select>
    </form>
    <main>
    <%
if (score) out.println("<h4>occurrences (chapitres) "+facetName+"</h4>");
else out.println("<h4>(chapitres) "+facetName+"</h4>");
Facet facet = alix.facet(facetField, TEXT);
// a query
if (terms != null && terms.size() > 0) { 
  // Hack to use facet as a navigator in results, cache results in the field of the facet order
  TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, facetField);
  // get the position of the first document for each facet
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
    out.print("<a href=\"snip?sort="+facetField+"&amp;q="+q+"&start="+(n+1)+"&amp;hpp="+hits+"\">");
    out.print("<span><span class=\"occs\">"+occs+"</span> ("+hits+" <i>/"+docs+"</i>)</span>    ");
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
    // if a filter but no query, hits is set, test it
    if (filter != null && facetEnum.hits() < 1) continue;
    if (docs < 1) continue; // if in alpha order, do not stop here
    out.print("<div class=\"term\">");
    out.print("<span>(<i>"+docs+"</i>)</span>    ");
    out.print(facetEnum.term());
    out.println("</div>");
  }
}

    %>
    </main>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
  </body>
</html>
