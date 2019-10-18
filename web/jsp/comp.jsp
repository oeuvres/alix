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
#right {
  float: right;
}
iframe {
  border: none;
  margin: 0;
  padding: 0;
}
    </style>
  </head>
  <body style="padding: 0; margin">
    <iframe id="left" name="left" src="comp1.jsp?q=<%=q%>" width="50%" height="99.5%">
    </iframe>
    <iframe id="right" name="right" src="comp2.jsp" width="50%" height="99.5%">
    </iframe>
  </body>
</html>
