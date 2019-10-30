<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="alix.lucene.analysis.MetaAnalyzer" %>
<%@ page import="alix.lucene.search.Doc" %>
<%@ page import="alix.lucene.search.Marker" %>
<%@ page import="alix.util.Top" %>
<%!
final static Analyzer ANAMET = new MetaAnalyzer();
final static HashSet<String> DOC_SHORT = new HashSet<String>(Arrays.asList(new String[] {Alix.ID, Alix.BOOKID, "bibl"}));
final static Query QUERY_LEVEL = new TermQuery(new Term(Alix.LEVEL, Alix.CHAPTER));
%>
<%
String refId = tools.getString("refid", null);
int refDocId = tools.getInt("refdocid", -1);
String refType = tools.getString("reftype", null);

String q = tools.getString("q", null);
int fromDoc = tools.getInt("fromdoc", -1);
float fromScore = tools.getFloat("fromscore", 0);
int hpp = tools.getInt("hpp", 100);
hpp = Math.min(hpp, 1000);

//canonize query string to push in history, avoiding bad requests ?
LinkedHashMap<String, String> pars = new LinkedHashMap<String, String>();

// Is there a good reference doc requested ?
Doc refDoc = null;
try {
  if (refId != null) refDoc = new Doc(alix, refId, DOC_SHORT);
  else if (refDocId >= 0) {
    refDoc = new Doc(alix, refDocId, DOC_SHORT);
    refId = refDoc.id();
  }
}
catch (IllegalArgumentException e) {} // unknown id




%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Rechercher un texte, <%=baseTitle %> [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
  </head>
  <body class="results">
    <header>
<%
if (refDoc != null) {
  out.println("<h1>Textes similaires</h1>");
  out.println("<a href=\"?\" class=\"delete\">🞬</a>");
  out.println("<b>Textes similaires à :</b>");
  out.println(refDoc.doc().get("bibl"));
}
else {
  out.println("<h1>Rechercher un texte par ses métadonnées</h1>");
}
if (corpus != null) {
  out.println("<p>Dans votre corpus : "+"<b>"+corpus.name()+"</b>"+"</p>");
}
%>
    </header>
    <form>
      <%
if (refDoc != null) {
  out.println("<input type=\"hidden\" name=\"refid\" value=\"" +refDoc.id()+"\"/>");
}
else {
  out.print("<input size=\"50\" type=\"text\" id=\"q\" onfocus=\"var len = this.value.length * 2; this.setSelectionRange(len, len); \" autofocus=\"true\"");
  out.println(" spellcheck=\"false\" autocomplete=\"off\" name=\"q\" value=\"" +JspTools.escapeHtml(q)+"\"/>");
  // out.println("<br/>" + query);
}
      %>
    </form>
    <p/>
    <main>
      <nav id="chapters">
<%
Query query = null;
if (refDoc != null) {
  Top<String> topTerms;
  pars.put("refid", refDoc.id());
  if (refType != null) pars.put("reftype", refType);
  if ("names".equals(refType)) topTerms = refDoc.names(TEXT);
  else topTerms = refDoc.theme(TEXT);
  query = Doc.moreLikeThis(TEXT, topTerms, 50);
}
else if (q != null) {
  pars.put("q", q);
  String lowbibl = q.toLowerCase();
  query = Alix.qParse("bibl", lowbibl, ANAMET, Occur.MUST);
}
// restrict to corpus
if (corpus != null) {
  query = corpusQuery(corpus, query);
}
// meta, restric document type
else if(query != null && q != null) {
  query = new BooleanQuery.Builder()
    .add(QUERY_LEVEL, Occur.FILTER)
    .add(query, Occur.MUST)
  .build();
}
// no queries by parameter
else if (query == null) {
  query = QUERY_LEVEL;
}

out.println("<!-- "+query+" -->");

TopDocs results = null;
if (query == null);
else if (fromDoc > -1) {
  ScoreDoc from = new ScoreDoc(fromDoc, fromScore);
  results = searcher.searchAfter(from, query, hpp);
}
else {
  results = searcher.search(query, hpp);
}

if(results == null); // no query
else if(results.totalHits.value == 0); // no results
else {
  long totalHits = results.totalHits.value;
  ScoreDoc[] hits = results.scoreDocs;
  String paging = "";
  if (fromDoc > 0) {
    paging = "&amp;fromdoc="+fromDoc+"&amp;fromscore="+fromScore;
  }
  Marker marker = null;
  String qback = "";
  // a query to hilite in records
  if (q != null) {
    marker = new Marker(ANAMET, q);
    qback += "&amp;q="+q;
  }
  else if (refId != null) {
    qback += "&amp;refid=" + refId;
    if (refType != null) qback += "&amp;reftype" + refType;
  }
  int docId = 0;
  float score = 0;
  for (int i = 0, len = hits.length; i < len; i++) {
    docId = hits[i].doc;
    score = hits[i].score;
    // out.append("<li>");
    Document doc = searcher.doc(docId, DOC_SHORT);
    
    String text = doc.get("bibl");
    if (marker != null) {
      out.append("<a class=\"bibl\" href=\"compdoc.jsp?id="+doc.get(Alix.ID)+qback+paging+"\">");
      out.append(marker.mark(text));
      out.append("</a>");
    }
    else {
      out.append("<a class=\"bibl\" href=\"compdoc.jsp?id="+doc.get(Alix.ID)+qback+paging+"\">");
      out.append(text);
      out.append("</a>");
    }
    // out.append("</li>\n");
  }
  if (hits.length < totalHits) {
    out.append("<a  class=\"more\" href=\"?fromscore="+score+"&amp;fromdoc="+docId+qback+"\">⮟</a>\n");
  }
}

out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->");

%>
      </nav>

    </main>
    <script src="../static/js/list.js">//</script>
  </body>
</html>