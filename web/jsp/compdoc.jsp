<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="alix.lucene.search.Doc" %>
<%@ page import="alix.util.Top" %>
<%!
final static HashSet<String> DOC_SHORT = new HashSet<String>(Arrays.asList(new String[] {Alix.ID, Alix.BOOKID, "bibl"}));

static String wordList(Top<String> top, String type)
{
  StringBuilder sb = new StringBuilder();
  int max = 50;
  boolean first = true;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else sb.append(", ");
    final String form = entry.value();
    sb.append("<a class=\""+Doc.csstok(form)+"\">");
    sb.append(JspTools.escapeHtml(form));
    sb.append("</a>");
    // out.print(" ("+entry.score()+")");
    // if (entry.score() <= 0) break;
    if (--max <= 0) break;
  }
  return sb.toString();
}
%>
<%
// get a doc, by String id is preferred (more persistant)
String id = tools.getString("id", null);
int docId = tools.getInt("docid", -1);
// if a refernce doc
String refId = tools.getString("refid", null);
int refDocId = tools.getInt("refdocid", -1);
// params to go back in query
String q = tools.getString("q", null);
int fromDoc = tools.getInt("fromdoc", -1);
float fromScore = tools.getFloat("fromscore", 0);

// global variables
Doc doc = null;
try { // load full document
  if (id != null) doc = new Doc(alix, id);
  else if (docId >= 0) {
    doc = new Doc(alix, docId);
    id = doc.id();
  }
}
catch (IllegalArgumentException e) {} // unknown id

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title><%
if (doc != null) {
  out.print(JspTools.detag(doc.doc().get("bibl")));
}
else {
  out.print("Comparaison, document");
}
    %> [Obvil]</title>
    <link href="../static/vendors/teinte.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <script>
    <%
if (doc != null) {
  out.println("const docLength="+doc.length(TEXT)+";");
  out.println("const id=\""+doc.id()+"\";");
}
    %>
    </script>
  </head>
  <body  class="comp">

<% if (doc == null) { // no doc requested %>

    <p><a href="meta.jsp">Chercher un document</a></p>

<% } else { // doc found %>

<%
String bibl = doc.doc().get("bibl");
%>
    <header class="biblbar" title="<%= JspTools.detag(bibl) %>">
        <%
// link to go back to results
String url = "meta.jsp";
if (q != null) url += "?q=" + JspTools.escapeHtml(q);
else if (refId != null) url += "?refid=" + refId;
else if (refDocId > -1) url += "?refdocid=" + refDocId;
if (fromDoc >= 0) url += "&amp;fromdoc=" + fromDoc + "&amp;fromscore=" + fromScore;
        %>
      <a class="back" href="<%=url %>" title="Retour aux résultats">⮐</a>
      <a href="#" class="bibl"><%= bibl %></a>
    </header>
<%

  Top<String> top = doc.theme(TEXT);
  out.println("<nav class=\"biflex\">");
  out.println("<p class=\"keywords\">");
  out.println("<label onclick=\"clickSet(this)\">Mots spécifiques</label> : ");
  out.println(wordList(top, "WORD"));
  out.print(".");
  out.println("</p>");
  out.println("<a class=\"goright\" href=\"meta?reftype=theme&amp;refid="+id+"\" target=\"right\">⮞</a>");
  out.println("</nav>");

  top = doc.names(TEXT);
  out.println("<nav class=\"biflex\">");
  out.println("<p class=\"keywords\">");
  out.println("<label onclick=\"clickSet(this)\">Noms cités</label> : ");
  out.println(wordList(top, "NAME"));
  out.print(".");
  out.println("</p>");
  out.println("<a class=\"goright\" href=\"meta?reftype=names&amp;refid="+id+"\" target=\"right\">⮞</a>");
  out.println("</nav>");
  
%>

    <main>
      <article id="text">
        <%= doc.paint(TEXT) %>
      </article>


<% } %>
    </main>
    <nav id="rulhi">
    </nav>
    <script src="../static/js/doc.js">//</script>
  </body>
</html>