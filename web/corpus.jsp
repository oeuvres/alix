<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="data/common.jsp" %>
<%!
final static DecimalFormat dfScoreFr = new DecimalFormat("0.00000", frsyms);
final static HashSet<String> FIELDS = new HashSet<String>();
static {
  for (String w : new String[] {Alix.BOOKID, "byline", "year", "title"}) {
    FIELDS.add(w);
  }
}
static Sort SORT = new Sort(new SortField("author1", SortField.Type.STRING), new SortField("year", SortField.Type.INT));
%>
<%
/*
Use cases of this page
 — creation : no local corpus, create a new one
 — select : no corpus in session, list of local corpus
 — modify : no query, corpus in session, edit
 — stats : query distribution by book 
*/

String name = getParameter(request, "name", "");
String desc = getParameter(request, "desc", "");
String json = getParameter(request, "json", null);
String[] checks = request.getParameterValues("book");
String botjs = ""; // javascript to add at the end
Set<String> bookids = null; // load the bookds to update the 
Facet facet;
// new corpus, do not load something
if (request.getParameter("new") != null) {
  corpus = null;
  name = "";
  desc = "";
  botjs += "informParent(); ";
}
// json send, client wants to load a new corpus
else if (json != null) {
  corpus = new Corpus(alix, Alix.BOOKID, json);
  name = corpus.name();
  desc = corpus.desc();
  session.setAttribute(CORPUS, corpus);
  bookids =  corpus.books();
  botjs += "informParent(\""+name+"\"); ";
}
// client send bookids
else if (checks != null) {
  corpus = new Corpus(alix, Alix.BOOKID, name, desc);
  corpus.add(checks);
  bookids = corpus.books(); // 
  session.setAttribute(CORPUS, corpus);
  json = corpus.json();
  // corpus has been modified, store on client
  botjs += "store(\""+name+"\", \""+desc+"\", '"+json+"');\n";
  botjs += "informParent(\""+name+"\"); ";
}
// load corpus from sesssion
else {
  corpus = (Corpus)session.getAttribute(CORPUS);
  if (corpus != null) {
    bookids = corpus.books();
    name = corpus.name();
    desc = corpus.desc();
    botjs += "informParent(\""+name+"\"); ";
  }
}
%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Corpus [Alix]</title>
    <link href="static/alix.css" rel="stylesheet"/>
    <script src="static/js/corpus.js">//</script>
  </head>
  <body class="corpus">
    <form method="post" id="corpora" action="?">
      <input type="hidden" name="json"/>
      <input type="hidden" name="q" value="<%=q%>"/>
      <ul id="corpusList"></ul>
    </form>
    <script type="text/javascript">corpusList("corpusList");</script>
    <main>
        <details id="filter">
          <summary>Filtres</summary>
          <label for="start">Années</label>
          <input id="start" name="start" type="number" min="<%=alix.min("year")%>" max="<%=alix.max("year")%>" placeholder="Début" class="year"/>
          <input id="end" name="end" type="number" min="<%=alix.min("year")%>" max="<%=alix.max("year")%>" placeholder="Fin" class="year"/>
          <br/><label for="author">Auteur</label>
          <input id="author" name="author" autocomplete="off" list="author-data" size="50" type="text" onclick="select()" placeholder="Nom, Prénom"/>
          <br/><label for="title">Titre</label>
          <input id="title" name="title" autocomplete="off" list="title-data" type="text" size="50" onclick="select()" placeholder="Chercher un titre"/>
        </details>

<%
IndexReader reader = alix.reader();
facet = alix.facet(Alix.BOOKID, TEXT, new Term(Alix.LEVEL, Alix.BOOK));
TermList qTerms = alix.qTerms(q, TEXT);
// no query
TopTerms dic = null;
boolean score = qTerms.size() > 0;
%>

      <form method="post" id="corpus" action="#">
        <table class="sortable" id="bib">
         <caption>
            <input type="hidden" name="q" value="<%=q%>"/>
            <button id="selection" type="button" title="Montrer les items sélectionnés">✔</button>
            <button id="all" type="button" title="Montrer tous les items">▢</button>
            Corpus <input type="text" size="10" id="name" name="name" value="<%=name%>" placeholder="Nom du corpus" required="required"/>
            <button style="float: right;"  name="save" type="submit">Enregistrer</button>
            <button style="float: right;" name="new" onclick="this.form.name = ' '" type="submit">Nouveau</button>
            <button style="float: right;" name="reload" type="button" onclick="window.location = window.location.href.split('#')[0];">Recharger</button>
         </caption>
          <thead>
            <tr>
              <th class="checkbox"><input id="checkall" type="checkbox" title="Sélectionner/déselectionner les lignes visibles"/></th>
              <th class="author">Auteur</th>
              <th class="year">Date</th>
              <th class="title">Titre</th>
              <th class="docs">Chapitres</th>
              <th class="length" title="Taille en mots">Taille</th>
            <% if (score) { %>
              <th class="occs">Occurrences</th>
              <th class="score">Score</th>
            <% } %>
            </tr>
          </thead>
          <tbody>
    <%
  if (score && corpus != null) {
    dic = facet.topTerms(corpus.bits(), qTerms, null);
  }
  else if (score) {
    dic = facet.topTerms(null, qTerms, null);
  }
  else {
    dic = facet.topTerms();
  }
  
  while (dic.hasNext()) {
    dic.next();
    int coverId = dic.cover();
    Document doc = reader.document(coverId, FIELDS);
    String bookid = doc.get(Alix.BOOKID);
    out.println("<tr>");
    out.println("  <td class=\"checkbox\">");
    out.print("    <input type=\"checkbox\" name=\"book\" id=\""+bookid+"\" value=\""+bookid+"\"");
    if (bookids != null && bookids.contains(bookid)) out.print(" checked=\"checked\"");
    out.println(" />");
    out.println("  </td>");
    out.print("  <td class=\"author\">");
    out.print("<label for=\""+bookid+"\">");
    String byline = doc.get("byline");
    if (byline != null) out.print(byline);
    out.print("</label>");
    out.println("</td>");
    out.println("  <td class=\"year\">"+doc.get("year")+"</td>");
    out.println("  <td class=\"title\">"+doc.get("title")+"</td>");
    out.println("  <td class=\"docs num\">"+dic.docs()+"</td>");
    out.println("  <td class=\"length num\">"+dfint.format(dic.length())+"</td>");
    if (score) {
      out.println("  <td class=\"occs num\">" +dic.occs()+"</td>"); 
      out.println("  <td class=\"score num\">" +dfScoreFr.format(dic.score())+"</td>"); 
    }
    out.println("</tr>");
  }
  // TermQuery filterQuery = null;
  // put metas
    %>
          </tbody>
        </table>
      </form>
    </main>

    <script src="vendors/Sortable.js">//</script>
              <datalist id="author-data">
    <%
    facet = alix.facet("author", TEXT);
    TopTerms facetEnum = facet.topTerms();
    while (facetEnum.hasNext()) {
      facetEnum.next();
      // long weight = facetEnum.weight();
      out.println("<option value=\""+facetEnum.term()+"\"/>");
    }
    %>

          </datalist>
          <datalist id="title-data">
    <%
    facet = alix.facet("title", TEXT);
    facetEnum = facet.topTerms();
    while (facetEnum.hasNext()) {
      facetEnum.next();
      // long weight = facetEnum.weight();
      out.println("<option value=\""+facetEnum.term()+"\"/>");
    }
    %>
          </datalist>

    <script>
bottomLoad();
<%
out.println(botjs);
if (corpus != null) out.println("showSelected();");
%>
    </script>
  </body>
</html>