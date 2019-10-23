<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="prelude.jsp" %>
<%!
public String results(TopDocs docs, IndexReader reader, int docSrc) throws  IOException
{
  StringBuilder out = new StringBuilder();
  ScoreDoc[] hits = docs.scoreDocs;
  for (int i = 0, len = hits.length; i < len; i++) {
    int docId = hits[i].doc;
    if (docSrc == docId) continue;
    Document doc = reader.document(docId, DOC_SHORT);
    out.append("<li>");
    out.append("<a href=\"?docid="+docId+"\">");
    out.append(doc.get("bibl"));
    out.append("</a>");
    out.append("</li>\n");
  }
  return out.toString();
}
%>
<%
/**
 * display a doc from the index.
 * Different case
 *  — direct read of a docid
 *  — find a doc by id field
 *  — query with an index order
 */


Doc doc = null;
int docId = tools.get("docid", -1);
String id = tools.get("id", null);
String sort = tools.get("sort", null);
int start = tools.get("start", 1);
if (request.getParameter("prev") != null) {
  start = tools.get("prevn", start);
}
else if (request.getParameter("next") != null) {
  start = tools.get("nextn", start);
}
IndexReader reader = alix.reader();
IndexSearcher searcher = alix.searcher();
TopDocs topDocs = null;
if (id != null) {
  doc = new Doc(alix, id);
}
else if (docId >= 0) {
  doc = new Doc(alix, docId);
}
else if (!"".equals(q)) {
  time = System.nanoTime();
  topDocs = getTopDocs(pageContext, alix, corpus, q, sort);
  ScoreDoc[] hits = topDocs.scoreDocs;
  if (hits.length == 0) {
    start = 0;
  }
  else {
    if (start < 1 || (start - 1) >= hits.length) start = 1;
    docId = hits[start - 1].doc;
    doc = new Doc(alix, docId);
  }
}
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <link href="../static/vendors/teinte.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <title><%
if (doc != null) {
  out.print(doc.getUntag("bibl"));
}
    %> [Obvil]</title>

  </head>
  <body class="document">
  <%
if (doc != null) {
  out.println("<header class=\"biblbar\" title=\""+doc.getUntag("bibl")+"\">");
  out.print("<a href=\"#\" class=\"bibl\">");
  out.println(doc.get("bibl"));
  out.print("</a>");
  out.println("</header>");
}
  %>
  <main>
      <form id="qform" action="#">
        <input type="submit" 
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <input type="hidden" name="docid" value="<%=docId%>"/>
        <% 
        if (topDocs != null && start > 1) {
          out.println("<input type=\"hidden\" name=\"prevn\" value=\""+(start - 1)+"\"/>");
          out.println("<button type=\"submit\" name=\"prev\">◀</button>");
        }

        %>
        <input id="q" name="q" value="<%=JspTools.escapeHtml(q)%>" autocomplete="off" type="hidden"/>
        <select name="sort" onchange="this.form.submit()" title="Ordre">
            <option>Pertinence</option>
            <% sortOptions(out, sort); %>
        </select>
        <input id="start" name="start" value="<%=start%>" autocomplete="off" size="1"/>
               <% 
        if (topDocs != null) {
          long max = topDocs.totalHits.value;
          out.println("<span class=\"hits\"> / "+ max  + "</span>");
          if (start < max) {
            out.println("<input type=\"hidden\" name=\"nextn\" value=\""+(start + 1)+"\"/>");
            out.println("<button type=\"submit\" name=\"next\">▶</button>");
          }
        }
        %>
      </form>
<%

if (doc != null) {
  out.println("<div class=\"heading\">");
  out.println(doc.get("bibl"));
  out.println("</div>");
  
  Top<String> top;
  boolean first;
  Query query;
  TopDocs results;
  
  int max;

  out.println("<p class=\"keywords\">");
  out.println("<b>Mots clés</b> : ");
  top = doc.theme(TEXT);
  max = 50;
  first = true;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else out.println(", ");
    String word = entry.value();
    out.print("<a href=\"?q="+word+"\">"+word+"</a>");
    // out.print(" ("+entry.score()+")");
    if (entry.score() <= 0) break;
    if (--max <= 0) break;
  }
  out.println(".</p>");

  query = Doc.moreLikeThis(TEXT, top, 50);
  results = searcher.search(query, 11);
  out.println("<details>");
  out.println("<summary>Chapitres avec ces mots</summary>");
  out.println("<ul>");
  out.println(results(results, reader, docId));
  out.println("</ul>");
  out.println("</details>");
  
  out.println("<p class=\"keywords\">");
  top = doc.names(TEXT);
  out.println("<b>Noms cités</b> : ");
  first = true;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else out.println(", ");
    String word = entry.value();
    out.print("<a href=\"?q="+word+"\">");
    out.print(word);
    out.print("</a>");
  }
  out.println(".</p>");
  
  query = Doc.moreLikeThis(TEXT, top, 50);
  results = searcher.search(query, 11);
  out.println("<details>");
  out.println("<summary>Chapitres avec ces noms</summary>");
  out.println("<ul>");
  out.println(results(results, reader, docId));
  out.println("</ul>");
  out.println("</details>");

  top = doc.happax(TEXT);
  if (top.length() > 0) {
    out.println("<p>");
    out.println("<b>Happax</b> : ");
    first = true;
    for (Top.Entry<String> entry: top) {
      if (first) first = false;
      else out.println(", ");
      String word = entry.value();
      out.print(word);
    }
    out.println(".</p>");
    
  }
  

  // hilie
  if (!"".equals(q)) {
    String[] terms = alix.qTerms(q, TEXT).toArray();
    out.print(doc.hilite(TEXT, terms));
  }
  else {
    out.print(doc.get(TEXT));
  }
}
    %>
    </main>
    <script src="../static/js/doc.js">//</script>
  </body>
</html>
