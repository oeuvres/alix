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
<%!final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
final static DecimalFormat dfScoreFr = new DecimalFormat("0.000", frsyms);
final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
static final DecimalFormat dfdec3 = new DecimalFormat("0.###", ensyms);
private static final int OUT_HTML = 0;
private static final int OUT_CSV = 1;
private static final int OUT_JSON = 2;

private static final int ALL    = 0b0000000;
private static final int NOSTOP = 0b0000001;
private static final int SUB =    0b0000010;
private static final int NAME =   0b0000100;
private static final int VERB =   0b0001000;
private static final int ADJ =    0b0010000;
private static final int ADV =    0b0100000;

private static String lines(final TopTerms dic, int max, final String format, final String filter, final boolean hasScore)
{
  max = Math.min(max, dic.size());
  StringBuilder sb = new StringBuilder();
  
  final int cat;
  if ("nostop".equals(filter)) cat = NOSTOP;
  else if ("sub".equals(filter)) cat = SUB;
  else if ("name".equals(filter)) cat = NAME;
  else if ("verb".equals(filter)) cat = VERB;
  else if ("adj".equals(filter)) cat = ADJ;
  else if ("adv".equals(filter)) cat = ADV;
  else cat = ALL;
  
  final int type;
  if (Jsp.JSON.equals(format)) type = Jsp.JSONi;
  else if (Jsp.CSV.equals(format)) type = Jsp.CSVi;
  else type = Jsp.HTMLi;
  
  
  int no = 1;
  Tag tag;
  // dictonaries coming fron analysis, wev need to test attributes
  CharsAtt term = new CharsAtt();
  boolean first = true;
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
        if (!tag.isSub()) continue;
        break;
      case NAME:
        if (!tag.isName()) continue;
        break;
      case VERB:
        if (!tag.equals(Tag.VERB)) continue;
        break;
      case ADJ:
        if (!tag.isAdj()) continue;
        break;
      case ADV:
        if (!tag.equals(Tag.ADV)) continue;
        break;
    }
    if (dic.occs() == 0) break;
    if (no >= max) break;
    
    switch(type) {
      case Jsp.JSONi:
        if (!first) sb.append(",\n");
        jsonLine(sb, dic, tag, no, hasScore);
        break;
      case Jsp.CSVi:
        csvLine(sb, dic, tag, no, hasScore);
        break;
      default:
        htmlLine(sb, dic, tag, no, hasScore);
    }
    no++;
    first = false;
  }

  return sb.toString();
}

/**
 * An html table row &lt;tr&gt; for lexical frequence result.
 */
private static void htmlLine(StringBuilder sb, final TopTerms dic, final Tag tag, final int no, final boolean hasScore)
{
  sb.append("  <tr>\n");
  sb.append("    <td class=\"num\">");
  sb.append(no) ;
  sb.append("</td>\n");
  String t = dic.term().toString().replace('_', ' ');
  sb.append("    <td><a target=\"_top\" href=\".?q="+t+"\">");
  sb.append(t);
  sb.append("</a></td>\n");
  sb.append("    <td>");
  sb.append(tag) ;
  sb.append("</td>\n");
  sb.append("    <td class=\"num\">");
  sb.append(dic.hits()) ;
  sb.append("</td>\n");
  sb.append("    <td class=\"num\">");
  sb.append(dic.occs()) ;
  sb.append("</td>\n");
  if (hasScore) {
    sb.append("    <td class=\"num\">");
    sb.append(dfScoreFr.format(dic.score())) ;
    sb.append("</td>\n");
  }
  sb.append("  </tr>\n");
}

private static void csvLine(StringBuilder sb, final TopTerms dic, final Tag tag, final int no, final boolean hasScore)
{
}

static private void jsonLine(StringBuilder sb, final TopTerms dic, final Tag tag, final int no, final boolean hasScore)
{
  sb.append("    {\"word\" : \"");
  sb.append(dic.term().toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  sb.append("\"");
  sb.append(", \"weight\" : ");
  sb.append(dfdec3.format(dic.rank()));
  sb.append(", \"attributes\" : {\"class\" : \"");
  sb.append(Tag.label(tag.group()));
  sb.append("\"}");
  sb.append("}");
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
// cooccurrences has not yet score
final boolean hasScore = (q == null);

String format = tools.getString("format", null);
if (format == null) format = (String)request.getAttribute(Obvil.EXT);

if (Jsp.JSON.equals(format)) {
  response.setContentType(Jsp.JSON_TYPE);
  out.println("{");
  out.println("  \"data\":[");
  out.println( lines(dic, 500, format, sorter, hasScore));
  out.println("\n  ]");
  out.println("\n}");  
}
else if (Jsp.CSV.equals(format)) {
  response.setContentType(Jsp.CSV_TYPE);
  out.println( lines(dic, -1, format, sorter, hasScore));
}
else {


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

             if (q == null) {
               // out.println(max+" termes");
             }
             else {
               out.println("&lt;<input style=\"width: 2em;\" name=\"left\" value=\""+left+"\"/>");
               out.print(q);
               out.println("<input style=\"width: 2em;\" name=\"right\" value=\""+right+"\"/>&gt;");
               out.println("<input type=\"hidden\" name=\"q\" value=\""+Jsp.escapeHtml(q)+"\"/>");
             }

             %>
           <select name="sorter" onchange="this.form.submit()">
              <option/>
              <%= posOptions(sorter) %>
           </select>
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
    if (hasScore) {
      out.println("<th>Score</th>");
    }
    %>
        <tr>
      </thead>
      <tbody>
        <%= lines(dic, 500, format, sorter, hasScore) %>
      </tbody>
    </table>
    <script src="../static/vendors/Sortable.js">//</script>
    <script src="../static/js/freqs.js">//</script>
  </body>
  <!-- <%= ((System.nanoTime() - time) / 1000000.0) %> ms  -->
  
</html>
<%
}
%>
