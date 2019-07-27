<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ page import="

java.io.BufferedReader,
java.io.File,
java.io.InputStream,
java.io.InputStreamReader,
java.io.IOException,
java.io.PrintWriter,
java.io.StringReader,
java.nio.charset.StandardCharsets,
java.nio.file.Files,
java.nio.file.Path,
java.nio.file.Paths,
java.nio.file.StandardOpenOption,
java.text.DecimalFormat,
java.text.DecimalFormatSymbols,
java.util.Arrays,
java.util.ArrayList,
java.util.Collections,
java.util.Comparator,
java.util.HashSet,
java.util.Iterator,
java.util.LinkedHashMap,
java.util.List,
java.util.Locale,
java.util.Set,
java.util.Scanner,
java.util.stream.Collectors,

org.apache.lucene.analysis.Analyzer,
org.apache.lucene.analysis.Tokenizer,
org.apache.lucene.analysis.TokenStream,
org.apache.lucene.analysis.tokenattributes.CharTermAttribute,
org.apache.lucene.analysis.tokenattributes.FlagsAttribute,
org.apache.lucene.analysis.tokenattributes.OffsetAttribute,
org.apache.lucene.document.Document,
org.apache.lucene.document.IntPoint,
org.apache.lucene.index.IndexableField,
org.apache.lucene.index.IndexReader,
org.apache.lucene.index.IndexWriter,
org.apache.lucene.index.LeafReader,
org.apache.lucene.index.MultiBits,
org.apache.lucene.index.PostingsEnum,
org.apache.lucene.index.Term,
org.apache.lucene.index.TermsEnum,
org.apache.lucene.index.Terms,
org.apache.lucene.index.LeafReaderContext,
org.apache.lucene.index.SortedSetDocValues,
org.apache.lucene.search.BooleanClause.Occur,
org.apache.lucene.search.BooleanQuery,
org.apache.lucene.search.BulkScorer,
org.apache.lucene.search.ConstantScoreQuery,
org.apache.lucene.search.DocIdSet,
org.apache.lucene.search.DocIdSetIterator,
org.apache.lucene.search.IndexSearcher,
org.apache.lucene.search.MatchAllDocsQuery,
org.apache.lucene.search.Query,
org.apache.lucene.search.ScoreDoc,
org.apache.lucene.search.ScoreMode,
org.apache.lucene.search.similarities.*,
org.apache.lucene.search.Sort,
org.apache.lucene.search.SortField,
org.apache.lucene.search.TermQuery,
org.apache.lucene.search.TopDocs,
org.apache.lucene.search.uhighlight.UnifiedHighlighter,
org.apache.lucene.search.uhighlight.DefaultPassageFormatter,
org.apache.lucene.search.vectorhighlight.FastVectorHighlighter,
org.apache.lucene.search.vectorhighlight.FieldQuery,
org.apache.lucene.search.Weight,
org.apache.lucene.util.BytesRef,
org.apache.lucene.util.Bits,
org.apache.lucene.util.BitSet,
org.apache.lucene.util.automaton.Automaton,
org.apache.lucene.util.automaton.DaciukMihovAutomatonBuilder,
org.apache.lucene.util.automaton.CompiledAutomaton,alix.fr.Tag,

alix.lucene.Alix,
alix.lucene.Alix.Tick,
alix.lucene.search.DicBytes,
alix.lucene.search.DicBytes.Cursor,
alix.lucene.analysis.CharsLemAtt,
alix.lucene.search.Cooc,
alix.lucene.analysis.CharsAtt,
alix.lucene.analysis.CharsDic,
alix.lucene.analysis.CharsDic.Entry,
alix.lucene.analysis.CharsMaps,
alix.lucene.analysis.CharsMaps.LexEntry,
alix.lucene.analysis.CharsMaps.NameEntry,
alix.lucene.analysis.TokenCompound,
alix.lucene.analysis.TokenDic,
alix.lucene.analysis.TokenDic.AnalyzerDic,
alix.lucene.analysis.TokenCooc,
alix.lucene.analysis.TokenCooc.AnalyzerCooc,
alix.lucene.analysis.TokenLem,
alix.lucene.analysis.TokenizerFr,
alix.lucene.search.Corpus,
alix.lucene.search.CollectorBits,
alix.lucene.search.Facet,
alix.lucene.search.HiliteFormatter,
alix.lucene.search.QueryBits,
alix.lucene.search.Scorer,
alix.lucene.search.ScorerBM25,
alix.lucene.search.ScorerTfidf,
alix.lucene.search.ScorerTf,
alix.lucene.search.ScorerOccs,
alix.lucene.search.TermFreqs,
alix.lucene.search.TermList,
alix.lucene.search.TopTerms,
alix.util.Char,
alix.util.Dir
" %>
<%!

final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
final static DecimalFormat dfppm = new DecimalFormat("#,###", frsyms);
final static DecimalFormat dfratio = new DecimalFormat("#,##0.0000", frsyms);
/** Field Name with int date */
final static String YEAR = "year";
/** Field name containing canonized text */
public static String TEXT = "text";


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
 * Ensure that a String could be included as an html attribute with quotes
 */
public static String escapeHtml(String s) {
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

/**
 * Get a request parameter as an int with default value
 */
public static int getParameter(HttpServletRequest request, String name, int value) {
  String s = request.getParameter(name);
  if (s == null || s.trim().length() == 0 || "null".equals(s)) return value;
  try {
    value = Integer.parseInt(s);
  }
  catch(NumberFormatException e) {
  }
  return value;
}

public static String getParameter(HttpServletRequest request, String name, String value) {
  String s = request.getParameter(name);
  if (s == null || s.trim().length() == 0 || "null".equals(s)) return value;
  return s;
}

%><%
long time = System.nanoTime();

Alix alix = Alix.instance(application.getRealPath("") + "/WEB-INF/lucene/");
int start = getParameter(request, "start", -1);
int end = getParameter(request, "end", -1);
String author = getParameter(request, "author", "");
String title = getParameter(request, "title", "");
Query filterQuery = null;
if (start > 0 && end > 0 && start <= end) filterQuery = IntPoint.newRangeQuery(YEAR, start, end);
// else filterQuery = new MatchAllDocsQuery(); // ensure to get bits without deleted docs
/*
QueryBitSetProducer was quite nice but has a too hard cache policy.
Prefer to rely on the default LRU caching of IndexSearcher.
// QueryBitSetProducer filter = new QueryBitSetProducer(query);
ConstantScoreQuery wraper is not needed here
*/

%>
