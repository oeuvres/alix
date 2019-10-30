<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%
// params
String q = tools.getString("q", null);
String id = tools.getString("id", null);
String url;
if (id != null) {
  url = "doc.jsp?id="+id;
  if (q != null) url += "&amp;"+JspTools.escapeHtml(q);
}
else if (q != null) {
  url = "snip.jsp?q=" + JspTools.escapeHtml(q);
}
else {
  url = "corpus.jsp";
}
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Alix</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/vendors/split.js">//</script>
  </head>
  <body class="split">
    <header id="header">
      <span class="base"><%=props.get("title")%></span>
      <form id="qform" name="qform" onsubmit="return dispatch(this)" target="page" action="snip.jsp">
        <input id="q" name="q" autocomplete="off" autofocus="true" value="<%= JspTools.escapeHtml(q)%>"/>
        <button type="submit" name="send" tabindex="-1" class="magnify">⚲</button>
      </form>
      <div id="tabs">
        <a href="corpus.jsp" target="page" class="tab">Corpus</a>
        <a href="snip.jsp" target="page" class="tab">Résultats</a>
        <a href="doc.jsp" target="page" class="tab">Document</a>
        <a href="freqs.jsp" target="page" class="tab">Fréquences</a>
        <a href="cloud.jsp" target="page" class="tab">Nuage</a>
        <i target="page" class="tab">Concordancier</i>
      </div>
    </header>
    <div id="win">
      <div id="aside">
        <iframe id="panel" name="panel" src="facet.jsp">
        </iframe>
      </div>
      <div id="main">
        <div id="body">
          <iframe name="page" id="page" src="<%= url %>">
          </iframe>
        </div>
        <footer id="footer">
          <iframe id="chrono" name="chrono" src="chrono.jsp">
          </iframe>
         </footer>
      </div>
    </div>
    <script src="../static/js/desk.js">//</script>
  </body>
</html>
