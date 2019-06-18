<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8"%>
<%@include file="data/common.jsp" %>
<%
  // choose a field
String facet = "author";
String q = request.getParameter("q");
if (q == null) q = "";
else q = q.trim();


%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Facettes</title>
    <style>
html, body {height: 100%; background-color: #f3f2ec; margin: 0; padding:0;}
body {font-family: sans-serif; }
* {box-sizing: border-box;}
    </style>
  </head>
  <body>
    <form id="qform">
      <input id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
      Entre
      <input id="start" name="start" size="4" value="<%=(start > -1)?start:""%>"/>
      et
      <input id="end" name="end" size="4" value="<%=(end > -1)?end:""%>"/>
      <button>▶</button>
    </form>
    <ul>
    <%
TermList terms = lucene.qTerms(q, TEXT);
// needs the bits of th filter
QueryBits filter = null;
if (filterQuery != null) filter = new QueryBits(filterQuery);

FacetResult results = lucene.facet(facet, TEXT, filter, terms, null);
while (results.hasNext()) {
  results.next();
  out.println("<li>"+results.term()+" ("+results.weight()+")</li>");
}
    %>
    </ul>
    <% out.println("  \"time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\""); %>
  </body>
</html>