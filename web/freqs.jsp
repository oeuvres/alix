<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<%!
final static DecimalFormat dfScoreFr = new DecimalFormat("0.000", frsyms);

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body>
    <table class="sortable" align="center">
      <thead>
        <tr>
          <th>Nᵒ</th>
          <th>Mot</th>
          <th>Catégorie</th>
          <th>Chapitres</th>
          <th>Occurrences</th>
          <th>Score</th>
        <tr>
      </thead>
      <tbody>
    <%
IndexSearcher searcher = alix.searcher();

String textField = TEXT;

String sc = getParameter(request, "scorer", "bm25");
Scorer scorer; 
if ("tfidf".equals(sc)) scorer = new ScorerTfidf();
else if ("tf".equals(sc)) scorer = new ScorerTf();
else if ("occs".equals(sc)) scorer = new ScorerOccs();
else scorer = new ScorerBM25();
Freqs freqs = alix.freqs(textField);
int no = 1;
int max = 500;
CharsAtt term = new CharsAtt();
Tag tag;
TopTerms terms = freqs.topTerms(filter, scorer);
while (terms.hasNext()) {
  terms.next();
  terms.term(term);
  // filter some unuseful words
  // if (STOPLIST.contains(term)) continue;
  LexEntry entry = CharsMaps.word(term);
  if (entry != null) {
    tag = new Tag(entry.tag);
  }
  else if (Char.isUpperCase(term.charAt(0))) {
    tag = new Tag(Tag.NAME);
  }
  else {
    tag = new Tag(0);
  }
  out.println("  <tr>");
  out.print("    <td class=\"num\">");
  out.print(no) ;
  out.println("</td>");
  String t = terms.term().toString().replace('_', ' ');
  out.print("    <td><a target=\"_top\" href=\".?q="+t+"\">");
  out.print(t);
  out.println("</a></td>");
  out.print("    <td>");
  out.print(tag) ;
  out.println("</td>");
  out.print("    <td class=\"num\">");
  out.print(terms.docs()) ;
  out.println("</td>");
  out.print("    <td class=\"num\">");
  out.print(terms.length()) ;
  out.println("</td>");
  out.print("    <td class=\"num\">");
  out.print(dfScoreFr.format(terms.score())) ;
  out.println("</td>");
  out.println("  </tr>");
  if (no >= max) break;
  no++;
}
    %>
      </tbody>
    </table>
    <script src="vendors/Sortable.js">//</script>
    <script src="static/js/freqs.js">//</script>
  </body>
</html>
