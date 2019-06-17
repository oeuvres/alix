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
// if (q == null || q.trim() == "") q = "théâtre acteur ; lettres ; littérature ; poésie poème ; roman";
String sort = request.getParameter("sort");
      %>
      <form id="qform">
        <input id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
        <label>
         Tri
          <select name="sort" onchange="this.form.submit()">
            <option value="">Pertinence (BM25)</option>
            <%
String[] value = {
  "length", "year", "year-inv", 
  "tf-idf", "dfi_chi2", "dfi_std", "dfi_sat", 
  "lmd", "lmd0.1", "lmd0.7", "dfr", "ib"
};
String[] label = {
  "taille", "Année (+ ancien)", "Année (+ récent)", 
  "Pertinence (tf-idf)", "Pertinence (DFI chi²)", "Pertinence (DFI standard)", "Pertinence (DFI saturé)", 
  "LMD", "LMD λ=0.1", "LMD λ=0.7", "DFR", "IB"
};
for (int i = 0, length = value.length; i < length; i++) {
  out.print("<option");
  if (value[i].equals(sort)) out.print(" selected=\"selected\"");
  out.print(" value=\"");
  out.print(value[i]);
  out.print("\">");
  out.print(label[i]);
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
if ("dfi_chi2".equals(sort)) similarity = new DFISimilarity(new IndependenceChiSquared());
else if ("dfi_std".equals(sort)) similarity = new DFISimilarity(new IndependenceStandardized());
else if ("dfi_sat".equals(sort)) similarity = new DFISimilarity(new IndependenceSaturated());
else if ("tf-idf".equals(sort)) similarity = new ClassicSimilarity();
else if ("lmd".equals(sort)) similarity = new LMDirichletSimilarity();
else if ("lmd0.1".equals(sort)) similarity = new LMJelinekMercerSimilarity(0.1f);
else if ("lmd0.7".equals(sort)) similarity = new LMJelinekMercerSimilarity(0.7f);
else if ("dfr".equals(sort)) similarity = new DFRSimilarity(new BasicModelG(), new AfterEffectB(), new NormalizationH1());
else if ("ib".equals(sort)) similarity = new IBSimilarity(new DistributionLL(), new LambdaDF(), new NormalizationH3());
  
  
if (similarity != null) searcher.setSimilarity(similarity);

String fieldName = TEXT;
Query query;
if (q == null || q.trim() == "") query = new MatchAllDocsQuery();
else query = Alix.qParse(q, fieldName);

TopDocs topDocs;
if ("year".equals(sort)) {
  topDocs = searcher.search(query, 100, new Sort(new SortField(YEAR, SortField.Type.INT)));
}
else if ("year-inv".equals(sort)) {
  topDocs = searcher.search(query, 100, new Sort(new SortField(YEAR, SortField.Type.INT, true)));
}
else if ("length".equals(sort)) {
  topDocs = searcher.search(query, 100, new Sort(new SortField(TEXT, SortField.Type.INT)));
}
else {
  topDocs = searcher.search(query, 100);
}


ScoreDoc[] hits = topDocs.scoreDocs;



UnifiedHighlighter uHiliter = new UnifiedHighlighter(searcher, Alix.qAnalyzer);
uHiliter.setFormatter(new  HiliteFormatter());
String[] fragments = uHiliter.highlight(fieldName, query, topDocs, 3);

for (int i = 0; i < hits.length; i++) {
  int docId = hits[i].doc;
  Document document = searcher.doc(docId);
  out.println("<article class=\"hit\">");
  // hits[i].doc
  out.println("  <div class=\"bibl\">");
  // test if null ?
  out.println("<a target=\"_blank\" href=\"doc.jsp?doc="+docId+"&q="+q+"\">");
  out.println(document.get("bibl"));
  out.println("</a>");
  out.println("  </div>");
  out.print("<p class=\"frags\">");
  out.println(fragments[i]);
  out.println("</p>");
  /*
  out.println("<small>");
  out.println(document.get(Alix.FILENAME));
  out.println("</small>");
  */
  out.println("</article>");
}


    %>
    </div>
    <% out.println("time : " + (System.nanoTime() - time) / 1000000.0 + " ms "); %>  </body>
</html>
