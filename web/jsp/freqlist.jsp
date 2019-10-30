<%@ page language="java"  pageEncoding="UTF-8" contentType="text/javascript; charset=UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%@ page import="java.text.DecimalFormat" %>
<%@ page import="java.text.DecimalFormatSymbols" %>
<%@ page import="java.util.Locale" %>
<%@ page import="alix.fr.Tag" %>
<%@ page import="alix.lucene.analysis.tokenattributes.CharsAtt" %>
<%@ page import="alix.lucene.analysis.FrDics" %>
<%@ page import="alix.lucene.analysis.FrDics.LexEntry" %>
<%@ page import="alix.lucene.search.Freqs" %>
<%@ page import="alix.util.Char" %>

<%!
final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
static final DecimalFormat dfdec3 = new DecimalFormat("0.###", ensyms);
static HashSet<CharsAtt> STOPLIST = new HashSet<CharsAtt>();
static {
  for (String w : new String[] {"dire", "Et", "etc.", "homme", "Il", "La", "Le", "Les", "M.", "p."}) {
    STOPLIST.add(new CharsAtt(w));
  }
}

%>
<%
  out.println("{");

String field = TEXT;

String sorter = tools.getString("sorter", "score", "freqSorter");
Freqs freqs = alix.freqs(field);

/*
String f = getParameter(request, "f", null);
String v = getParameter(request, "v", null);
if (f != null && v != null) {
  CollectorBits authorCollector = new CollectorBits(searcher);
  searcher.search(new TermQuery(new Term(f, v)), authorCollector);
  filter = authorCollector.bits();  
}
*/
TopTerms dic;
if (corpus != null) dic = freqs.topTerms(corpus.bits());
else dic = freqs.topTerms();
if ("score".equals(sorter)) dic.sort(dic.getScores());
else dic.sort(dic.getOccs());


out.println("  \"data\":[");
int lines = 500;
CharsAtt term = new CharsAtt();
Tag tag;
while (dic.hasNext()) {
  dic.next();
  dic.term(term);
  // local filter
  if (STOPLIST.contains(term)) continue;
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
  if ("nostop".equals(sorter) && FrDics.isStop(term)) continue;
  else if ("adj".equals(sorter) && !tag.isAdj()) continue;
  else if ("adv".equals(sorter) && !tag.equals(Tag.ADV)) continue;
  else if ("name".equals(sorter) && !tag.isName()) continue;
  else if ("sub".equals(sorter) && !tag.isSub()) continue;
  else if ("verb".equals(sorter) && !tag.equals(Tag.VERB)) continue;

  out.print("    {\"word\" : \"");
  out.print(dic.term().toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  out.print("\"");
  out.print(", \"weight\" : ");
  out.print(dfdec3.format(dic.rank()));
  out.print(", \"attributes\" : {\"class\" : \"");
  out.print(Tag.label(tag.group()));
  out.print("\"}");
  out.print("}");
  if (--lines <= 0 ) break;
  else out.println(",");
}
out.println("\n  ]");
out.println("\n}");
/*
DicBytes dic = lucene.dic(field);
Cursor cursor = dic.iterator();
int lines = 500;
float franfreq;
double bias = 0;
CharsAtt term = new CharsAtt();
while (cursor.hasNext()) {
  cursor.next();
  cursor.term(term);
  long count = cursor.count();
  if (count <= 2) break;
  if (CharsMaps.isStop(term)) continue;
  */
  // if (STOP.contains(term)) continue;
  /*
  */
  /*
  if ( frantext != null ) {
    float ratio = 4F;
    if (tag.isSub()) ratio = 12F;
    else if ( tag.equals(Tag.VERB)) ratio = 6F;
    LexEntry lex = CharsMaps.word(entries[i].key());
    if (lex == null) franfreq = 0;
    else franfreq = lex.freq;
    double myfreq = 1.0*count*1000000/total;
    if ( myfreq/franfreq < ratio ) continue;
  }
  */
%>