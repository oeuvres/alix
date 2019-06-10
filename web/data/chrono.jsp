<%@ page language="java" contentType="text/javascript; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%!
/** A record used to sort docid by date */
/** 0.1ms no real need to cache */
public String ticks(PageContext pageContext, Alix lucene) throws IOException  {

  ServletContext application = pageContext.getServletContext();
  
  int min = lucene.min(YEAR);
  int max = lucene.max(YEAR);
  int yearStep = 5; // TODO, optimize date step according to max - min
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
  return sb.toString();
}%>
<%
// number of fots by curve, could be a parameter
int dots = 200;

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
TermList terms = lucene.qTerms(q, TEXT);
if (terms.size() > 0) {
  terms.sortByRowFreq();
  out.print(",\n  \"labels\": [\"\"");
  boolean first = true;
  for(Term t: terms) {
    if (t == null) {
      out.print("\"");
      first = true;
      continue;
    }
    if (first) {
      out.print(", \"");
      first = false;
    }
    else out.print(", ");
    out.print(t.text());
  }
  out.println("],");
  
  time = System.nanoTime();
  int cols = terms.rows();
  // table of data to populate
  int[][] data = new int[cols][dots];
  // width of a step between two dots, 
  long step = (total) / dots;
  // axis index
  Tick[] axis = (Tick[])lucene.cache("axis-year");
  if (axis == null) {
    axis = lucene.axis(TEXT, YEAR);
    lucene.cache("axis-year", axis);
  }
  // loop on contexts, because open a context is heavy, do not open too much
  for (LeafReaderContext ctx : reader.leaves()) {
    // Do as a termQuery, loop on PostingsEnum.FREQS for each term
    LeafReader leaf = ctx.reader();
    Bits live = leaf.getLiveDocs();
    // loop on terms
    int col = 0;
    for(Term term: terms) {
      if (term == null) {
        col++;
        continue;
      }
      PostingsEnum postings = leaf.postings(term);
      if (postings == null) continue;
      int doc;
      long freq;
      int[] column = data[col];
      while((doc = postings.nextDoc()) !=  DocIdSetIterator.NO_MORE_DOCS) {
        if (live !=null && !live.get(doc)) continue;
        if ((freq = postings.freq()) == 0) continue;
        int row = (int)(axis[doc].cumul / step);
        if (row >= dots) row = dots - 1; // because of rounding on big numbers last row could be missed
        column[row] += freq;
      }
    }
  }

  out.println("  \"data\": [");
  first = true;
  for (int row = 0; row < dots; row++) {
    if (first) first = false;
    else out.print(",\n");
    out.print("    [");
    out.print((step * row));
    for (int col = 0; col < cols; col++){
      out.print(", ");
      double ppm = Math.round(10 * 100000.0 * data[col][row] / step) / 10.0;
      out.print(ppm);
    }
    out.print("]");
  }
  out.println("\n  ],");
}
out.println("  \"time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\"");
out.println("\n}");
%>