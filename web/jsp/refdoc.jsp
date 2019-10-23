<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="prelude.jsp" %>
<%

int docId = tools.get("docid", -1);
int fromDoc = tools.get("fromdoc", -1);
int fromScore = tools.get("rfromscore", 0);
int n = tools.get("n", -1);
IndexReader reader = alix.reader();
Doc doc = null;
if (docId > -1) doc = new Doc(alix, docId);
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title><%
if (doc != null) {
  out.print(Char.detag(doc.fields().get("bibl")));
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
  <p><a href="reflist.jsp">Chercher un document</a></p>
<%
}
else {
%>
    <form action="reflist.jsp">
      <input type="hidden" name="q" value="<%=q%>"/>
      <input type="hidden" name="fromdoc" value="<%=fromDoc%>"/>
      <input type="hidden" name="fromscore" value="<%=fromScore%>"/>
      <button>Retour à la liste</button>
    </form>
        <header>
<% 
if (doc != null) out.println("<h2>"+doc.get("bibl")+"</h2>"); 
%>    
    </header>
    <main>
<%
  Top<String> topTerms = doc.theme(TEXT);
  Top<String> top;

  out.println("<p class=\"keywords\">");
  out.println("<b>Mots clés</b> : ");
  top = doc.theme(TEXT);
  int max = 50;
  boolean first = true;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else out.println(", ");
    String word = entry.value();
    out.print(word);
    // out.print(" ("+entry.score()+")");
    if (entry.score() <= 0) break;
    if (--max <= 0) break;
  }
  out.println(".</p>");

  String text = doc.hilite(TEXT, topTerms.toArray());
  out.println(text);
}



    
    %>
    </main>
  </body>
</html>