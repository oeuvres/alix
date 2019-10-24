<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="prelude.jsp" %>
<%!
final static HashSet<String> DOC_SHORT = new HashSet<String>(Arrays.asList(new String[] {Alix.ID, Alix.BOOKID, "bibl"}));

%>
<%
int docId = tools.getInt("docid", -1);
int docId2 = tools.getInt("doc2", -1);
String id = tools.getString("id", null);
String sort = tools.getString("sort", null);
int start = tools.getInt("start", 1);
if (request.getParameter("prev") != null) {
  start = tools.getInt("prevn", start);
}
else if (request.getParameter("next") != null) {
  start = tools.getInt("nextn", start);
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
  if (doc.fields() == null) doc = null;
}

// declare some variables to update if doc found
String bibl = null;
Top<String> top = null;
boolean first = true;
Query query;
TopDocs results;
int docSim = -1;

%>
<!DOCTYPE html>
<html class="comp">
  <head>
    <meta charset="UTF-8">
    <title><%
if (doc != null) {
  out.print(doc.getUntag("bibl"));
}
    %> [Obvil]</title>
    <link href="../static/vendors/teinte.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <style>
mark.ADV { font-weight: normal; background: #FEC; }
mark.ADJ { font-weight: normal; background: #CEF; }
mark.NAME { color: red; }

    </style>
  <%



if (doc != null) {
  // fill topTerms for below
  top = doc.theme(TEXT);
  // no docId given to contrast with, serach with one
  if (docId2 < 0) {
    query = Doc.moreLikeThis(TEXT, top, 50);
    results = searcher.search(query, 2);
    ScoreDoc[] hits = results.scoreDocs;
    docSim = hits[1].doc;
  } else {
    docSim = docId2;
  }
}
  %>
  <script type="text/javascript">
var winaside = parent.document.getElementById("right");
    <% 
if (doc != null) out.println("var rulhiLength ="+doc.length(TEXT)+";");
if (docId2 < 0) out.println("showRight("+docId+");");
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
if (doc != null) {
  out.println("<header class=\"biblbar\" title=\""+doc.getUntag("bibl")+"\">");
  out.print("<a href=\"#\" class=\"bibl\">");
  out.println(doc.get("bibl"));
  out.print("</a>");
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
        <input id="q" name="q" value="<%=JspTools.escapeHtml(q)%>" autocomplete="off"/>
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
  Document fields = reader.document(docSim, DOC_SHORT);
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
