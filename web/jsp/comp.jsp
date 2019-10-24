<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="prelude2.jsp" %>
<%
String id1 = tools.getString("id1", null);
int docId1 = tools.getInt("docid1", -1);
String id2 = tools.getString("id2", null);
int docId2 = tools.getInt("docId2", -1);
String q = tools.getString("q", null);

String url1;
if (id1 != null) { // doc by id requested
  url1 = "compdoc.jsp?" + "id=" + id1;
}
else if (docId1 >= 0) { // doc by docid requested
  url1 = "compdoc.jsp?" + "docid=" + docId1;
}
else if (q != null) { // query
  url1 = "complist.jsp?" + "q=" + q;
}
else { // query
  url1 = "complist.jsp";
}

String url2;
String ref = "";

if (id2 != null) { // doc by id requested
  url2 = "compdoc.jsp?" + "id=" + id2;
}
else if (docId2 >= 0) { // doc by docid requested
  url2 = "compdoc.jsp?" + "docid=" + docId2;
}
else { // query
  url2 = "complist.jsp?";
}


if (id1 != null) { // reference document for list or hilite
  url2 += "&amp;refid=" + id1;
}
else if (docId1 >= 0) { // reference document for list or hilite
  url2 += "&amp;refdocid=" + docId1;
}
else if (q != null) { // query
  url2 += "&amp;q=" + q;
}


%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Face à face, <%=baseTitle %> [Obvil]</title>
    <style>
body, html {
  height: 100%;
  margin: 0;
  padding: 0;
}
iframe {
  border: none;
  margin: 0;
  padding: 0;
  width: 50%;
}
#left {
}

#right {
  float: right;
}
    </style>
  </head>
  <body>
    <iframe id="left" name="left" src="<%=url1%>" width="50%" height="99.5%">
    </iframe>
    <iframe id="right" name="right" src="<%=url2%>" width="50%" height="99.5%">
    </iframe>
    <script type="text/javascript">
window.onhashchange = function (e)
{
  let url = new URL(e.newURL);
  let hash = url.hash;
  return propaghi(hash);
}

function propaghi(hash)
{
  let text = decodeURIComponent(hash);
  if (text[0] == "#") text = text.substring(1);
  words = text.split(/[,;]/);
  for (let w of words) {
    // console.log(w);
  }
}
if (window.location.hash) propaghi(window.location.hash);
    </script>
  </body>
</html>
