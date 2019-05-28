<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%
  String log = request.getParameter("log");
if ( log != null && log.isEmpty() ) log = null;
String frantext = request.getParameter("frantext");
DecimalFormat fontdf = new DecimalFormat("#");
String word = request.getParameter("word");
if ( word != null && word.isEmpty() ) word = null;
%>
<!DOCTYPE html>
<html>
  <head>
    <title>Nuage de mots</title>
    <link rel="stylesheet" type="text/css" href="alix.css" />
    <script src="lib/wordcloud2.js">//</script>
    <style>
#frame { padding: 25px; background:#000;}
#nuage { height: 800px; background: #000; }
#nuage a { text-decoration: none; }
a.mot { font-family: Georgia, serif; position: absolute; display: block; white-space: nowrap; color: rgba( 128, 0, 0, 0.9); }
a.SUB { color: rgba( 32, 32, 32, 0.6); font-family: "Arial", sans-serif; font-weight: 700; }
a.ADJ { color: rgba( 128, 128, 192, 0.8); }
a.VERB { color: rgba( 255, 0, 0, 1 );  }
a.ADV { color: rgba( 64, 128, 64, 0.8); }
a.NAME { padding: 0 0.5ex; background-color: rgba( 192, 192, 192, 0.2) ; color: #FFF; 
text-shadow: #000 0px 0px 5px;  -webkit-font-smoothing: antialiased;  }
    </style>
  </head>
  <body>
    <article id="article">
      <form method="GET">
        <a href=".">Alix</a>
		<%
		  String checked = ""; if (frantext != null) checked =" checked=\"checked\"";
		%>
       <label>Filtre Frantext <input name="frantext" <%=checked%> type="checkbox"/></label>
        <button>▶</button>
      </form>
      <div id="frame">
      	<div id="nuage"></div>
	  </div>
   <ul>
<%
  CharsDic dic = new CharsDic();
Analyzer analyzer;
if (word != null) analyzer = new AnalyzerCooc(dic, word, -5, +5);
else analyzer = new AnalyzerDic(dic);
TokenStream ts;
long time;
// List<File> ls = Dir.ls("/home/fred/Documents/rougemont/DDR/tei");
// List<File> ls = Dir.ls("/var/www/html/critique");
List<File> ls = Dir.ls("/home/fred/Documents/suisse/.*\\.html");
out.println(ls);

int n = 0;
long total = 0;
for (File entry : ls) {
  out.print(entry.getName());
  Path path = entry.toPath();
  String text = Files.readString(path);
  InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
  BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
  ts = analyzer.tokenStream("cloud", reader);
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
    out.println("token no="+occs+" "+term);
    e.printStackTrace();
  }
  finally {
    ts.close();
  }
  out.println(" occs="+occs+"<br/>");
  total += occs;
  n++;
}
analyzer.close();
%>
</ul>
		<script>
var list = [
<%// System.out.println("tokens="+total+" files="+n);
Entry[] entries = dic.sorted();
int max = entries.length;
int lines = 500;
int fontmin = 16;
float fontmax = 70;
int countmax = 0;
float franfreq;
double bias = 0;
int start = 0;
if (word != null) start = 1; 
for (int i = start; i < max; i++) {
  if (CharsMaps.isStop(entries[i].key())) continue;
  int count = entries[i].count();
  Tag tag = new Tag(entries[i].tag());
  if (tag.isPun()) continue;
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

  out.print("[\"");
  // if ( word.indexOf( '"' ) > -1 ) word = word.replace( "\"", "\\\"" ); 
  out.print(entries[i].key().toString()) ;
  out.print("\", ");
  if ( log != null ) out.print( fontdf.format( fontmin + (fontmax - fontmin)*Math.log10(1+9.0*count/countmax) ) );
  else out.print(fontdf.format( (1.0*count/countmax) *fontmax+fontmin ) );
  out.print(", '");
  out.print(tag.label());
  out.print("'");
  out.print(", ");
  out.print(count);
  // TODO link ? out.println("\", target:\"grep\", href:\"grep.jsp?q="+word+"&bibcode="+bibcode+"\" }, bias:"+bias+" },");
  out.println("],");
  if (--lines <= 0 ) break;
}%>
];
WordCloud(
	document.getElementById('nuage'), 
	{ 
		list: list,
		// drawMask: true,
		// maskColor: "white",
		minRotation: -Math.PI/2.5, 
		maxRotation: Math.PI/2.5,
   		rotationSteps: 8,
   		rotateRatio: 1, 
   		shape: 'square',
   		ridSize: 5,
   		fontFamily:'Arial, sans-serif',
   		fontWeight: 500, 
   		color: 'random-light', 
   		backgroundColor: '#000000' 
   	} 
);
      </script>
    </article>
  </body>
</html>
