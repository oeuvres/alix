<%@ page language="java"  pageEncoding="UTF-8" contentType="text/javascript; charset=UTF-8"%>
<%@include file="common.jsp"%>
<%!%>
<%
  Query filterQuery =  new MatchAllDocsQuery();
/*
BooleanQuery filterQuery = new BooleanQuery.Builder()
  .add(IntPoint.newExactQuery(Indexer.tag, 0), Occur.SHOULD)
  .add(IntPoint.newExactQuery(Indexer.tag, 1), Occur.SHOULD)
  .build();
*/
String field = "article";
String[] words = request.getParameter("w").split("[\n,]+");
if (words != null) {
  BooleanQuery.Builder qBuilder = new BooleanQuery.Builder();
  for (String w: words) {
    w = w.trim();
    qBuilder.add(new TermQuery(new Term(field, w)), Occur.SHOULD);
  }
  BooleanQuery pivotQuery = new BooleanQuery.Builder()
    .add(filterQuery, Occur.FILTER)
    .add(qBuilder.build(), Occur.MUST)
  .build();
  long time = System.nanoTime();
  // build a cooccurrence map
  Cooc cooc = new Cooc(lucene.searcher(), pivotQuery, field, 1, 1);
  time = (System.nanoTime() - time) / 1000000;
  // out.println("Cooc build in " + time + "ms");
  
  BytesRef term = new BytesRef(); // reusable string
  DicBytes terms = lucene.dic(field); // dictionary of all term for the field
  CharsAtt chars = new CharsAtt();

  out.println("[");
  int lines = 500;
  Cursor cursor = terms.iterator();
  while (cursor.hasNext()) {
    cursor.next();
    cursor.term(term); // get the term
    long total = cursor.count();
    long count = cooc.count(field, term);
    // chars.cop
    // System.out.print(bytes.utf8ToString()+":"+bytesDic.count(bytes)+" - ");
    double ratio = 1000000.0*count/total;
    // if (ratio < 3000) continue;
    out.print("  {\"word\" : \"");
    out.print(term.utf8ToString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
    out.print("\"");
    out.print(", \"weight\" : ");
    out.print(Math.round(ratio));
    /*
    out.print(", \"attributes\" : {\"class\" : \"");
    out.print(Tag.label(tag.group()));
    out.print("\"}");
    */
    out.print("}");

    // out.println(term.utf8ToString()+":\t"+count+'/'+total+"\t"+ratio);
    if (--lines <= 0 ) break;
    else out.println(",");
  }
  out.println("\n]");
}

/*
out.println("[");
int max = entries.length;
int lines = 500;
int countmax = 0;
float franfreq;
double bias = 0;
int start = 0;
if (word != null) start = 1; 
for (int i = start; i < max; i++) {
  if (CharsMaps.isStop(entries[i].key())) continue;
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

  out.print("  {\"word\" : \"");
  out.print(entries[i].key().toString().replace( "\"", "\\\"" ).replace('_', ' ')) ;
  out.print("\"");
  out.print(", \"weight\" : ");
  if ( log != null ) out.print( fontdf.format( fontmin + (fontmax - fontmin) * Math.log10(1+9.0*count/countmax) ) );
  else out.print(fontdf.format( fontmin + (fontmax - fontmin) * (1.0*count/countmax)) );
  out.print(", \"attributes\" : {\"class\" : \"");
  out.print(Tag.label(tag.group()));
  out.print("\"}");
  out.print("}");
  if (--lines <= 0 ) break;
  else out.println(",");
}
out.println("\n]");
*/
%>