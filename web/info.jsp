<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Tests [Alix]</title>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="info">
    <%
IndexReader reader = alix.reader();

%>
    <h1><%=alix.path %></h1>
    <ul>
      <li>Documents: <%= reader.numDocs() %></li>
      <li><%= request  %></li>
    </ul>
  </body>
</html>