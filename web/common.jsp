<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%><%@ page import="

java.io.BufferedReader,
java.io.File,
java.io.InputStream,
java.io.InputStreamReader,
java.io.IOException,
java.io.PrintWriter,
java.nio.charset.StandardCharsets,
java.nio.file.Files,
java.nio.file.Path,
java.nio.file.Paths,
java.nio.file.StandardOpenOption,
java.text.DecimalFormat,
java.text.DecimalFormatSymbols,
java.util.Arrays,
java.util.HashSet,
java.util.LinkedHashMap,
java.util.List,
java.util.Locale,
java.util.Set,
java.util.Scanner,

org.apache.lucene.analysis.Analyzer,
org.apache.lucene.analysis.TokenStream,
org.apache.lucene.analysis.tokenattributes.CharTermAttribute,
org.apache.lucene.analysis.tokenattributes.OffsetAttribute,

alix.fr.dic.Tag,
alix.lucene.CharsAttDic,
alix.lucene.CharsAttDic.Entry,
alix.lucene.CharsAttMaps,
alix.lucene.CharsAttMaps.LexEntry,
alix.lucene.CharsAttMaps.NameEntry,
alix.lucene.TokenDic,
alix.lucene.TokenDic.AnalyzerDic,
alix.lucene.TokenCooc,
alix.lucene.TokenCooc.AnalyzerCooc
" %><%!

static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
static DecimalFormat dfppm = new DecimalFormat("#,###", frsyms);
static DecimalFormat dfratio = new DecimalFormat("#,##0.0000", frsyms);


/**
 * Output options for Frantext filter
 */
static Float tlfoptions (PageContext pageContext, String param) throws IOException
{
  JspWriter out = pageContext.getOut();
  DecimalFormat frdf = new DecimalFormat("#.#", frsyms);
  Float tlfratio = null;
  if (param != null) {
    try { tlfratio = new Float(param); }
    catch (Exception e) {}
  }

  float[] values = { 200F, 100F, 50F, 20F, 10F, 7F, 6F, 5F, 3F, 2F, 0F, -2F, -3F, -5F, -10F };
  String[] labels = { "> ×200", "> ×100", "> ×50", "> ×20", "> ×10", "> ×7", "> ×6", "> ×5", "> ×3", "> ×2", 
      "[×2, /2]", "< /2", "< /3", "< /5", "< /10" };
  int lim = values.length;
  String selected="";
  boolean seldone = false;
  String label;
  for (int i=0; i < lim; i++) {
    if (!seldone && tlfratio != null && tlfratio >= values[i]) {
      selected=" selected=\"selected\"";
      seldone = true;
    }
    out.println("<option"+selected+" value=\""+values[i]+"\">"+labels[i] +"</option>");
    selected = "";
  }
  return tlfratio;
}

/**
 * Normaliser une entrée pour l'affichage dans un input
 */
public static String escapeXML(String s) {
    StringBuilder out = new StringBuilder(Math.max(16, s.length()));
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '"') out.append("&quot;");
        else if (c == '<') out.append("&lt;");
        else if (c == '>') out.append("&gt;");
        else if (c == '&') out.append("&amp;");
        else out.append(c);
    }
    return out.toString();
}

%><%
request.setCharacterEncoding("UTF-8");
%>
