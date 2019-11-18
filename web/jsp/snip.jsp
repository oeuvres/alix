<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="org.apache.lucene.search.uhighlight.UnifiedHighlighter" %>
<%@ page import="org.apache.lucene.search.uhighlight.DefaultPassageFormatter" %>
<%@ page import="alix.lucene.search.HiliteFormatter" %>
<%
final int hppDefault = 100;
final int hppMax = 1000;
// parameters
int hpp = tools.getInt("hpp", hppDefault);
if (hpp > hppMax || hpp < 1) hpp = hppDefault;
final String q = tools.getString("q", null);
DocSort sort = (DocSort)tools.getEnum("sort", DocSort.score, Cookies.docSort);
int start = tools.getInt("start", 1);
if (start < 1) start = 1;
// global variables
final String fieldName = TEXT;
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, sort);

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche, <%=props.get("title")%> [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <script src="../static/js/common.js">//</script>
  </head>
  <body class="results">
    <form id="qform">
      <input type="submit"
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <%
if (start > 1 && q != null) {
  int n = Math.max(1, start-hppDefault);
  out.println("<button name=\"prev\" type=\"submit\" onclick=\"this.form['start'].value="+n+"\">◀</button>");
}
        %>
      <input type="hidden" id="q" name="q" value="<%=Jsp.escape(q)%>" autocomplete="off" size="60" autofocus="autofocus" 
        onfocus="this.setSelectionRange(this.value.length,this.value.length);"
        oninput="this.form['start'].value='';"/>
      <script>if(self == top) { input = document.getElementById("q"); if (input && input.type == "hidden") input.type = "text";}</script>
      <select name="sort" onchange="this.form['start'].value=''; this.form.submit()" title="Ordre">
        <option/>
        <%= options(sort) %>
      </select>
               <%
if (topDocs != null) {
  long max = topDocs.totalHits.value;
  out.println("<input  name=\"start\" value=\""+start+"\" autocomplete=\"off\" class=\"start\"/>");
  out.println("<span class=\"hits\"> / "+ max  + "</span>");
  int n = start + hpp;
  if (n < max) out.println("<button name=\"next\" type=\"submit\" onclick=\"this.form['start'].value="+n+"\">▶</button>");
}
        %>
    </form>
    <main>
    <%
if (topDocs == null) {
  // what shal we do ?
}
// TODO: merge these two blocks
else if (q!=null) { // a query, something to hilite

  UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, alix.analyzer());
  uHiliter.setMaxLength(500000); // biggest text size to process
  uHiliter.setFormatter(new  HiliteFormatter());
  Query query = getQuery(alix, q, corpus); // to get the terms to Hilite
  ScoreDoc[] scoreDocs = topDocs.scoreDocs;
  if (start > scoreDocs.length) start = 1;
  int len = Math.min(hpp, 1 + scoreDocs.length - start);
  
  int docIds[] = new int[len];
  for (int i = 0; i < len; i++) {
    docIds[i] = scoreDocs[start - 1 + i].doc;
  }
  Map<String, String[]> res = uHiliter.highlightFields(new String[]{fieldName}, query, docIds, new int[]{5});
  String[] fragments = res.get(fieldName);

  final StringBuilder href = new StringBuilder();
  href.append("?q=").append(Jsp.escUrl(q));
  final int hrefLen = href.length();
  for (int i = 0; i < len; i++) {
    href.setLength(hrefLen); // reset query String
    int docId = docIds[i];
    Document document = searcher.doc(docId);
    out.println("<article class=\"res\">");
    // hits[i].doc
    out.println("  <header>");
    out.println("<small>"+(start + i)+".</small> ");
    href.append( "&amp;start=").append((i + start));
    if (sort != DocSort.score) href.append( "&amp;sort=").append(sort.name());
    out.println("<a href=\"doc" + href.toString()+"\">");
    out.println(document.get("bibl"));
    out.println("</a>");
    out.println("  </header>");
    if (fragments[i] != null) {
      out.print("<p class=\"frags\">");
      out.println(fragments[i]);
      out.println("</p>");
    }
    out.println("</article>");
  }
}
else { // list title of documents
  ScoreDoc[] scoreDocs = topDocs.scoreDocs;
  // start has here a public value, starting at 1
  if (start > scoreDocs.length) start = 1;
  int limit = Math.min(start + hpp, scoreDocs.length+1);
  
  final StringBuilder href = new StringBuilder();
  href.append("?");
  final int hrefLen = href.length();
  while(start < limit) {
    href.setLength(hrefLen); // reset query String
    final int docId = scoreDocs[start - 1].doc;
    Document document = searcher.doc(docId);
    out.println("<article class=\"res\">");
    out.println("  <header>");
    out.println("<small>"+(start)+".</small> ");
    href.append( "&amp;start=").append((start));
    if (sort != DocSort.score) href.append( "&amp;sort=").append(sort.name());
    out.println("<a href=\"doc" + href.toString()+"\">");
    out.println(document.get("bibl"));
    out.println("</a>");
    out.println("  </header>");
    /*
    if (fragments[i] != null) {
      out.print("<p class=\"frags\">");
      out.println(fragments[i]);
      out.println("</p>");
    }
    */
    start++;
    out.println("</article>");
  }
}
    %>
    </main>
  </body>
</html>
