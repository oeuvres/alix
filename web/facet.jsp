<%@ page language="java"  pageEncoding="UTF-8" contentType="text/plain; charset=UTF-8"%>
<%@include file="data/common.jsp" %>
<%
  // choose a field
String facet = "author";
// Open a dictionary for this field
DicBytes dic = new DicBytes(facet);
// get terms from a query
String q = request.getParameter("q");
if (q == null || q.trim() == "") q = "théâtre acteur ; lettres ; littérature ; poésie poème ; roman";
TermList terms = lucene.qTerms(q, TEXT);


for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
  BitSet bits = filter.bits(ctx); // the filtered docs for this segment
  if (bits == null) continue; // no matching doc, go away
  LeafReader leaf = ctx.reader();
  // get a doc iterator for the facet field
  SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
  if (docs4terms == null) continue;
  int maxDoc = leaf.maxDoc();
  long[] docOccs = new long[maxDoc];
  // get the ocurrence count for each doc (allow GC to release resources on postings)
  PostingsEnum postings;
  // cost of reading postings ?
  job = System.nanoTime();
  for(Term term: terms) {
    if (term == null) continue;
    postings = leaf.postings(term);
    if (postings == null) continue;
    int doc;
    long freq;
    while((doc = postings.nextDoc()) !=  DocIdSetIterator.NO_MORE_DOCS) {
  docOccs[doc] += postings.freq();
    }
  }
  // collect postings is very cheap (25000 docs < 1 ms)  
  out.println("postings " + (System.nanoTime() - job) / 1000000.0 + "ms\"");
  // the term for the facet is indexed with a long, lets bet it is less than the max int for an array collecttor
  long ordMax = docs4terms.getValueCount();
  // record counts for each term by ord index
  long[] counts = new long[(int)ordMax];
  // loop on docs 
  int doc;
  long ord;
  /* 
   // A bit slower than loop on filtered docs when the filter is narrow
   // could be faster for sparse facets 
  while ((doc = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
    if (!bits.get(doc)) continue;
    while ( (ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
  counts[(int)ord] += docOccs[doc];
    }
  }
  */
  job = System.nanoTime();
  int current = -1;
  for (doc = bits.nextSetBit(0); doc != DocIdSetIterator.NO_MORE_DOCS; doc = bits.nextSetBit(doc + 1)) {
    // current doc for facets is too far
    if (current > doc) continue;
    // advance cursor in docs facets
    if (current < doc) {
  current = docs4terms.advance(doc);
  if (current == DocIdSetIterator.NO_MORE_DOCS) break;
    }
    // current in factes maybe too far here
    if (current != doc) continue;
    while ( (ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
  counts[(int)ord] += docOccs[doc];
    }
  }
  out.println("facets " + (System.nanoTime() - job) / 1000000.0 + "ms\"");
  // populate the dic
  for (ord = 0; ord < ordMax; ord++) {
    dic.add(docs4terms.lookupOrd(ord), counts[(int)ord]);
  }
}
out.println(dic);
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Facettes</title>
    <style>
html, body {height: 100%; background-color: #f3f2ec; margin: 0; padding:0;}
body {font-family: sans-serif; }
* {box-sizing: border-box;}
    </style>
  </head>
  <body>
    <ul>
    <%
int max = 30;
Cursor cursor = dic.iterator();
while (cursor.hasNext()) {
  cursor.next();
  out.print("<li>");
  out.print(cursor.term());
  out.print(" (");
  out.print(cursor.count());
  out.print(")</li>");
  if (--max < 0) break;
}
    %>
    </ul>
  </body>
</html>