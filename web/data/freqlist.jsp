<%@ page language="java"  pageEncoding="UTF-8" contentType="text/javascript; charset=UTF-8"%>
<%@include file="common.jsp"%>
<%!
static HashSet<CharsAtt> STOP = new HashSet<CharsAtt>();
static {
  for (String w : new String[] {"dire", "Et", "etc.", "homme", "Il", "La", "Le", "Les", "M.", "p."}) {
    STOP.add(new CharsAtt(w));
  }
}
%>
<%
out.println("{");
IndexSearcher searcher = alix.searcher();

String textField = TEXT;

String sc = request.getParameter("scorer");
Scorer scorer = new ScorerBM25();
if ("tfidf".equals(sc)) scorer = new ScorerTfidf();
else if ("tf".equals(sc)) scorer = new ScorerTf();
else if ("occs".equals(sc)) scorer = new ScorerOccs();

String log = request.getParameter("log");
if (log != null && log.isEmpty()) log = null;
String frantext = request.getParameter("frantext");
DecimalFormat fontdf = new DecimalFormat("#");
String word = request.getParameter("word");
if (word != null && word.isEmpty()) word = null;

TermFreqs freqs = alix.termFreqs(textField);
String f = getParameter(request, "f", null);
String v = getParameter(request, "v", null);
if (f != null && v != null) {
  CollectorBits authorCollector = new CollectorBits(searcher);
  searcher.search(new TermQuery(new Term(f, v)), authorCollector);
  filter = authorCollector.bits();  
}

TopTerms terms = freqs.topTerms(filter, scorer);

out.println("  \"data\":[");
int lines = 500;
CharsAtt term = new CharsAtt();
Tag tag;
while (terms.hasNext()) {
  terms.next();
  terms.term(term);
  // filter some unuseful words
  if (STOP.contains(term)) continue;
  // term frequency or brut counts will not filter stop words
  if("tf".equals(sc) || "occs".equals(sc))
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
  // 
  if("tf".equals(sc) || "occs".equals(sc)) {
    if (tag.isVerb()) continue;
  }
  // if (tag.isPun()) continue;
  // if (tag.isNum()) continue;
  /*
  long weight = terms.weight();
  if (weight < 1) break;
  */
  out.print("    {\"word\" : \"");
  out.print(terms.term().toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  out.print("\"");
  out.print(", \"weight\" : ");
  out.print(terms.score());
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