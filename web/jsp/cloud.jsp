<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%!
private String options(int[] values, int value) {
  StringBuilder sb = new StringBuilder();
  for (int i = 0, lim = values.length; i < lim; i++) {
    sb.append("<option");
    if (value == values[i]) sb.append(" selected=\"selected\"");
    sb.append(">");
    sb.append(values[i]);
    sb.append("</option>\n");
  }
  return sb.toString();
}

%>
<%
final String q = tools.getString("q", null);
WordClass cat = (WordClass)tools.getEnum("cat", WordClass.NOSTOP, Cookies.wordClass);
final int count = tools.getInt("count", 500, Cookies.count);
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Nuage de mots</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/js/common.js">//</script>
    <script>var count = <%= count %>;</script>
  </head>
  <body class="cloud">
    <form id="filter">
       <select name="count" onchange="this.form.submit()">
       <option/>
       <%= options(new int[]{30, 50, 100, 200, 500, 100}, count) %>
       </select>

       <select name="cat" onchange="this.form.submit()">
          <option/>
          <%= options(cat) %>
       </select>
       <input type="hidden" name="q" value="<%=Jsp.escape(q)%>"/>
    </form>
    <div id="wordcloud2"></div>
    <script src="../static/vendor/wordcloud2.js">//</script>
    <script src="../static/js/cloud.js">//</script>
</body>
</html>
