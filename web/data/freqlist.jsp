<%@ page language="java"  pageEncoding="UTF-8" contentType="text/javascript; charset=UTF-8"%>
<%@include file="common.jsp"%>
<%!
static HashSet<CharsAtt> STOP = new HashSet<CharsAtt>();
static {
  /*
  for (String w : new String[] { "article", "Article", "partie", "section", "annexe", "protocole", "déclaration", "effet", "sein", "art.", "let.", 
      "Union_européenne", "accords", "accord", "Suisse", "paragraphe", "paragraphes", "UE", "ci-après", "sauf"}) {
    STOP.add(new CharsAtt(w));
  }
  */
}
%>
<%
String log = request.getParameter("log");
if ( log != null && log.isEmpty() ) log = null;
String frantext = request.getParameter("frantext");
DecimalFormat fontdf = new DecimalFormat("#");
String word = request.getParameter("word");
if ( word != null && word.isEmpty() ) word = null;

// output array
String field = TEXT;
BytesDic dic = lucene.dic(field);
Cursor cursor = dic.iterator();

out.println("[");
int lines = 500;
float franfreq;
double bias = 0;
int start = 0;
CharsAtt term = new CharsAtt();
Tag tag;
while (cursor.hasNext()) {
  cursor.next();
  cursor.term(term);
  long count = cursor.count();
  if (count <= 2) break;
  if (CharsMaps.isStop(term)) continue;
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
  // if (STOP.contains(term)) continue;
  /*
  if (tag.isPun()) continue;
  if (tag.isNum()) continue;
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

  out.print("  {\"word\" : \"");
  out.print(term.toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  out.print("\"");
  out.print(", \"weight\" : ");
  out.print(count);
  out.print(", \"attributes\" : {\"class\" : \"");
  out.print(Tag.label(tag.group()));
  out.print("\"}");
  out.print("}");
  if (--lines <= 0 ) break;
  else out.println(",");
}
out.println("\n]");

%>