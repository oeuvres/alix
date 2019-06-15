<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Alix, test</title>
    <link href="vendors/tei2html.css" rel="stylesheet"/>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="results">
  <%
  
while (true) {
  IndexReader reader = lucene.reader();
  // 
  int docId = getParameter(request, "doc", -1);
  if (docId < 0) break;
  if( docId >= reader.maxDoc()) break; // tdoc do not existsc
  
  Document document = reader.document(docId);
  if (document == null) {
    // something have to be said here
    break;
  }
  String value;
  out.println("<article class=\"chapter\">");
  value = document.get("bibl");
  if (value != null) {
    out.println("<header class=\"bibl\">");
    out.print(value);
    out.println("</header>");
  }
  value = document.get(TEXT);
  // TODO, hilite
  out.print(value);
  out.println("</article>");
  break;
}
  %>
  </body>
</html>