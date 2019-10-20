<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%

String id = getParameter(request, "id", null);
int docId = getParameter(request, "docid", -1);
int refDocId = getParameter(request, "refdocid", -1);
int fromDoc = getParameter(request, "fromdoc", -1);
int fromScore = getParameter(request, "rfromscore", 0);
int n = getParameter(request, "n", -1);
IndexReader reader = alix.reader();
Doc doc = null;
try {
  if (id != null) doc = new Doc(alix, id);
  else if (docId > -1) doc = new Doc(alix, docId);
}
catch(Exception e) {
  doc = null;
}
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
    <main>
<%
  Doc refDoc = new Doc(alix, refDocId);
  Top<String> topTerms = refDoc.theme(TEXT);
  String text = doc.hilite(TEXT, topTerms.toArray());
  out.println(text);
}



    
    %>
    </main>
  </body>
</html>