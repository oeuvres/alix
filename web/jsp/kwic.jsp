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
int start = tools.getInt("start", 1);
if (start < 1) start = 1;
// global variables
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, sort);
final int left = 50;
final int right = 50;
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Recherche, <%=props.get("title")%> [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <style>
span.left {display: inline-block; text-align: right; width: 70ex; padding-right: 1ex;}
    </style>
  </head>
  <body class="results">
      <form id="qform">
        <input id="q" name="q" value="<%=Jsp.escape(q)%>" autocomplete="off" size="60" autofocus="autofocus" onclick="this.select();"/>
        <label>
         Tri
          <select name="sort" onchange="this.form.submit()">
            <option>Pertinence</option>
            <%= sortOptions(sort) %>
          </select>
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
  start--; // private index in results start at 0
  if (start > scoreDocs.length) start = 0;
  int limit = Math.min(start + hpp, scoreDocs.length);
  // load a doc 
  for (int i = start; i < limit; i++) {
    final int docId = scoreDocs[i].doc;
    final Doc doc = new Doc(alix, docId);
    out.println("<h4>"+doc.get("bibl")+"</h4>");
    String[] lines = doc.kwic(field, include, 50, 50, 100);
    for (String l: lines) {
      out.println("<div>"+l+"</div>");
    }
  }
  
}
    %>
    </main>
  </body>
  <script src="../static/js/snip.js">//</script>
</html>
