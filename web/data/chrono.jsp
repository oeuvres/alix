<%@ page language="java" contentType="text/javascript; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%!
/** A record used to sort docid by date */
/** 0.1ms no real need to cache */
public String ticks(PageContext pageContext, Alix lucene) throws IOException  {

  ServletContext application = pageContext.getServletContext();
  String ticks = (String)application.getAttribute("ticks");
  if (ticks != null) return ticks;
  
  int min = lucene.min(YEAR);
  int max = lucene.max(YEAR);
  int yearStep = 10; // TODO, optimize date step according to max - min
  long total = lucene.reader().getSumTotalTermFreq(TEXT);
  long labelWidth = (long)Math.ceil((float) total / 30); // width of a label in tokens
  
  long[] docLength = lucene.docLength(TEXT);
  

  StringBuilder sb = new StringBuilder();
  
  sb.append("[\n");
  int label = min + yearStep - min % yearStep;
  // get Axis data, but resort it in cumulate order
  Tick[] axis = lucene.axis(TEXT, YEAR);
  Arrays.sort(axis, new Comparator<Tick>() {
    @Override
    public int compare(Tick tick1, Tick tick2) {
      if (tick1.cumul < tick2.cumul) return -1;
      if (tick1.cumul > tick2.cumul) return +1;
      return 0;
    }
  });

  long cumulLast = -labelWidth - 1;
  boolean first = true;
  int lastYear = Integer.MIN_VALUE;
  for (int i = 0, length = axis.length; i < length; i++) {
    if (axis[i].length == 0) continue; // A deleted doc
    int year = axis[i].rank;
    if (lastYear == year) continue;
    lastYear = year;
    if (year < label) continue;
    label = year - year % yearStep;
    if (first) first = false;
    else sb.append(",\n");
    long cumul = axis[i].cumul;
    sb.append("    {");
    sb.append("\"v\": "+cumul);
    // let space between labels
    if (cumul - cumulLast > labelWidth) {
      sb.append(", \"label\": "+label);
      cumulLast = cumul;
    }
    sb.append("}");
    lastYear = year;
    label = label + yearStep;
  }
  sb.append("\n  ]");
  ticks = sb.toString();
  application.setAttribute("ticks", ticks);
  return ticks;
}%>
<%
// number of fots by curve, could be a parameter
int dots = 100;

// build queries
long time;
time = System.nanoTime();

IndexReader reader = lucene.reader();
long total = reader.getSumTotalTermFreq(TEXT);

int maxDoc = reader.maxDoc();
// OK if no deleted docs



out.println("{");
// display ticks
out.print( "  \"ticks\": "+ticks(pageContext, lucene));



//parse the query by line
String q = request.getParameter("q");
if (q == null || q.isBlank()) q = "théâtre acteur actrice ; lettres ; littérature ; poésie poème ; roman";
Term[][] terms = Alix.qTerms(q, TEXT);
if (terms != null) {
  out.print(",\n  \"labels\": [\"\"");
  for(Term[] l: terms) {
    out.print(", \"");
    boolean first = true;
    for(Term t: l) {
      if (first) first = false;
      else out.print(", ");
      out.print(t.text());
    }
    out.print("\"");
  }
  out.println("]");
  
  time = System.nanoTime();
  // loop on docs in docid order
  // get term freqs for each queries
  // populate a data table
  int cols = terms.length;
  // table of data to populate
  long[][] data = new long[cols][dots];
  // width of a step between two dots
  long step = total / dots;
  // axis index
  Tick[] axis = (Tick[])lucene.cache("axis-year");
  if (axis == null) {
    axis = lucene.axis(TEXT, YEAR);
    lucene.cache("axis-year", axis);
  }
  IndexSearcher searcher = lucene.searcher();
  /*
  Weight[] weights = new Weight[cols];
  for(int i = 0; i < cols; i++) {
    weights[i] = queries.get(i).createWeight(searcher, ScoreMode.COMPLETE, 0);
  }
  */
  // loop on contexts, because open a context is heavy, do not open too much
  for (LeafReaderContext ctx : reader.leaves()) {
    // Do as a termQuery, loop on PostingsEnum.FREQS for each term
    LeafReader leaf = ctx.reader();
    // loop on terms
    for(int i = 0; i < cols; i++) {
      Bits live = leaf.getLiveDocs();
      long[] col = data[i]; // prepare the col to write in
      Term[] line = terms[i];
      for (int j = 0, end = line.length; j < end; j ++) {
        PostingsEnum postings = leaf.postings(line[j]);
        
      }
    }
  }
  /*
  long toks = 0;
  long step = Math.round((double)total / dots);
  for(int i=0, end = sorter.length; i < end; i++) {
    long length = sorter[i].length;
    if (length <= 0) continue;
    toks += length;
    // looo on weights
  } */

  out.println("  \"data\": [");
  out.println("\n  ]");
}
out.println("\n}");
out.println("" + (System.nanoTime() - time) / 1000000.0 + "ms");
%>