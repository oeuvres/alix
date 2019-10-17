<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <style>
body, html {
  height: 100%;
  margin: 0;
  padding: 0;
}
#target {
  float: right;
}
iframe {
  border: none;
}
    </style>
  </head>
  <body style="padding: 0; margin">
    <iframe id="source" name="source" src="comp1.jsp?q=<%=q%>" width="50%" height="100%">
    </iframe>
    <iframe id="target" name="target" src="comp2.jsp" width="50%" height="100%">
    </iframe>
  </body>
</html>
