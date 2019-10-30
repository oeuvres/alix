<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%
// parameters
String id1 = tools.getString("leftid", null);
int docId1 = tools.getInt("leftdocid", -1);
String id2 = tools.getString("rightid", null);
int docId2 = tools.getInt("rightdocid", -1);
String q = tools.getString("q", null);

String url1;
String ref = "";
if (id1 != null) { // doc by id requested
  url1 = "compdoc?" + "id=" + id1;
  ref = "&amp;refid=" + id1;
}
else if (docId1 >= 0) { // doc by docid requested
  url1 = "compdoc?" + "docid=" + docId1;
  ref = "&amp;refdocid=" + docId1;
}
else if (q != null) { // query
  url1 = "complist?" + "q=" + q;
}
else { // query
  url1 = "complist";
}

String url2;
if (id2 != null) { // doc by id requested
  url2 = "compdoc?" + "id=" + id2 + ref;
}
else if (docId2 >= 0) { // doc by docid requested
  url2 = "compdoc?" + "docid=" + docId2 + ref;
}
else if (id1 != null) { // reference document for list or hilite
  url2 = "complist?refid=" + id1;
}
else if (docId1 >= 0) { // reference document for list or hilite
  url2 = "complist?refdocid=" + docId1;
}
else { // help
  url2 = "help/comparer";
}


%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Comparer, <%=baseTitle %> [Obvil]</title>
    <style>
body, html {
  height: 100%;
  margin: 0;
  padding: 0;
}
#cont {
  position: relative;
  background-color: red;
  height: 100%;
}
iframe {
  border: none;
  margin: 0;
  padding: 0;
  width: 50%;
  top: 0px;
  bottom: 0px;
  height: 100%;
  position: absolute;
}
#left {
}

#right {
  right: 0 ;
}
    </style>
  </head>
  <body>
    <iframe id="left" name="left" src="<%=url1%>">
    </iframe>
    <iframe id="right" name="right" src="<%=url2%>">
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
