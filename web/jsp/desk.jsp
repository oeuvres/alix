<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%
  // params
String q = tools.getString("q", null);
String id = tools.getString("id", null);
String url;
if (id != null) {
  url = "doc.jsp?id="+id;
  if (q != null) url += "&amp;"+Jsp.escapeHtml(q);
}
else if (q != null) {
  url = "snip.jsp?q=" + Jsp.escapeHtml(q);
}
else {
  url = "corpus.jsp";
}


%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>[Obvil] <%=props.get("title")%></title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/vendors/split.js">//</script>
  </head>
  <body class="split">
    <header id="header">
      <span class="base"><%=props.get("title")%></span>
      <form id="qform" name="qform" onsubmit="return dispatch(this)" target="page" action="snip.jsp">
        <input id="q" name="q" autocomplete="off" autofocus="true" value="<%=Jsp.escapeHtml(q)%>"/>
        <button type="submit" name="send" tabindex="-1" class="magnify">⚲</button>
      </form>
      <a class="logo" href="."><img alt="Obvil app" src="../static/img/obvil_50.png"/></a>
      <div id="tabs">
        <a href="corpus" target="page" class="tab">Corpus</a>
        <a href="snip" target="page" class="tab">Résultats</a>
        <a href="doc" target="page" class="tab">Document</a>
        <a href="freqs" target="page" class="tab">Fréquences</a>
        <a href="cloud" target="page" class="tab">Nuage</a>
        <i href="kwic" target="page" class="tab">Concordancier</i>
      </div>
    </header>
    <div id="win">
      <div id="aside">
        <iframe id="panel" name="panel" src="facet">
        </iframe>
      </div>
      <div id="main">
        <div id="body">
          <iframe name="page" id="page" src="<%= url %>">
          </iframe>
        </div>
        <footer id="footer">
          <iframe id="chrono" name="chrono" src="chrono">
          </iframe>
         </footer>
      </div>
    </div>
    <script src="../static/js/desk.js">//</script>
  </body>
</html>
