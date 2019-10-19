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
<html class="comp">
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
int doc2 = getParameter(request, "doc2", -1);
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
  doc = new Doc(alix, docId);
  if (doc.document() == null) doc = null;
}

// declare some variables to update if doc found
String bibl = null;
Top<String> top = null;
boolean first = true;
Query query;
TopDocs results;
int docSim = -1;


if (doc != null) {
  bibl = doc.document().get("bibl");
  out.print("    <title>");
  out.print(Char.unTag(bibl));
  out.println(" [Obvil]</title>");
  Keywords keywords = new Keywords (alix, TEXT, docId);
  // fill topTerms for below
  top = keywords.theme();
  // no docId given to contrast with, serach with one
  if (doc2 < 0) {
    query = keywords.query(top, 50, true);
    results = searcher.search(query, 2);
    ScoreDoc[] hits = results.scoreDocs;
    docSim = hits[1].doc;
  } else {
    docSim = doc2;
  }
}
  %>
  <script type="text/javascript">
var winaside = parent.document.getElementById("right");
    <% 
if (doc != null) out.println("var rulhiLength ="+doc.length(TEXT)+";");
if (doc2 < 0) out.println("showRight("+docId+");");
    %>
function showRight (docId) {
  if (docId < 0) return false;
  if (!winaside) return false;
  var url = "comp2.jsp?from=left&start=2&docleft="+docId;
  winaside.src=url;
}

  </script>
  </head>
  <body class="comp left">
    <%
// Shall we add prev/next navigation ?
if (bibl != null) {
  out.println("<header class=\"biblbar\">");
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
    <nav id="rulhi" class="right">
    </nav>
    <main class="right">
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


  int max = 50;
  /*
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
  */
  /*
  out.println("<p class=\"keywords\">");
  out.println("<b>Mots clés</b> : ");
  first = true;
  int i = 0;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else out.println(", ");
    String word = entry.value();
    out.print("<a href=\"?q="+word+"\">"+word+"</a>");
    // out.print(" ("+entry.score()+")");
    if (entry.score() <= 0) break;
    if (++i >= max) break;
  }
  out.println(".</p>");
  */
  Document fields = reader.document(docSim, FIELDS);
  out.append("<p><b>Contrasté avec : </b>"+fields.get("bibl")+"</p>");
}
    %>
      <ul class="legend">
        <li>Plus un mot est <b class="em9">gras</b>, plus il est fréquent.</li>
        <li><a class="tokspec em3">Mot spécifique</a> à ce document.</li>
        <li><a class="tokshared em3">Mot partagé</a> entre les deux documents.</li>
        <li><a class="tokhover">Mot au survol</a>, cliquer surligne toutes les occurences.</li>
        <li><a class="tokhi">Mot surligné</a>, cliquer supprimme tous les surlignements.</li>
      </ul>
      <article class="content" id="contrast">
        <% if (doc!= null) out.println(doc.contrast(TEXT, docSim)); %>
      </article>
    </main>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
    <script src="../static/js/doc.js">//</script>
  </body>
</html>
