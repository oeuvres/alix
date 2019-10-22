<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%

String id = tools.get("id", null);
int docId = tools.get("docid", -1);
int refDocId = tools.get("refdocid", -1);
int fromDoc = tools.get("fromdoc", -1);
int fromScore = tools.get("rfromscore", 0);
int n = tools.get("n", -1);
IndexReader reader = alix.reader();
Doc doc = null;
if (docId > 0) doc = new Doc(alix, docId);
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
  </head>
  <body class="document">
<%
if (doc == null) { // no doc requested
%>
  <p>Rechercher un document dans la fenêtre de gauche</p>
<%
}
else {
%>
    <form action="list.jsp">
      <input type="hidden" name="refdocid" value="<%=refDocId%>"/>
      <input type="hidden" name="fromdoc" value="<%=fromDoc%>"/>
      <input type="hidden" name="fromscore" value="<%=fromScore%>"/>
      <button>Retour à la liste</button>
    </form>
    <header>
<% 
if (doc != null) out.println("<h2>"+doc.get("bibl")+"</h2>"); 
Doc refDoc = new Doc(alix, refDocId);
if (refDoc != null) out.println("<p><b>Mots clés de :</b> "+refDoc.get("bibl")+"</h2>"); 
%>    
    </header>
    <main>
<%
  Top<String> topTerms = refDoc.theme(TEXT);
  String text = doc.hilite(TEXT, topTerms.toArray());
  out.println(text);
}



    
    %>
    </main>
  </body>
</html>