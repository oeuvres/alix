<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="org.apache.lucene.util.automaton.Automaton" %>
<%@ page import="org.apache.lucene.util.automaton.ByteRunAutomaton" %>
<%@ page import="alix.lucene.search.Doc" %>
<%@ page import="alix.lucene.util.WordsAutomatonBuilder" %>
<%
final int hppDefault = 100;
final int hppMax = 1000;
  // parameters
int hpp = tools.getInt("hpp", hppDefault);
if (hpp > hppMax || hpp < 1) hpp = hppDefault;
final String q = tools.getString("q", null);
DocSort sort = (DocSort)tools.getEnum("sort", DocSort.score, Cookies.docSort);
final boolean expression = tools.getBoolean("expression", false, Cookies.expression);

int start = tools.getInt("start", 1);
if (start < 1) start = 1;
// global variables
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, sort);
final int left = 70;
final int right = 50;
// terms of the query
final String field = TEXT;
String[] terms = alix.qTermList(field, q).toArray();

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche, <%=props.get("title")%> [Obvil]</title>
    <script src="../static/js/common.js">//</script>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <style>
span.left {display: inline-block; text-align: right; width: <%= Math.round(left * 1.0)%>ex; padding-right: 1ex;}
    </style>
  </head>
  <body class="results">
      <form id="qform">
        <input type="submit"
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <%
if (start > 1 && q != null) {
  int n = Math.max(1, start-hppDefault);
  out.println("<button name=\"next\" type=\"submit\" onclick=\"this.form['start'].value="+n+"\">◀</button>");
}
        %>
        <input type="hidden" id="q" name="q" value="<%=Jsp.escape(q)%>" autocomplete="off" size="60" autofocus="autofocus" 
          onfocus="this.setSelectionRange(this.value.length,this.value.length);"
          oninput="this.form['start'].value='';"
        />
        <script>if(self == top) { input = document.getElementById("q"); if (input && input.type == "hidden") input.type = "text";}</script>
        <select name="sort" onchange="this.form['start'].value=''; this.form.submit()" title="Ordre">
          <option/>
          <%= options(sort) %>
        </select>
               <%
if (terms.length < 2 );
else if (expression) {
  out.println("<button title=\"Cliquer pour dégrouper les locutions\" type=\"submit\" name=\"expression\" value=\"false\">✔ Locutions</button>");
}
else {
  out.println("<button title=\"Cliquer pour grouper les locutions\" type=\"submit\" name=\"expression\" value=\"true\">☐ Locutions</button>");
}
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
if (topDocs != null) {
  Automaton automaton = WordsAutomatonBuilder.buildFronStrings(terms);
  ByteRunAutomaton include = new ByteRunAutomaton(automaton);
  // get the index in results
  ScoreDoc[] scoreDocs = topDocs.scoreDocs;
  // where to start loop ?
  int i = start - 1; // private index in results start at 0
  int max = scoreDocs.length;
  if (i < 0) i = 0;
  else if (i > max) i = 0;
  // loop on docs
  int docs = 0;
  final int gap = 3;
  
  final StringBuilder href = new StringBuilder();
  href.append("doc?");
  if (q != null) href.append("q=").append(Jsp.escape(q));
  final int hrefLen = href.length();

  while (i < max) {
    final int docId = scoreDocs[i].doc;
    i++; // is now a public start
    final Doc doc = new Doc(alix, docId);
    String type = doc.doc().get(Alix.TYPE);
    // TODO Enenum
    if (type.equals(DocType.book.name())) continue;
    if (doc.doc().get(TEXT) == null) continue;
    href.setLength(hrefLen); // reset href
    href.append("&amp;id=").append(doc.id()).append("&amp;start=").append(i);
    
    // show simple metadata
    if (terms == null || terms.length == 0) {
      out.println("<article class=\"res\">");
      out.println("<header>");
      out.println("<small>"+(i)+".</small> ");
      out.println("<a href=\""+href+"\">"+doc.get("bibl")+"</a>");
      out.println("</header>");
      out.println("</article>");
      if (++docs >= hpp) break;
      continue;
    }
    
    String[] lines = doc.kwic(field, include, href.toString(), 200, left, right, gap, expression);
    if (lines == null || lines.length < 1) continue;
    // doc.kwic(field, include, 50, 50, 100);
    out.println("<article class=\"res\">");
    out.println("<header>");
    out.println("<small>"+(i)+"</small> ");

    out.println("<a href=\""+href+"\">"+doc.get("bibl")+"</a></header>");
    for (String l: lines) {
      out.println("<div class=\"line\">"+l+"</div>");
    }
    out.println("</article>");
    if (++docs >= hpp) break;
  }
  
}
    %>
    </main>
    <a href="#" id="gotop">▲</a>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
  </body>
</html>
