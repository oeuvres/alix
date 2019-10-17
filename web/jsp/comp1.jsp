<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%!
final static HashSet<String> FIELDS = new HashSet<String>();
static {
  for (String w : new String[] {Alix.BOOKID, "bibl"}) {
    FIELDS.add(w);
  }
}
public String results(TopDocs docs, IndexReader reader, int docSrc) throws  IOException
{
  StringBuilder out = new StringBuilder();
  ScoreDoc[] hits = docs.scoreDocs;
  for (int i = 0, len = hits.length; i < len; i++) {
    int docId = hits[i].doc;
    if (docSrc == docId) continue;
    Document doc = reader.document(docId, FIELDS);
    out.append("<li>");
    out.append("<a href=\"?docid="+docId+"\">");
    out.append(doc.get("bibl"));
    out.append("</a>");
    out.append("</li>");
  }
  return out.toString();
}
%>
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


int docId = getParameter(request, "docid", -1);
String id = getParameter(request, "id", null);
String sort = getParameter(request, "sort", null);
int start = getParameter(request, "start", 1);
if (request.getParameter("prev") != null) {
  start = getParameter(request, "prevn", start);
}
else if (request.getParameter("next") != null) {
  start = getParameter(request, "nextn", start);
}
IndexReader reader = alix.reader();
IndexSearcher searcher = alix.searcher();
TopDocs topDocs = null;
if (id != null) {
  TermQuery qid = new TermQuery(new Term(Alix.ID, id));
  TopDocs search = searcher.search(qid, 1);
  ScoreDoc[] hits = search.scoreDocs;
  if (hits.length > 0) {
    docId = hits[0].doc;
  }
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
  <script type="text/javascript">
    var docsim = <%=docId%>;
    if (docsim > 0) {
      var target = parent.document.getElementById('target');
      target.src="comp2.jsp?docsim="+docsim+"&start=2";
    }
  
  </script>
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
      <form id="qform" action="#">
        <input type="submit" 
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <input id="q" name="q" value="<%=escapeHtml(q)%>" autocomplete="off"/>
        <br/>
        <input type="hidden" name="docid" value="<%=docId%>"/>
        <% 
        if (topDocs != null && start > 1) {
          out.println("<input type=\"hidden\" name=\"prevn\" value=\""+(start - 1)+"\"/>");
          out.println("<button type=\"submit\" name=\"prev\">◀</button>");
        }

        %>
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
  out.println(bibl);
  out.println("</div>");

  Top<String> top;
  boolean first = true;
  Query query;
  TopDocs results;

  Keywords keywords = new Keywords (alix, TEXT, docId);
  out.println("<p class=\"keywords\">");
  top = keywords.names();
  out.println("<b>Noms cités</b> : ");
  first = true;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else out.println(", ");
    String word = entry.value();
    out.print(word);
  }
  out.println(".</p>");

  String[] words = top.toArray();
  out.println(doc.hilite(TEXT, words));

  
  /*
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
    out.print("<mark class=\""+Tag.label(Tag.group(tag))+"\">");
    out.print(text.substring(offStart, offEnd));
    out.print("</mark>");
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
