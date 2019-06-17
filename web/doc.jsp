<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<%!
class TokenOffsets
{
  final int startOffset;
  final int endOffset;
  final String className;
  public TokenOffsets(final int startOffset, final int endOffset, final String className)
  {
    this.startOffset = startOffset;
    this.endOffset = endOffset;
    this.className = className;
  }
}
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <link href="vendors/tei2html.css" rel="stylesheet"/>
    <link href="static/alix.css" rel="stylesheet"/>
  <%
int docId = getParameter(request, "doc", -1);
Document document = null;
IndexReader reader = lucene.reader();
while (true) {
  if (docId < 0) break;
  if( docId >= reader.maxDoc()) break; // tdoc do not existsc
  
  document = reader.document(docId);
  if (document == null) {
    // something have to be said here
    break;
  }
  break;
}
if (document != null) {
  out.print("    <title>");
  out.print(document.get("bibl"));
  out.println(" [Alix]</title>");
}
  %>
  </head>
  <body class="docuement">
      <%
String q = request.getParameter("q");
if (q == null || "".equals(q.trim())) q = "";
String sort = request.getParameter("sort");
      %>

    <form id="qform">
      <input type="hidden" name="doc" value="<%=docId%>"/>
      <input id="q" name="q" value="<%=q%>" autocomplete="off" size="60" autofocus="true" placeholder="Victor Hugo + Molière, Dieu"  onclick="this.select();"/>
    </form>
    <%
if (document != null) {
  out.println("<article class=\"chapter\">");
  String value = document.get("bibl");
  if (value != null) {
    out.println("<header class=\"bibl\">");
    out.print(value);
    out.println("</header>");
  }
  String text = document.get(TEXT);
  // hilie
  if (!"".equals(q)) {
    TermList terms = lucene.qTerms(q, TEXT);
    ArrayList<BytesRef> bytesList = (ArrayList<BytesRef>)terms.bytesList();
    Terms tVek = reader.getTermVector(docId, TEXT);
    Automaton automaton = DaciukMihovAutomatonBuilder.build(bytesList);
    CompiledAutomaton filter = new CompiledAutomaton(automaton);
    TermsEnum tEnum = filter.getTermsEnum(tVek);
    PostingsEnum postings = null;
    ArrayList<TokenOffsets> offsets = new ArrayList<>();
    while (tEnum.next() != null) {
      postings = tEnum.postings(postings, PostingsEnum.OFFSETS);
      while(postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
        int pos = -1;
        for (int freq = postings.freq(); freq > 0; freq --) {
          postings.nextPosition();
          offsets.add(new TokenOffsets(postings.startOffset(), postings.endOffset(), null));
        }
      }
    }
    Collections.sort(offsets, new Comparator<TokenOffsets>()
    {
      @Override
      public int compare(TokenOffsets entry1, TokenOffsets entry2)
      {
        int v1 = entry1.startOffset;
        int v2 = entry2.startOffset;
        if (v1 < v2) return -1;
        if (v1 > v2) return +1;
        return 0;
      }
    });
    int offset = 0;
    for (TokenOffsets tok: offsets) {
      out.print(text.substring(offset, tok.startOffset));
      out.print("<mark>");
      out.print(text.substring(tok.startOffset, tok.endOffset));
      out.print("</mark>");
      offset = tok.endOffset;
    }
    out.print(text.substring(offset));
  }
  else {
    out.print(text);
  }
  out.println("</article>");
}
    %>
  </body>
</html>