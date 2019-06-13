<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Alix, test</title>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="info">
    <%
IndexReader reader = lucene.reader();

%>
    <h1><%=lucene.path %></h1>
    <ul>
      <li>Documents: <%= reader.numDocs() %></li>
      <li><%= request  %></li>
    </ul>
  </body>
</html>