<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8"%>
<%@include file="data/common.jsp" %>
<%!
public static void collect(IndexReader reader, String field, BitSet filter, BytesDic dic) throws IOException
{
  int docBase = 0;
  int maxDoc = 0;
  Iterator<LeafReaderContext> contexts = reader.leaves().iterator();
  LeafReader leaf = null;
  LeafReaderContext ctx = null;
  SortedSetDocValues docs = null;
  // loop on docs, try to find them in the right context
  for (int docid = filter.nextSetBit(0); docid >= 0; docid = filter.nextSetBit(docid+1)) {
    // search the right context 
    while((docid - docBase) >= maxDoc) {
      if (!contexts.hasNext()) return; // no more contexts, go away
      ctx = contexts.next();
      docBase = ctx.docBase;
      leaf = ctx.reader();
      maxDoc = leaf.maxDoc();
      docs = leaf.getSortedSetDocValues(field);
    }
    docs.advanceExact(docid - docBase);
    long ord;
    // each term 
    while((ord = docs.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
      dic.add(docs.lookupOrd(ord), 1);
    }
  }
}

%>
<%



IndexReader reader = lucene.reader();
// choose a field
String facet = "author";
// open the dic
BytesDic dic = new BytesDic(facet);
// get terms from a query
String q = request.getParameter("q");
if (q == null || q.trim() == "") q = "théâtre acteur ; lettres ; littérature ; poésie poème ; roman";
TermList terms = lucene.qTerms(q, TEXT);
// reusable counts if possible
int leafCount = reader.leaves().size();
// loop on the reader leaves
for (LeafReaderContext context : reader.leaves()) {
  LeafReader leaf = context.reader();
  // get a doc iterator for the facet
  SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
  if (docs4terms == null) break;
  // the term for the facet is indexed with a long, lets bet it is less than the max int for an array collecttor
  long ordMax = docs4terms.getValueCount();
  // record counts for each term by ord
  long[] counts = new long[(int)ordMax];
  // loop on matches docs
      
  if (counts == null || counts.length < ordMax) {
    int length = (int)(1.5 * ordMax); // give some place
    if (leafCount == 1) length = 
    counts = new int[(int)(ordMax * 1.5)];
  }
  System.out.println("term ord < "+ordMax);
  for (long ord = 0; ord < ordMax; ord++) {
    System.out.println(ord+" "+docs4terms.lookupOrd(ord).utf8ToString());
  }
  /*
  // terms.termsEnum().docFreq() not implemented, should loop on docs to have it
  while (docs4terms.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
    long ord;
    docs++;
    // each term
    while ((ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
      dic.add(docs4terms.lookupOrd(ord), 1);
    }
  }
  */
}


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