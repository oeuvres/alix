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
#frame { padding: 25px; background:#FFF;}
#nuage { height: 800px; width: 800px; background: #FFF; }
#nuage a { text-decoration: none; }
span.SUB { color: #000000; }
span.ADJ { color: #008000; }
span.VERB { color: #000080;  }
span.ADV { color: #008000; }
span.NAME { color: #FF0000; }
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
    <div style="font-family: 'Roboto'; font-size: 50pt; ">
    	<span style="font-weight: 100">100</span>
    	<span style="font-weight: 200">200</span>
    	<span style="font-weight: 300">300</span>
    	<span style="font-weight: 400">400</span>
    	<span style="font-weight: 500">500</span>
    	<span style="font-weight: 600">600</span>
    	<span style="font-weight: 700">700</span>
    	<span style="font-weight: 800">800</span>
    	<span style="font-weight: 900">900</span>
    </div>
      <div id="frame">
      	<div id="nuage"></div>
	  </div>
   <ul>
<%
HashSet<CharsAtt> STOP = new HashSet<CharsAtt>();
for (String w : new String[] { "article", "Article", "partie", "section", "annexe", "protocole", "déclaration", "effet", "sein", "art.", "let.", 
    "Union_européenne", "accords", "accord", "Suisse", "paragraphe", "paragraphes", "UE", "ci-après", "sauf"}) {
  STOP.add(new CharsAtt(w));
}
CharsDic dic = new CharsDic();
Analyzer analyzer;
if (word != null) analyzer = new AnalyzerCooc(dic, word, -5, +5);
else analyzer = new AnalyzerDic(dic);
TokenStream ts;
long time;
// List<File> ls = Dir.ls("/home/fred/Documents/rougemont/DDR/tei");
// List<File> ls = Dir.ls("/var/www/html/critique");
List<File> ls = Dir.ls("/home/fred/Documents/suisse/accord2.html");
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
int fontmin = 14;
float fontmax = 80;
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

  out.print("{word:\"");
  // if ( word.indexOf( '"' ) > -1 ) word = word.replace( "\"", "\\\"" ); 
  out.print(entries[i].key().toString().replace('_', ' ')) ;
  out.print("\"");
  out.print(", weight:");
  if ( log != null ) out.print( fontdf.format( fontmin + (fontmax - fontmin)*Math.log10(1+9.0*count/countmax) ) );
  else out.print(fontdf.format( (1.0*count/countmax)*(fontmax - fontmin)+fontmin ) );
  out.print(", attributes:{class:'");
  out.print(tag.label());
  out.print("'}");
  out.println("},");
  if (--lines <= 0 ) break;
}%>
];
var fontmin = <%=fontmin%>;
var fontmax = <%=fontmax%>;
WordCloud(
	document.getElementById('nuage'), 
	{ 
		list: list,
		// drawMask: true,
		// maskColor: "white",
		minRotation: -Math.PI/4, 
		maxRotation: Math.PI/4,
   		rotationSteps: 5,
   		rotateRatio: 1, 
   		shuffle: false,
   		shape: 'square',
   		ridSize: 5,
   		color: null,
   		fontFamily:'Roboto, Lato, Helvetica, "Open Sans", sans-serif',
   		fontWeight: function(word, weight, fontSize) {
   			var ratio = (weight - fontmin) / (fontmax - fontmin);
   			var bold = 250 + Math.round(ratio * 13) * 50;
   			return ""+bold;
   		},
   		backgroundColor: '#FFFFFF',
   		opacity: function(word, weight, fontSize) {
   			var ratio = (weight - fontmin) / (fontmax - fontmin);
   			return 1 - ratio * 0.7;
   		},
   	} 
);
      </script>
    </article>
  </body>
</html>
