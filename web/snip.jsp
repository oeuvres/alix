<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Alix, test</title>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="results">
    <div id="results">
      <%
String q = request.getParameter("q");
if (q == null || q.trim() == "") q = "théâtre acteur ; lettres ; littérature ; poésie poème ; roman";
String sim = request.getParameter("sim");
if (sim == null) sim = "";
      %>
      <form id="qform">
        <input id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
        <label>
         Tri
          <select name="sim" onchange="this.form.submit()">
            <option value="">Défaut</option>
            <%
String[] sims = {"bm25", "dfi_chi2", "dfi_std", "dfi_sat", "tf-idf"};
for (String s: sims) {
  out.print("<option");
  if (s.equals(sim)) out.print(" selected=\"selected\"");
  out.print(">");
  out.print(s);
  out.println("</option>");
}
            %>
          </select>
        </label>
      </form>
    
    <%
// renew seracher for this experiment on similarity
IndexSearcher searcher = lucene.searcher(true);
Similarity similarity = null;
switch(sim) {
  case "dfi_chi2":
    similarity = new DFISimilarity(new IndependenceChiSquared());
    break;
  case "dfi_std":
    similarity = new DFISimilarity(new IndependenceStandardized());
    break;
  case "dfi_sat":
    similarity = new DFISimilarity(new IndependenceSaturated());
    break;
  case "tf-idf":
    similarity = new ClassicSimilarity();
}
if (similarity != null) searcher.setSimilarity(similarity);

String fieldName = TEXT;
Query query = Alix.qParse(q, fieldName);





TopDocs topDocs = searcher.search(query, 100);
ScoreDoc[] hits = topDocs.scoreDocs;



UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, Alix.qAnalyzer);
uHiliter.setFormatter(new  HiliteFormatter());
String[] fragments = uHiliter.highlight(fieldName, query, topDocs, 3);
// TODO, get matching occurences coumt

String value;
for (int i = 0; i < hits.length; i++) {
  int docId = hits[i].doc;
  Document document = searcher.doc(docId);
  out.println("<article class=\"hit\">");
  // hits[i].doc
  out.println("  <div class=\"bibl\">");
  // test if null ?
  out.println("<a href=\"doc.jsp?doc="+docId+"\">");
  value = document.get("bibl");
  out.println(value);
  out.println("</a>");
  out.println("  </div>");
  out.print("<p class=\"frags\">");
  out.println(fragments[i]);
  out.println("</p>");
  /*
  out.println("<small>");
  out.println(searcher.explain(query, docId));
  out.println("</small>");
  */
  out.println("</article>");
}


    %>
    </div>
    <% out.println("time : " + (System.nanoTime() - time) / 1000000.0 + " ms "); %>  </body>
</html>
