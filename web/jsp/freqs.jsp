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


private static String lines(final TopTerms dic, int max, final Mime mime, final String cat, final boolean hasScore)
{
  max = Math.min(max, dic.size());
  StringBuilder sb = new StringBuilder();
  
  Cat catSwitch = Cat.NOSTOP;
  try { catSwitch = Cat.valueOf(cat); }
  catch (Exception e) { }
  
  int no = 1;
  Tag zetag;
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
      zetag = new Tag(entry.tag);
    }
    else if (Char.isUpperCase(term.charAt(0))) {
      zetag = new Tag(Tag.NAME);
    }
    else {
      zetag = new Tag(0);
    }
    // filtering
    switch (catSwitch) {
      case NOSTOP:
        if (FrDics.isStop(term)) continue;
        break;
      case SUB:
        if (!zetag.isSub()) continue;
        break;
      case NAME:
        if (!zetag.isName()) continue;
        break;
      case VERB:
        if (!zetag.equals(Tag.VERB)) continue;
        break;
      case ADJ:
        if (!zetag.isAdj()) continue;
        break;
      case ADV:
        if (!zetag.equals(Tag.ADV)) continue;
        break;
    }
    if (dic.occs() == 0) break;
    if (no >= max) break;
    
    switch(mime) {
      case json:
        if (!first) sb.append(",\n");
        jsonLine(sb, dic, zetag, no, hasScore);
        break;
      case csv:
        csvLine(sb, dic, zetag, no, hasScore);
        break;
      default:
        htmlLine(sb, dic, zetag, no, hasScore);
    }
    no++;
    first = false;
  }

  return sb.toString();
}

/**
 * An html table row &lt;tr&gt; for lexical frequence result.
 */
private static void htmlLine(StringBuilder sb, final TopTerms dic, final Tag zetag, final int no, final boolean hasScore)
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
  sb.append(zetag) ;
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
int hpp = tools.getInt("hpp", -1);
if (hpp < 1 || hpp > 2000) hpp = 500;

final String sorter = tools.getString("sorter", "score", "freqSorter");
final String cat = tools.getString("cat", Cat.NOSTOP.name(), "catFreqs");

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
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
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
Mime mime;
try { mime = Mime.valueOf(format); }
catch(Exception e) { mime = Mime.html; }

if (Mime.json.equals(mime)) {
  response.setContentType(Mime.json.type);
  out.println("{");
  out.println("  \"data\":[");
  out.println( lines(dic, hpp, mime, cat, hasScore));
  out.println("\n  ]");
  out.println("\n}");  
}
else if (Mime.csv.equals(mime)) {
  response.setContentType(Mime.csv.type);
  out.println( lines(dic, -1, mime, cat, hasScore));
}
else {

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Fréquences, <%= (corpus != null) ? Jsp.escape(corpus.name())+", " : "" %><%=props.get("name")%> [Obvil]</title>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <script src="../static/js/common.js">//</script>
  </head>
  <body>
    <table class="sortable">
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
                  out.println("<input type=\"hidden\" name=\"q\" value=\""+Jsp.escape(q)+"\"/>");
                }
             %>
           <select name="cat" onchange="this.form.submit()">
              <option/>
              <%= catOptions(cat) %>
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
        <%= lines(dic, hpp, mime, cat, hasScore) %>
      </tbody>
    </table>
    <script src="../static/vendors/Sortable.js">//</script>
  </body>
  <!-- <%= ((System.nanoTime() - time) / 1000000.0) %> ms  -->
  
</html>
<%
}
%>
