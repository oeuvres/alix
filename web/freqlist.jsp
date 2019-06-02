<%@ page language="java"  pageEncoding="UTF-8" contentType="text/javascript; charset=UTF-8"%>
<%@include file="common.jsp" %>
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
int fontmin = 0;
try { fontmin = Integer.parseInt(request.getParameter("fontmin"));} catch(Exception e) {}
if (fontmin <= 0) fontmin = 14;
int fontmax = 0;
try { fontmax = Integer.parseInt(request.getParameter("fontmax"));} catch(Exception e) {}
if (fontmax <= fontmin || fontmax >= 200) fontmax = 80;

String log = request.getParameter("log");
if ( log != null && log.isEmpty() ) log = null;
String frantext = request.getParameter("frantext");
DecimalFormat fontdf = new DecimalFormat("#");
String word = request.getParameter("word");
if ( word != null && word.isEmpty() ) word = null;

// List<File> ls = Dir.ls("/home/fred/Documents/rougemont/DDR/tei");
List<File> ls = Dir.ls("/var/www/html/critique");
//List<File> ls = Dir.ls("/home/fred/Documents/suisse/accord2.html");

CharsDic dic = new CharsDic();
Analyzer analyzer;
if (word != null) analyzer = new AnalyzerCooc(dic, word, -5, +5);
else analyzer = new AnalyzerDic(dic);
TokenStream ts;
long time;

int n = 0;
long total = 0;
for (File entry : ls) {
  Path path = entry.toPath();
  String text = Files.readString(path);
  InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
  BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
  ts = analyzer.tokenStream("name", reader);
  CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
  OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);
  long occs = 0;
  try {
    ts.reset();
    while (ts.incrementToken()) {
      occs++; // loop on all tokens
    }
    ts.end();
  }
  catch(Exception e) {
    int from = Math.max(offset.startOffset()-50, 0);
    int to = Math.min(offset.endOffset()+50, text.length());
    out.println(entry+" "+text.substring(from, to));
    e.printStackTrace(response.getWriter());
  }
  finally {
    ts.close();
  }
  total += occs;
  n++;
}
analyzer.close();
// output array
Entry[] entries = dic.sorted();

out.println("[");
int max = entries.length;
int lines = 500;
int countmax = 0;
float franfreq;
double bias = 0;
int start = 0;
if (word != null) start = 1; 
for (int i = start; i < max; i++) {
  if (CharsMaps.isStop(entries[i].key())) continue;
  if (STOP.contains(entries[i].key())) continue;
  int count = entries[i].count();
  if (count <= 2) break;
  Tag tag = new Tag(entries[i].tag());
  if (tag.isPun()) continue;
  if (tag.isNum()) continue;
  if ( countmax == 0 ) countmax = count;
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
  else {
  }

  out.print("  {\"word\" : \"");
  // if ( word.indexOf( '"' ) > -1 ) word = word.replace( "\"", "\\\"" ); 
  out.print(entries[i].key().toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  out.print("\"");
  out.print(", \"weight\" : ");
  if ( log != null ) out.print( fontdf.format( fontmin + (fontmax - fontmin)*Math.log10(1+9.0*count/countmax) ) );
  else out.print(fontdf.format( (1.0*count/countmax)*(fontmax - fontmin)+fontmin ) );
  out.print(", \"attributes\" : {\"class\" : \"");
  out.print(Tag.label(tag.group()));
  out.print("\"}");
  out.print("}");
  if (--lines <= 0 ) break;
  else out.println(",");
}
out.println("\n]");

%>