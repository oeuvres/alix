<%@ page language="java" contentType="text/javascript; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%@ page import="alix.lucene.search.Scale" %>
<%@ page import="alix.lucene.search.Scale.Tick" %>
<%@ page import="alix.lucene.search.TermList" %>

<%!
/** A record used to sort docid by date */

/**
 * Return a json view of ticks for 
 */
public String ticks(Scale scale) throws IOException  {
  
  StringBuilder sb = new StringBuilder();
  int min = scale.min();
  int max = scale.max();
  int span = max - min;
  int yearStep = 5;
  if (span > 400) yearStep = 10;
  else if (span > 50) yearStep = 5;
  else yearStep = 1;
  long total = scale.length();
  // calculate an hypothetic width to avoid too much labels ine dense sections
  long labelWidth = (long)Math.ceil((float) total / 30);
  sb.append("[\n");
  // give the first year
  int label = min - min % yearStep;
  // int label = min;
  sb.append("    {\"v\": 0, \"label\": " + min + "}");
  // get Axis data, but resort it in cumulate order
  Tick[] axis = scale.axis();
  
  
  long cumulLast = 0;
  boolean first = true;
  int lastYear = Integer.MIN_VALUE;
  long cumul = 0;
  for (int i = 0, length = axis.length; i < length; i++) {
    if (axis[i].length == 0) continue; // A deleted doc
    int year = axis[i].value;
    if (year < lastYear) {
      sb.append("\nBUG\n");
    }
    if (year == lastYear) continue;
    lastYear = year;
    if (year < label) continue;
    label = year - year % yearStep;
   
    sb.append(",\n");
    cumul = axis[i].cumul;
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
  // give the last year ?
  // if (cumul != total) sb.append("\n    {\"v\": "+ total+", \"label\": " + max + "}");
  sb.append("\n  ]");
  return sb.toString();
}%>
<%
// parameters
final String q = tools.getString("q", null);

// global variables
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
BitSet bits = bits(alix, corpus, q);
Scale scale = new Scale(alix, bits, YEAR, TEXT);
// number of fots by curve, could be a parameter
int dots = tools.getInt("dots", 200);



out.println("{");
if (q != null) out.print( "  \"q\": \""+q.replace("\"", "\\\"")+"\",\n");
// display ticks
long partial = System.nanoTime();
out.print( "  \"ticks\": "+ticks(scale)+",\n");
out.println("  \"time\" : \"" + (System.nanoTime() - partial) / 1000000.0 + "ms\",");



//parse the query by line
TermList terms = alix.qTermList(TEXT, q);
if (terms.size() > 0) {
  terms.sortByRowFreq(); // sort query lines by freq
  out.print("  \"labels\": [\"\"");
  boolean first = true;
  for(Term t: terms) {
    if (t == null) continue;
    out.print(", \"");
    out.print(t.text().replace("\"", ""));
    out.print("\"");
  }
  out.println("],");
  // get dots by curve
  long[][] data = scale.curves(terms, dots);
  long step = data[0][1];
  // 
  int rows = data[0].length;
  int cols = data.length;
  
  out.println("  \"data\": [");
  first = true;
  for (int row = 0; row < rows; row++) {
    // do not print empty rows (easier for curve display)
    long sum = 0;
    for (int col = 1; col < cols; col++) sum += data[col][row];
    if (sum == 0) continue;
    
    if (first) first = false;
    else out.print(",\n");
    
    out.print("    [");
    out.print(data[0][row]);
    for (int col = 1; col < cols; col++) {
      out.print(", ");
      long count = data[col][row];
      if (count < 2) {
        out.print("null");
        continue;
      }
      double ppm = Math.round(10.0 * 100000.0 * count / step) / 10.0;
      out.print(ppm);
    }
    out.print("]");
  }
  out.println("\n  ],");
  // labels for points
  out.println("  \"legend\": {");
  data = scale.legend(dots);
  first = true;
  long[] cumul = data[0];
  long[] year = data[1];
  long[] start = data[2];
  for (int row = 0; row < rows; row++) {
    if (first) first = false;
    else out.print(",\n");
    out.print("    \"");
    out.print(cumul[row]);
    out.print("\": {");
    out.print("\"year\":");
    out.print(year[row]);
    out.print(", \"start\":");
    out.print(start[row]);
    out.print("}");
  }
  out.println("\n  },");
}
out.println("  \"time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\"");
out.println("\n}");
%>