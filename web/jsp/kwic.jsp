<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="org.apache.lucene.util.automaton.Automaton" %>
<%@ page import="org.apache.lucene.util.automaton.ByteRunAutomaton" %>
<%@ page import="alix.lucene.search.Doc" %>
<%@ page import="alix.lucene.util.WordsAutomatonBuilder" %>
<%
  // parameters
final String q = tools.getString("q", null);
final String sort = request.getParameter("sort");
final int hpp = tools.getInt("hpp", 100);
final boolean expression = tools.getBoolean("expression", false);

int start = tools.getInt("start", 1);
if (start < 1) start = 1;
// global variables
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, sort);
final int left = 70;
final int right = 50;
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
div.line a { font-weight: bold; padding: 0 1ex; background: #FFFFFF; color: #ea5b0c; }
div.line a:hover { text-decoration: none; color: #000;}
article.kwic { margin: 1rem 0;}
article.kwic header {text-align:left; font-weight: bold; margin-bottom: 0.5rem;}
    </style>
  </head>
  <body class="results">
      <form id="qform">
        <input type="hidden" id="q" name="q" value="<%=Jsp.escape(q)%>" autocomplete="off" size="60" autofocus="autofocus" onfocus="this.setSelectionRange(this.value.length,this.value.length);"/>
        <label>
         Tri
          <select name="sort" onchange="this.form.submit()">
            <option>Pertinence</option>
            <%= sortOptions(sort) %>
          </select>
        </label>
        <label title="">
         Locutions
            <input type="checkbox" name="expression" <%= (expression)?"checked=\"checked\"":""  %> onclick="this.form.submit()"/>
        </label>
      </form>
    <main>
    <%
if (topDocs != null) {
  final String field = TEXT;
  // compile automaton for the searched terms
  String[] terms = alix.qTermList(field, q).toArray();
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
  while (i < max) {
    final int docId = scoreDocs[i].doc;
    i++; // do not forget to increment 
    final Doc doc = new Doc(alix, docId);
    String type = doc.doc().get(Alix.TYPE);
    // TODO Enenum
    if (type.equals(Alix.BOOK)) continue;
    if (doc.doc().get(TEXT) == null) continue;
    String href = "doc?id=" + doc.id()+"&amp;q="+q;
    String[] lines = doc.kwic(field, include, href, 200, left, right, gap, expression);
    if (lines == null || lines.length < 1) continue;
    // doc.kwic(field, include, 50, 50, 100);
    out.println("<article class=\"kwic\">");
    out.println("<header><a href=\""+href+"\">"+doc.get("bibl")+"</a></header>");
    for (String l: lines) {
      out.println("<div class=\"line\">"+l+"</div>");
    }
    out.println("</article>");
    if (++docs >= hpp) break;
  }
  
}
    %>
    </main>
  </body>
</html>
