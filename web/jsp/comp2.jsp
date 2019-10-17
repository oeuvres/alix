<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <link href="../static/vendors/teinte.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <style>
mark.ADV { font-weight: normal; background: #FEC; }
mark.ADJ { font-weight: normal; background: #CEF; }
mark.NAME { color: red; }
    </style>
  <%
/**
 * display a doc from the index.
 * Different case
 *  — direct read of a docid
 *  — find a doc by id field
 *  — query with an index order
 */

int docSim = getParameter(request, "docsim", -1);
int start = getParameter(request, "start", 1);
int hitsTot = -1;
  
int docId = -1;
Top<String> topTerms = null;
String[] words = null;

IndexReader reader = alix.reader();
if (docSim < 0) {
  out.println("Chercher un mot dans le formulaire à gauche, ici apparaîtront les documents similaires.");
}
else {
  if (request.getParameter("prev") != null) {
    start = getParameter(request, "prevn", start);
  }
  else if (request.getParameter("next") != null) {
    start = getParameter(request, "nextn", start);
  }
  if (start < 1) start = 1;
  int hitsMax = 100;
  if (start > hitsMax) hitsMax = (int)(1.1 * start);
  
  IndexSearcher searcher = alix.searcher();
  
  Keywords keywords = new Keywords (alix, TEXT, docSim);
  topTerms = keywords.names();
  words = topTerms.toArray();

  Query query = keywords.query(topTerms, 50, true);
  TopDocs results = searcher.search(query, hitsMax);
  hitsTot = (int)results.totalHits.value;
  if (hitsTot == 0) {
    start = 0;
  }
  else if (hitsTot < start) {
    start = hitsTot;
  }
  if (start > 0) {
    ScoreDoc res = results.scoreDocs[start-1];
    docId = res.doc;
  }
}

Doc doc = null;
if (docId >= 0) {
  doc = new Doc(reader, docId);
  if (doc.document() == null) doc = null;
}
String bibl = null;
if (doc != null) {
  bibl = doc.document().get("bibl");
  out.print("    <title>");
  out.print(Char.unTag(bibl));
  out.println(" [Obvil]</title>");
}
  %>
  </head>
  <body class="document">
    <%
// Shall we add prev/next navigation ?
if (bibl != null) {
  out.println("<header class=\"bibl\">");
  out.println("<table class=\"prevnext\"><tr>");
  /*
  out.println("<td class=\"prev\">");
  out.println("<a class=\"but prev\">◀</a>");
  out.println("</td>");
  */
  out.println("<td class=\"bibl\" title=\""+detag(bibl)+"\">");
  out.print("<a href=\"#\" class=\"bibl\">");
  out.println(bibl);
  out.print("</a>");
  out.println("</td>");
  /*
  out.println("<td class=\"next\">");
  out.println("<a class=\"but next\">▶</a>");
  out.println("</td>");
  */
  out.println("</tr></table>");
  out.println("</header>");
}
  %>
    <main>
    
<% if (bibl != null) { %>
      <form id="qform" action="#">
        <b>Noms cités : </b>
        <% 
boolean first = true;
int size = 8;
for (String t: words) {
  if (first) first = false;
  else out.println(", ");
  out.print(t);
  if (--size <= 0) {
    out.println(", …");
    break;
  }
}
        %>
        <br/>
        <input type="submit" 
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <input type="hidden" name="q" value="<%=q%>"/>
        <input type="hidden" name="docsim" value="<%=docSim%>"/>
        <% 
        if (start > 1) {
          out.println("<button type=\"submit\" name=\"prev\">◀</button>");
          out.println("<input type=\"hidden\" name=\"prevn\" value=\""+(start - 1)+"\"/>");
        }

        %>
        <input id="start" name="start" value="<%=start%>" autocomplete="off" size="1"/>
               <% 
        out.println("<span class=\"hits\"> / "+ hitsTot  + "</span>");
        if (start < hitsTot) {
          out.println("<input type=\"hidden\" name=\"nextn\" value=\""+(start + 1)+"\"/>");
          out.println("<button type=\"submit\" name=\"next\">▶</button>");
        }
        %>
      </form>

  <%
}
if (bibl != null) {
  out.println("<div class=\"heading\">");
  out.println(bibl);
  out.println("</div>");

  out.println(doc.hilite(TEXT, words));
  /*
  // get keywords from 
  Keywords keywords = new Keywords (alix, TEXT, docSim);
  Top<String> top = keywords.names();



  String text = document.get(TEXT);
  BinaryUbytes tags = new BinaryUbytes();
  tags.open(document.getBinaryValue(TEXT+Alix._TAGS));
  Offsets offsets = new Offsets();
  offsets.open(document.getBinaryValue(TEXT+Alix._OFFSETS));
  
  
  TagFilter tagFilter = new TagFilter();
  tagFilter.setName();
  tagFilter.setAdj();
  tagFilter.set(Tag.ADV);
  
  // hilie
  int off = 0;
  for (int pos = 0, size = offsets.size(); pos < size; pos++) {
    int tag = tags.get(pos);
    if (!tagFilter.accept(tag)) continue;
    int offStart = offsets.getStart(pos);
    int offEnd = offsets.getEnd(pos);
    out.print(text.substring(off, offStart));
    out.print("\n<mark class=\""+Tag.label(Tag.group(tag))+"\">");
    out.print(text.substring(offStart, offEnd));
    out.print("</mark>\n");
    off = offEnd;
  }
  out.print(text.substring(off));
  */
}
    %>
    </main>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
    <script src="../static/js/doc.js">//</script>
  </body>
</html>
