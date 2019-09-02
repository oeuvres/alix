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
org.apache.lucene.search.Collector,
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
org.apache.lucene.search.TopDocsCollector,
org.apache.lucene.search.TopFieldCollector,
org.apache.lucene.search.TopScoreDocCollector,
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
alix.lucene.analysis.CharsLemAtt,
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
alix.lucene.search.BitsFromQuery,
alix.lucene.search.Cooc,
alix.lucene.search.Corpus,
alix.lucene.search.CorpusQuery,
alix.lucene.search.CollectorBits,
alix.lucene.search.DicBytes,
alix.lucene.search.DicBytes.Cursor,
alix.lucene.search.Facet,
alix.lucene.search.HiliteFormatter,
alix.lucene.search.Keywords,
alix.lucene.search.Scale,
alix.lucene.search.Scale.Tick,
alix.lucene.search.Scorer,
alix.lucene.search.ScorerBM25,
alix.lucene.search.ScorerTfidf,
alix.lucene.search.ScorerTf,
alix.lucene.search.ScorerOccs,
alix.lucene.search.SimilarityOccs,alix.lucene.search.Freqs,
alix.lucene.search.TermList,
alix.lucene.search.TopTerms,
alix.util.Char,
alix.util.Dir,
alix.util.Top
" %>
<%!

final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
final static DecimalFormat dfppm = new DecimalFormat("#,###", frsyms);
final static DecimalFormat dfratio = new DecimalFormat("#,##0.0000", frsyms);
final static DecimalFormat dfint = new DecimalFormat("###,###,##0", frsyms);
/** Field Name with int date */
final static String YEAR = "year";
/** Field name containing canonized text */
public static String TEXT = "text";
/** Key for current session corpus */
public static String CORPUS = "corpus";
/** Local stop word list */
static HashSet<CharsAtt> STOPLIST = new HashSet<CharsAtt>();
static {
  for (String w : new String[] {"dire", "Et", "etc.", "homme", "Il", "La", "Le", "Les", "M.", "p."}) {
    STOPLIST.add(new CharsAtt(w));
  }
}


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
 * Strip all html tags form a string
 */
public static String detag(String s) {
  StringBuilder out = new StringBuilder(Math.max(16, s.length()));
  boolean intag = false;
  for (int i = 0; i < s.length(); i++) {
    char c = s.charAt(i);
    if (c == '<') {
      intag = true;
    }
    else if (c == '>') {
      intag = false;
    }
    else if (!intag) {
      out.append(c);
    }
  }
  return out.toString();
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
 * Get a sort specification by a name
 */
 public static Sort getSort(final String sortSpec)
 {
   if ("year".equals(sortSpec)) {
     return new Sort(new SortField(YEAR, SortField.Type.INT));
   }
   else if ("year-inv".equals(sortSpec)) {
     return new Sort(new SortField(YEAR, SortField.Type.INT, true));
   }
   else if ("author".equals(sortSpec)) {
     return new Sort(new SortField(Alix.ID, SortField.Type.STRING));
   }
   else if ("author-inv".equals(sortSpec)) {
     return new Sort(new SortField(Alix.ID, SortField.Type.STRING, true));
   }
   else if ("length".equals(sortSpec)) {
     return new Sort(new SortField(TEXT, SortField.Type.INT));
   }
   return null;
 }

public static Similarity getSimilarity(final String sortSpec)
{
  Similarity similarity = null;
  if ("dfi_chi2".equals(sortSpec)) similarity = new DFISimilarity(new IndependenceChiSquared());
  else if ("dfi_std".equals(sortSpec)) similarity = new DFISimilarity(new IndependenceStandardized());
  else if ("dfi_sat".equals(sortSpec)) similarity = new DFISimilarity(new IndependenceSaturated());
  else if ("tf-idf".equals(sortSpec)) similarity = new ClassicSimilarity();
  else if ("lmd".equals(sortSpec)) similarity = new LMDirichletSimilarity();
  else if ("lmd0.1".equals(sortSpec)) similarity = new LMJelinekMercerSimilarity(0.1f);
  else if ("lmd0.7".equals(sortSpec)) similarity = new LMJelinekMercerSimilarity(0.7f);
  else if ("dfr".equals(sortSpec)) similarity = new DFRSimilarity(new BasicModelG(), new AfterEffectB(), new NormalizationH1());
  else if ("ib".equals(sortSpec)) similarity = new IBSimilarity(new DistributionLL(), new LambdaDF(), new NormalizationH3());
  return similarity;
}

public static void sortOptions(JspWriter out, String sortSpec) throws IOException
{
  String[] value = {
    "year", "year-inv", "author", "author-inv", "occs",
    // "tf-idf", "bm25", "dfi_chi2", "dfi_std", "dfi_sat", 
    // "lmd", "lmd0.1", "lmd0.7", "dfr", "ib"
  };
  String[] label = {
    "Année (+ ancien)", "Année (+ récent)", "Auteur (A-Z)", "Auteur (Z-A)", "Occurrences",
    // "tf-idf", "BM25", "DFI chi²", "DFI standard", "DFI saturé", 
    // "LMD", "LMD λ=0.1", "LMD λ=0.7", "DFR", "IB"
  };
  for (int i = 0, length = value.length; i < length; i++) {
    out.print("<option");
    if (value[i].equals(sortSpec)) out.print(" selected=\"selected\"");
    out.print(" value=\"");
    out.print(value[i]);
    out.print("\">");
    out.print(label[i]);
    out.println("</option>");
  }
  
}

/**
 * Get query from 
 */
public static Query getQuery(Corpus corpus, String q) throws IOException
{
  String fieldName = TEXT;
  Query qWords = Alix.qParse(q, fieldName);
  Query query;
  BitSet filter= null;
  if (corpus != null) filter = corpus.bits();
  if (filter != null) {
    query = new BooleanQuery.Builder()
      .add(new CorpusQuery(corpus.name(), filter), Occur.FILTER)
      .add(qWords, Occur.MUST)
      .build();
  }
  else {
    query = qWords;
  }
  return query;
}

public TopDocs getTopDocs(HttpSession session, IndexSearcher searcher, Corpus corpus, String q, String sortSpec) throws IOException
{
  int numHits = 10000;
  int totalHitsThreshold = Integer.MAX_VALUE;
  Query query = getQuery(corpus, q);
  if (query == null) return null;
  Sort sort = getSort(sortSpec);
  String key = ""+query;
  if (sort != null)  key+= " " + sort;
  Similarity similarity = null;
  Similarity oldSim = null;
  if ("occs".equals(sortSpec)) similarity = new SimilarityOccs();
  if (similarity != null) {
    key += " <"+similarity+">";
  }
  TopDocs topDocs = (TopDocs)session.getAttribute(key);
  if (topDocs != null) return topDocs;
  TopDocsCollector collector;
  if (sort != null) {
    collector = TopFieldCollector.create(sort, numHits, totalHitsThreshold);
  }
  else {
    collector = TopScoreDocCollector.create(numHits, totalHitsThreshold);
  }
  if (similarity != null) {
    oldSim = searcher.getSimilarity();
    searcher.setSimilarity(similarity);
    searcher.search(query, collector);
    // will it be fast enough to not affect other results ?
    searcher.setSimilarity(oldSim);
  }
  else {
    searcher.search(query, collector);
  }
  topDocs = collector.topDocs();
  session.setAttribute(key, topDocs);
  return topDocs;
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
  return s.trim();
}

%><%
long time = System.nanoTime();
request.setCharacterEncoding("UTF-8");
Alix alix = Alix.instance(application.getRealPath("") + "/WEB-INF/lucene/");

//Set a bitSet filter for current corpus
BitSet filter = null;
// limitation here, only one corpus allowed by session
Corpus corpus = (Corpus)session.getAttribute(CORPUS);
if (corpus != null) filter = corpus.bits();
// get query string
String q = getParameter(request, "q", "");


/*
int start = getParameter(request, "start", -1);
int end = getParameter(request, "end", -1);
String author = getParameter(request, "author", "");
String title = getParameter(request, "title", "");
Query filterQuery = null;
if (start > 0 && end > 0 && start <= end) filterQuery = IntPoint.newRangeQuery(YEAR, start, end);
// else filterQuery = new MatchAllDocsQuery(); // ensure to get bits without deleted docs
*/
/*
QueryBitSetProducer was quite nice but has a too hard cache policy.
Prefer to rely on the default LRU caching of IndexSearcher.
// QueryBitSetProducer filter = new QueryBitSetProducer(query);
ConstantScoreQuery wraper is not needed here
*/

%>
