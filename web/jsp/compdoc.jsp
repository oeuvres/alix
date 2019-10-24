<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="prelude2.jsp" %>
<%@ page import="alix.lucene.search.Doc" %>
<%@ page import="alix.util.Top" %>
<%!
final static HashSet<String> DOC_SHORT = new HashSet<String>(Arrays.asList(new String[] {Alix.ID, Alix.BOOKID, "bibl"}));
%>
<%
// get a doc, by String id is preferred (more persistant)
String id = tools.getString("id", null);
int docId = tools.getInt("docid", -1);
// params to go back in query
String q = tools.getString("q", "");
String refId = tools.getString("refid", null);
int refDocId = tools.getInt("refdocid", -1);
int fromDoc = tools.getInt("fromdoc", -1);
float fromScore = tools.getFloat("fromscore", 0);



Doc doc = null;
try { // load full document
  if (id != null) doc = new Doc(alix, id);
  else if (docId >= 0) doc = new Doc(alix, docId);
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
  out.print("Similarités, documents");
}
    %> [Obvil]</title>
    <title></title>
    <link href="../static/vendors/teinte.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <script>
var winaside = parent.document.getElementById("right");
    <% 
if (docId > 0) out.println("showRight("+docId+");");
    %>

function showRight (docId) {
  if (docId < 0) return false;
  if (!winaside) return false;
  var url = "simlist.jsp?refdocid="+docId;
  winaside.src=url;
}
    </script>
  </head>
  <body class="document">
<%
if (doc == null) { // no doc requested
%>
  <p><a href="complist.jsp">Chercher un document</a></p>
<%
}
else {
%>
    <form action="complist.jsp">
      <input type="hidden" name="q" value="<%= JspTools.escapeHtml(q) %>"/>
      <%
  if (fromDoc >= 0) {
    out.println("<input type=\"hidden\" name=\"fromdoc\" value=\""+fromDoc+"\"/>");
    out.println("<input type=\"hidden\" name=\"fromscore\" value=\""+fromScore+"\"/>");
  }
      %>
      <button>Retour à la liste</button>
    </form>
        <header>
            <h2><%=doc.doc().get("bibl") %></h2>
    </header>
    <main>
<%

  {
    Top<String> top = doc.theme(TEXT);
    int max = 50;
    out.println("<p class=\"keywords\">");
    out.println("<b>Mots clés</b> : ");
    boolean first = true;
    for (Top.Entry<String> entry: top) {
      if (first) first = false;
      else out.println(", ");
      String word = entry.value();
      out.print(word);
      // out.print(" ("+entry.score()+")");
      // if (entry.score() <= 0) break;
      if (--max <= 0) break;
    }
    out.println(".</p>");
  }
  Top<String> top = doc.names(TEXT);
  {
    int max = 50;
    out.println("<p class=\"keywords\">");
    out.println("<b>Noms cités</b> : ");
    boolean first = true;
    for (Top.Entry<String> entry: top) {
      if (first) first = false;
      else out.println(", ");
      String word = entry.value();
      out.print(word);
      // out.print(" ("+entry.score()+")");
      // if (entry.score() <= 0) break;
      if (--max <= 0) break;
    }
    out.println(".</p>");
  }
  
  // out.println(doc.doc().get(TEXT));
  String text = doc.hilite(TEXT, top.toArray());
  out.println(text);
}



    
    %>
    </main>
  </body>
</html>