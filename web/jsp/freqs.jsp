<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="java.text.DecimalFormat" %>
<%@ page import="java.text.DecimalFormatSymbols" %>
<%@ page import="java.util.Locale" %>
<%@ page import="alix.fr.Tag" %>
<%@ page import="alix.lucene.analysis.tokenattributes.CharsAtt" %>
<%@ page import="alix.lucene.analysis.FrDics" %>
<%@ page import="alix.lucene.analysis.FrDics.LexEntry" %>
<%@ page import="alix.lucene.search.Freqs" %>
<%@ page import="alix.lucene.search.TermList" %>
<%@ page import="alix.lucene.util.Cooc" %>
<%@ page import="alix.util.Char" %>
<%!
final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
final static DecimalFormat dfScoreFr = new DecimalFormat("0.000", frsyms);
final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
static final DecimalFormat dfdec3 = new DecimalFormat("0.###", ensyms);
private static final int OUT_HTML = 0;
private static final int OUT_CSV = 1;
private static final int OUT_JSON = 2;

private static final int NOSTOP = 0b0000001;
private static final int SUB =    0b0000010;
private static final int NAME =   0b0000100;
private static final int VERB =   0b0001000;
private static final int ADJ =    0b0010000;
private static final int ADV =    0b0100000;

private static String lines(final TopTerms dic, final int formater, final String sorter)
{
  StringBuilder sb = new StringBuilder();
  final int cat;
  if ("nostop".equals(sorter)) cat = NOSTOP;
  else if ("sub".equals(sorter)) cat = SUB;
  else if ("name".equals(sorter)) cat = NAME;
  else if ("verb".equals(sorter)) cat = VERB;
  else if ("adj".equals(sorter)) cat = ADJ;
  else if ("adv".equals(sorter)) cat = ADV;
  int no = 1;
  Tag tag;
  // dictonaries coming fron analysis, wev need to test attributes
  CharsAtt term = new CharsAtt();
  while (dic.hasNext()) {
    dic.next();
    dic.term(term);
    if (term.isEmpty()) continue; // empty position
    // filter some unuseful words
    // if (STOPLIST.contains(term)) continue;
    LexEntry entry = FrDics.word(term);
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
    switch (cat) {
      case NOSTOP:
        if (FrDics.isStop(term)) continue;
        break;
      case SUB:
        
        
    }
    if ("nostop".equals(sorter) && FrDics.isStop(term)) continue;
    else if ("adj".equals(sorter) && !tag.isAdj()) continue;
    else if ("adv".equals(sorter) && !tag.equals(Tag.ADV)) continue;
    else if ("name".equals(sorter) && !tag.isName()) continue;
    else if ("sub".equals(sorter) && !tag.isSub()) continue;
    else if ("verb".equals(sorter) && !tag.equals(Tag.VERB)) continue;
    if (dic.occs() == 0) break;
    if (no >= max) break;
    no++;

    /*
    if (--lines <= 0 ) break;
    else out.println(",");
    */
  
  }

  return sb.toString();
}

private static String htmlLine()
{
  StringBuilder sb = new StringBuilder();
  sb.append("  <tr>\n");
  sb.append("    <td class=\"num\">");
  sb.append(no) ;
  sb.append("</td>");
  String t = dic.term().toString().replace('_', ' ');
  sb.append("    <td><a href=\".?q="+t+"\">");
  sb.append(t);
  sb.append("</a></td>");
  sb.append("    <td>");
  sb.append(tag) ;
  sb.append("</td>");
  sb.append("    <td class=\"num\">");
  sb.append(dic.hits()) ;
  sb.append("</td>");
  sb.append("    <td class=\"num\">");
  sb.append(dic.occs()) ;
  sb.append("</td>");
  if ("".equals(q)) {
    sb.append("    <td class=\"num\">");
    sb.append(dfScoreFr.format(dic.score())) ;
    sb.append("</td>");
    sb.append("  </tr>");
  }
  return sb.toString();
}

private static String csvLine()
{
  StringBuilder sb = new StringBuilder();
  return sb.toString();
}

static private String jsonLine(final TopTerms dic)
{
  StringBuilder sb = new StringBuilder();
  sb.append("    {\"word\" : \"");
  sb.append(dic.term().toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  sb.append("\"");
  sb.append(", \"weight\" : ");
  sb.append(dfdec3.format(dic.rank()));
  sb.append(", \"attributes\" : {\"class\" : \"");
  sb.append(Tag.label(tag.group()));
  sb.append("\"}");
  sb.append("}");
  return sb.toString();
}
%>
<%
//parameters
final String q = tools.getString("q", null);
final String sorter = tools.getString("sorter", "score", "freqSorter");
int left = tools.getInt("left", 5, "freqLeft");
if (left < 0) left = 0;
else if (left > 10) left = 10;
int right = tools.getInt("right", 5, "freqRight");
if (right < 0) right = 0;
else if (right > 10) right = 10;

// global variables
final String field = TEXT; // the field to process
TopTerms dic; // the dictionary to extracz
BitSet filter = null; // if a corpus is selected, filter results with a bitset
if (corpus != null) filter = corpus.bits();
if (q == null) {
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

String format = tools.getString("format", null);
if (format == null) format = (String)request.getAttribute(Obvil.EXT);



int max = Math.min(500, dic.size());
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <link href="../static/obvil.css" rel="stylesheet"/>
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
             out.println("<option/>");
             out.println(posOptions(sorter));
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
    %>
      </tbody>
    </table>
    <script src="../static/vendors/Sortable.js">//</script>
    <script src="../static/js/freqs.js">//</script>
  </body>
</html>
