<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%@ page import="alix.lucene.search.Facet" %>
<%@ page import="alix.lucene.search.TermList" %>
<%@ page import="alix.lucene.search.TopTerms" %>
<%@ page import="alix.lucene.search.TopTerms" %>
<%@ page import="obvil.web.FacetField" %>

<%
// Params for the page
String q = tools.getString("q", null);
FacetSort sort = (FacetSort)tools.getEnum("ord", FacetSort.alpha, Cookies.facetSort);

//global variables
FacetField field = FacetField.author;
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
BitSet bits = bits(alix, corpus, q);
final boolean filtered = (bits !=null);
// is there a query and scores to get ?
TermList qTerms = alix.qTermList(TEXT, q);
final boolean queried =  (qTerms != null && qTerms.size() > 0);
if(!queried && sort == FacetSort.score) sort = FacetSort.freq;

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Facettes</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/js/common.js">//</script>
    <base target="page" href="snip"/>
  </head>
  <body class="facet">
    <form id="qform" target="_self">
      <input type="submit" style="position: absolute; left: -9999px; width: 1px; height: 1px;"  tabindex="-1" />
      <input type="hidden" id="q" name="q" value="<%=Jsp.escape(q)%>" autocomplete="off"/>
      <select name="ord" onchange="this.form.submit()">
        <option/>
        <%= options(sort) %>
      </select>
    </form>
    <main>
    <%
    
Facet facet = alix.facet(field.name(), TEXT);
TopTerms dic = facet.topTerms(bits, qTerms, null);
//Hack to use facet as a navigator in results, cache results in the facet order
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, DocSort.author);
int[] nos = facet.nos(topDocs);
dic.setNos(nos);



if (queried)  out.println("<h4><span class=\"occs\" title=\"Nombre d’occurrences\">occs</span>  "
    +field.label+" <span class=\"docs\" title=\"Nombre de documents\">(chapitres)</span></h4>");
else out.println("<h4>"+field.label+" <span class=\"docs\" title=\"Nombre de documents\">(chapitres)</span></h4>");


switch(sort){
  case alpha:
    dic.sort();
    break;
  case freq:
    if (queried) dic.sortByOccs();
    else if (filtered) dic.sortByHits();
    else dic.sortByLengths();
    break;
  case score:
    if (queried) dic.sortByScores();
    else if (filtered) dic.sortByHits();
    else dic.sortByLengths();
    break;
  default:
    dic.sort();
}



int hits = 0, docs= 0, n = 0;
long occs = 0;

final StringBuilder href = new StringBuilder();
href.append("?sort=author");
if (q != null) href.append("&amp;q=").append(Jsp.escUrl(q));;
final int hrefLen = href.length();

while (dic.hasNext()) {
  dic.next();
  n = dic.n();
  docs = dic.docs();
  if (filtered) {
    hits = dic.hits();
    if (hits < 1) continue; // in alpha order, try next
  }
  if (queried) {
    occs = dic.occs();
    if (hits < 1) continue; // in alpha order, try next
  }
  href.setLength(hrefLen);
  href.append("&amp;start=" + (n+1)); // parenthesis for addition!
  href.append("&amp;hpp=");
  if (filtered || queried) href.append(hits);
  else href.append(docs);

        
  out.print("<div class=\"term\">");
  if (queried) out.print("<span class=\"occs\">"+occs+"</span> ");
  out.print("<a href=\""+href+"\">");
  out.print(dic.term());
  out.print("</a>");
  if (filtered || queried) out.print(" <span class=\"docs\">("+hits+" / "+docs+")</span>    ");
  else out.print(" <span class=\"docs\">("+docs+")</span>    ");
  out.println("</div>");
}
    %>
    </main>
    <script src="../static/js/facet.js">//</script>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
  </body>
</html>
