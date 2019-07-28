<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<%!
static HashSet<String> FIELDS = new HashSet<String>();
static {
  for (String w : new String[] {Alix.BOOKID, "byline", "year", "title"}) {
    FIELDS.add(w);
  }
}
static Sort SORT = new Sort(new SortField("author1", SortField.Type.STRING), new SortField("year", SortField.Type.INT));
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Corpus [Alix]</title>
    <link href="static/alix.css" rel="stylesheet"/>
  </head>
  <body class="corpus">
    <%
String[] checks = request.getParameterValues("book");
if (checks != null) {
  for (String s: checks) {
    out.println(s + ", ");
  }
}
    %>
    <form method="post">
      <fieldset>
        <legend>Filter le tableau</legend>
        <label for="start">Années</label>
        <input id="start" name="start" type="number" min="<%=alix.min("year")%>" max="<%=alix.max("year")%>" placeholder="Début" class="year" onclick="select()"/>
        <input id="end" name="end" type="number" min="<%=alix.min("year")%>" max="<%=alix.max("year")%>" placeholder="Fin" class="year" onclick="select()"/>
        <br/><label for="author">Auteur</label>
        <input id="author" name="author" value="<%=author%>" size="50" list="author-data" type="text" onclick="select()" placeholder="Nom, Prénom"/>
        <datalist id="author-data">
  <%
  Facet facet = alix.facet("author", TEXT);
  TopTerms facetEnum = facet.topTerms();
  while (facetEnum.hasNext()) {
    facetEnum.next();
    // long weight = facetEnum.weight();
    out.println("<option value=\""+facetEnum.term()+"\"/>");
  }
  %>
  
        </datalist>
        <br/><button id="show" type="button">Tout voir</button><button id="save" type="submit">Enregistrer</button>
      </fieldset>
    

    
    <table class="sortable" id="corpus">
      <thead>
        <tr>
          <th class="checkbox"><input id="checkall" type="checkbox" title="Sélectionner/déselectionner les lignes visibles"/></th>
          <th class="author">Auteur</th>
          <th class="year">Date</th>
          <th class="title">Titre</th>
          <th class="occs">Occurrences</th>
          <th class="chapters">Chapitres</th>
          <th class="score">Score</th>
        </tr>
      </thead>
      <tbody>
  <%
facet = alix.facet(Alix.BOOKID, TEXT);
TopTerms dic = facet.topTerms(null, null, null);
IndexReader reader = alix.reader();
  
// loop on all books, get metas by document
int[] books = alix.books(SORT);


for (int i = 0, len = books.length; i < len; i++) {
  Document doc = reader.document(books[i], FIELDS);
  String bookid = doc.get(Alix.BOOKID);
  out.println("<tr>");
  out.println("<td class=\"checkbox\"><input type=\"checkbox\" name=\"book\" value=\""+bookid+"\"/></td>");
  out.print("<td class=\"author\">");
  String byline = doc.get("byline");
  if (byline != null) out.print(byline);
  out.println("</td>");
  out.println("<td class=\"year\">"+doc.get("year")+"</td>");
  out.println("<td class=\"title\">"+doc.get("title")+"</td>");
  dic.contains(bookid); // set internal pointer
  out.println("<td class=\"occs\">"+dic.length()+"</td>");
  out.println("<td class=\"chapter\">"+"</td>"); // +dic.weight()
  out.println("<td class=\"score\">"+"</td>"); // +dic.score()
  out.println("</tr>");
}
// TermQuery filterQuery = null;
// put metas
  %>
      </tbody>
    </table>
    </form>
    <script src="vendors/Sortable.js">//</script>
    <script src="static/js/corpus.js">//</script>
  </body>
</html>
<% out.println("<!-- "+(System.nanoTime() - time) / 1000000.0 + " ms. -->");%>