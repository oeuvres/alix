<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.util.Map" %>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="fr" lang="fr">
  <head>
    <meta charset="UTF-8"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
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
          <li>realPath=<%=application.getRealPath("WEB-INF/obvil")%></li>
          <li>obvilDir=<%=request.getAttribute("obvilDir")%></li>
          <li>base=<%=request.getAttribute("base")%></li>
      </ul>
      <h5>Paramètres</h5>
      <dl>
      <%
      Map<String, String[]> parameters = request.getParameterMap();
      for(String key : parameters.keySet()) {
        out.println("<dt>"+key+"</dt>");
        String[] values = parameters.get(key);
        if (values == null);
        else if (values.length < 1);
        else {
          out.println("<dd>");
          boolean first = true;
          for(String v: values) {
            if (first) first = false;
            else out.print("<br/>");
            out.println(v);
          }
          out.println("</dd>");
        }
        
      }
      %>
    </article>
  </body>
</html>

