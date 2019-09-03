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
/**
 * display a doc from the index.
 * Different case
 *  — direct read of a docid
 *  — find a doc by id field
 *  — query with an index order
 */


Document document = null;
int docId = getParameter(request, "docid", -1);
String id = getParameter(request, "id", null);
String sort = getParameter(request, "sort", null);
int n = getParameter(request, "n", 1);
if (request.getParameter("prev") != null) {
  n = getParameter(request, "prevn", n);
}
else if (request.getParameter("next") != null) {
  n = getParameter(request, "nextn", n);
}
IndexReader reader = alix.reader();
IndexSearcher searcher = alix.searcher();
TopDocs topDocs = null;
if (id != null) {
  TermQuery qid = new TermQuery(new Term(Alix.ID, id));
  TopDocs search = searcher.search(qid, 1);
  ScoreDoc[] hits = search.scoreDocs;
  if (hits.length > 0) {
    docId = hits[0].doc;
    document = reader.document(docId);
  }
}
else if (!"".equals(q)) {
  topDocs = getTopDocs(session, searcher, corpus, q, sort);
  ScoreDoc[] hits = topDocs.scoreDocs;
  if (n < 1) n = 1;
  if (n <= hits.length) {
    docId = hits[n - 1].doc;
    document = reader.document(docId);
  }
}
else if (docId >= 0) {
  document = reader.document(docId);
}
String bibl = null;
if (document != null) {
  bibl = document.get("bibl");
  out.print("    <title>");
  out.print(Char.unTag(document.get("bibl")));
  out.println(" [Alix]</title>");
}
  %>
  </head>
  <body class="document">
    <%
// Shall we add prev/next navigation ?
if (bibl != null) {
  out.println("<header class=\"bibl\">");
  out.println("<table class=\"prevnext\"><tr>");
  /*
  out.println("<td class=\"prev\">");
  out.println("<a class=\"but prev\">◀</a>");
  out.println("</td>");
  */
  out.println("<td class=\"bibl\" title=\""+detag(bibl)+"\">");
  out.print("<a href=\"#\" class=\"bibl\">");
  out.println(bibl);
  out.print("</a>");
  out.println("</td>");
  /*
  out.println("<td class=\"next\">");
  out.println("<a class=\"but next\">▶</a>");
  out.println("</td>");
  */
  out.println("</tr></table>");
  out.println("</header>");
}
  %>
    <article class="chapter">
      <form id="qform" action="#">
        <input type="submit" 
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <input type="hidden" name="docid" value="<%=docId%>"/>
        <% 
        if (topDocs != null && n > 1) {
          out.println("<input type=\"hidden\" name=\"prevn\" value=\""+(n - 1)+"\"/>");
          out.println("<button type=\"submit\" name=\"prev\">◀</button>");
        }
        %>
        <input id="q" name="q" value="<%=escapeHtml(q)%>" autocomplete="off" size="30" onclick="this.select();"/>
        <select name="sort" onchange="this.form.submit()" title="Ordre">
            <option>Pertinence</option>
            <% sortOptions(out, sort); %>
        </select>
        <input id="n" name="n" value="<%=n%>" autocomplete="off" size="1"/>
               <% 
        if (topDocs != null) {
          long max = topDocs.totalHits.value;
          out.println("<span class=\"hits\"> / "+ max  + "</span>");
          if (n < max) {
            out.println("<input type=\"hidden\" name=\"nextn\" value=\""+(n + 1)+"\"/>");
            out.println("<button type=\"submit\" name=\"next\">▶</button>");
          }
        }
        %>
      </form>

  <%
if (document != null) {
  out.println("<div class=\"heading\">");
  out.println(bibl);
  out.println("</div>");
  out.println("<p class=\"keywords\">");
  out.println("<b>Mots-Clés</b> : ");
  Keywords words = new Keywords (alix, TEXT, docId, new ScorerBM25());
  Top<String> top = words.top();
  boolean first = true;
  for (Top.Entry<String> entry: top) {
    if (first) first = false;
    else out.println(", ");
    out.print(entry.value());
  }
  out.println("</p>");



  String text = document.get(TEXT);
  // hilie
  if (!"".equals(q)) {
    TermList terms = alix.qTerms(q, TEXT);
    ArrayList<BytesRef> bytesList = (ArrayList<BytesRef>)terms.bytesList();
    Terms tVek = reader.getTermVector(docId, TEXT);
    // buid a term enumeration like lucene like them in the term vector
    Automaton automaton = DaciukMihovAutomatonBuilder.build(bytesList);
    TermsEnum tEnum = new CompiledAutomaton(automaton).getTermsEnum(tVek);
    PostingsEnum postings = null;
    ArrayList<TokenOffsets> offsets = new ArrayList<TokenOffsets>();
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
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      out.print(text.substring(offset, tok.startOffset));
      out.print("\n<mark class=\"mark\" id=\"mark"+(i+1)+"\">");
      if (i > 0) out.print("<a href=\"#mark"+(i)+"\" onclick=\"location.replace(this.href); return false;\" class=\"prev\">◀</a> ");
      out.print(text.substring(tok.startOffset, tok.endOffset));
      if (i < size - 1) out.print(" <a href=\"#mark"+(i + 2)+"\" onclick=\"location.replace(this.href); return false;\" class=\"next\">▶</a>");
      out.print("</mark>\n");
      offset = tok.endOffset;
    }
    out.print(text.substring(offset));
    
    int length = text.length();
    out.println("<nav id=\"ruloccs\">");
    final DecimalFormat dfdec1 = new DecimalFormat("0.#", ensyms);
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      offset = tok.startOffset;
      out.println("<a href=\"#mark"+(i+1)+"\" style=\"top: "+dfdec1.format(100.0 * offset / length)+"%\">88&nbsp;</a>");
    }
    out.println("</nav>");
  }
  else {
    out.print(text);
  }
}
    %>
    </article>
    <script src="static/js/doc.js">//</script>
  </body>
</html>
