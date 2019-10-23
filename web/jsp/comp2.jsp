<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="prelude.jsp" %>
<%!
final static HashSet<String> FIELDS = new HashSet<String>();
static {
  for (String w : new String[] {Alix.BOOKID, "bibl"}) {
    FIELDS.add(w);
  }
}
%>
<!DOCTYPE html>
<html class="comp right">
  <head>
    <meta charset="UTF-8">
    <link href="../static/vendors/teinte.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <style>
    </style>
  <%
    IndexReader reader = alix.reader();


    int refDocId = tools.get("docleft", -1);
    int start = tools.get("start", 1);
    int hitsTot = -1;
      
    int docId = -1;
    Top<String> topTerms = null;
    String[] words = null;

    Doc refDoc = null;
    if (refDocId < 0) {
      out.println("Chercher un mot dans le formulaire à gauche, ici apparaîtront les documents similaires.");
    }
    else {
      if (request.getParameter("prev") != null) {
    start = tools.get("prevn", start);
      }
      else if (request.getParameter("next") != null) {
    start = tools.get("nextn", start);
      }
      if (start < 1) start = 1;
      int hitsMax = 100;
      if (start > hitsMax) hitsMax = (int)(1.1 * start);
      
      refDoc = new Doc(alix, refDocId);
      topTerms = refDoc.theme(TEXT);
      Query query = Doc.moreLikeThis(TEXT, topTerms, 50);
      IndexSearcher searcher = alix.searcher();
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
      doc = new Doc(alix, docId);
      if (doc.fields() == null) doc = null;
    }
    String bibl = null;
    if (doc != null) {
      bibl = doc.fields().get("bibl");
      out.print("    <title>");
      out.print(Char.detag(bibl));
      out.println(" [Obvil]</title>");
    }
  %>
    <script type="text/javascript">
var winaside = parent.document.getElementById("left");
    <%
if (doc != null) out.println("var rulhiLength ="+doc.length(TEXT)+";");
// this doc is not requested from left, update left
if (tools.get("from", null) == null) out.println("showLeft("+docId+");");
    %>
function showLeft (docId) {
  if (docId < 0) return false;
  if (!winaside) return false;
  url = winaside.src.replace(/&?doc2=[^&]*/, '');
  winaside.src=url+"&doc2="+docId;
}
    </script>
  
  </head>
  <body class="comp right">
    <%
if (doc != null) {
  out.println("<header class=\"biblbar\" title=\""+doc.getUntag("bibl")+"\">");
  out.print("<a href=\"#\" class=\"bibl\">");
  out.println(doc.get("bibl"));
  out.print("</a>");
  out.println("</header>");
}
  %>
    <nav id="rulhi" class="left">
    </nav>
    <main  class="left">
<% if (bibl != null) { %>
      <form id="qform" action="#">
        <input type="submit" 
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <input type="hidden" name="q" value="<%=q%>"/>
        <input type="hidden" name="docleft" value="<%=refDocId%>"/>
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
int max = 50;
if (bibl != null) {
  out.println("<div class=\"heading\">");
  out.println(bibl);
  out.println("</div>");
  /*
  out.println("<p class=\"keywords\">");
  out.println("<b>Mots clés</b> : ");
  boolean first = true;
  int i = 0;
  for (Top.Entry<String> entry: topTerms) {
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
  out.append("<p><b>Contrasté avec : </b>"+refDoc.fields().get("bibl")+"</p>");

  out.println("<article class=\"content\" id=\"contrast\">");
  out.println(doc.contrast(TEXT, refDocId, true));
  out.println("</article>");
}
    %>
    </main>
    <% out.println("<!-- time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\" -->"); %>
    <script src="../static/js/doc.js">//</script>
  </body>
</html>
