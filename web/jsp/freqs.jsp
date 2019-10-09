<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%!
final static DecimalFormat dfScoreFr = new DecimalFormat("0.000", frsyms);

%>
<%
IndexSearcher searcher = alix.searcher();

String field = TEXT;

String sorter = getParameter(request, "sorter", "score", session);
int left = getParameter(request, "left", 5, session);
if (left < 0) left = 0;
else if (left > 10) left = 10;
int right = getParameter(request, "right", 5, session);
if (right < 0) right = 0;
else if (right > 10) right = 10;

TopTerms dic;
if ("".equals(q)) {
  Freqs freqs = alix.freqs(field);
  dic = freqs.topTerms(filter);
  if ("score".equals(sorter)) dic.sort(dic.getScores());
  else dic.sort(dic.getOccs());
}
else {
  Cooc cooc = alix.cooc(field);
  TermList terms = alix.qTerms(q, TEXT);
  dic = cooc.topTerms(terms, left, right, filter);
  dic.sort(dic.getOccs());
}
int max = Math.min(500, dic.size());

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <link href="../static/alix.css" rel="stylesheet"/>
  </head>
  <body>
    <table class="sortable" align="center">
      <caption>
        <form id="sortForm">
        <input type="submit" 
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
             <%
if (corpus != null) {
  out.println("<i>"+corpus.name()+"</i>");
}

if ("".equals(q)) {
  // out.println(max+" termes");
}
else {
  out.println("&lt;<input style=\"width: 2em;\" name=\"left\" value=\""+left+"\"/>");
  out.print(q);
  out.println("<input style=\"width: 2em;\" name=\"right\" value=\""+right+"\"/>&gt;");
  out.println("<input type=\"hidden\" name=\"q\" value=\""+q+"\"/>");
}

out.println("<select name=\"sorter\" onchange=\"this.form.submit()\">");
termOptions(out, sorter);
out.println("</select>");

      %>
        </form>
      </caption>
      <thead>
        <tr>
    <%
out.println("<th>Nᵒ</th>");
out.println("<th>Mot</th>");
out.println("<th>Type</th>");
out.println("<th>Chapitres</th>");
out.println("<th>Occurrences</th>");
if ("".equals(q)) {
  out.println("<th>Score</th>");
}
          %>
        <tr>
      </thead>
      <tbody>
    <%
int no = 1;
CharsAtt term = new CharsAtt();
Tag tag;
while (dic.hasNext()) {
  dic.next();
  dic.term(term);
  if (term.isEmpty()) continue; // empty position
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
  // filtering
  if ("nostop".equals(sorter) && CharsMaps.isStop(term)) continue;
  else if ("adj".equals(sorter) && !tag.isAdj()) continue;
  else if ("adv".equals(sorter) && !tag.equals(Tag.ADV)) continue;
  else if ("name".equals(sorter) && !tag.isName()) continue;
  else if ("sub".equals(sorter) && !tag.isSub()) continue;
  else if ("verb".equals(sorter) && !tag.equals(Tag.VERB)) continue;
  if (dic.occs() == 0) break;
  out.println("  <tr>");
  out.print("    <td class=\"num\">");
  out.print(no) ;
  out.println("</td>");
  String t = dic.term().toString().replace('_', ' ');
  out.print("    <td><a href=\".?q="+t+"\">");
  out.print(t);
  out.println("</a></td>");
  out.print("    <td>");
  out.print(tag) ;
  out.println("</td>");
  out.print("    <td class=\"num\">");
  out.print(dic.hits()) ;
  out.println("</td>");
  out.print("    <td class=\"num\">");
  out.print(dic.occs()) ;
  out.println("</td>");
  if ("".equals(q)) {
    out.print("    <td class=\"num\">");
    out.print(dfScoreFr.format(dic.score())) ;
    out.println("</td>");
    out.println("  </tr>");
  }
  if (no >= max) break;
  no++;
}
    %>
      </tbody>
    </table>
    <script src="../static/vendors/Sortable.js">//</script>
    <script src="../static/js/freqs.js">//</script>
  </body>
</html>
