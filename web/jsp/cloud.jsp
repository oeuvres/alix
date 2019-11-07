<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%
final String q = tools.getString("q", null);
final String sorter = tools.getString("sorter", "score", "freqSorter");

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Nuage de mots</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/js/common.js">//</script>
  </head>
  <body class="cloud">
    <form id="filter">
       <select name="sorter" onchange="this.form.submit()">
          <option/>
          <%= posOptions(sorter) %>
       </select>
       <input type="hidden" name="q" value="<%=Jsp.escape(q)%>"/>
    </form>
    <div id="wordcloud2"></div>
    <script src="../static/vendors/wordcloud2.js">//</script>
    <script src="../static/js/cloud.js">//</script>
</body>
</html>
