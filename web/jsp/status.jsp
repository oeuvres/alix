<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="
java.nio.file.Path
" %>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="fr" lang="fr">
  <head>
    <meta charset="UTF-8"/>
    <link href="../static/alix.css" rel="stylesheet"/>
    <title>Status, Obvil</title>
  </head>
  <body>
  <body class="document">
    <article class="chapter">
      <h1>Obvil, status</h1>
      <ul>
        <%
  Path path = (Path)request.getAttribute("path");
        %>
          <li>ContexPath=<%=request.getContextPath()%></li>
          <li>Orig=<%=path%></li>
          <li>RequestUri=<%=request.getRequestURI()%></li>
          <li>obvilDir=<%=request.getAttribute("obvilDir")%></li>
          <li>base=<%=request.getAttribute("base")%></li>
      </ul>
    </article>
  </body>
</html>

