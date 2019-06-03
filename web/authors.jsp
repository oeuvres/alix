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
String field = "author";
// get document results
BitSet filter = (BitSet)session.getAttribute("filterBits");
// no query stored, give all authors
BytesDic dic = null;
if (filter == null) {
  dic = lucene.dic(field);
}
else {
  dic = new BytesDic(field);
  collect(lucene.reader(), field, filter, dic);
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