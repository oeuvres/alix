<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%@ page import="java.text.DecimalFormat" %>
<%@ page import="java.text.DecimalFormatSymbols" %>
<%@ page import="java.util.Locale" %>
<%@ page import="alix.lucene.search.Facet" %>
<%@ page import="alix.lucene.search.IntSeries" %>
<%@ page import="alix.lucene.search.TermList" %>
<%@ page import="alix.lucene.search.TopTerms" %>
<%!final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
final static DecimalFormat dfScoreFr = new DecimalFormat("0.00000", frsyms);
final static DecimalFormat dfint = new DecimalFormat("###,###,##0", frsyms);
final static HashSet<String> FIELDS = new HashSet<String>(Arrays.asList(new String[] {Alix.BOOKID, "byline", "year", "title"}));
static Sort SORT = new Sort(new SortField("author1", SortField.Type.STRING), new SortField("year", SortField.Type.INT));%>
<%

// params for this page
String q = tools.getString("q", null);
    
// global variables
Corpus corpus = (Corpus)session.getAttribute(corpusKey);
Set<String> bookids = null;
if (corpus != null) bookids = corpus.books();
Facet facet = alix.facet(Alix.BOOKID, TEXT, new Term(Alix.TYPE, DocType.book.name()));
IntSeries years = alix.intSeries(YEAR); // to get min() max() year
TermList qTerms = alix.qTermList(TEXT, q);
boolean score = (qTerms != null && qTerms.size() > 0);

FacetSort fallback = FacetSort.alpha;
if (score) fallback = FacetSort.score;
FacetSort sort = (FacetSort)tools.getEnum("ord", fallback, Cookies.corpusSort);


BitSet bits = bits(alix, corpus, q);
TopTerms dic = facet.topTerms(bits, qTerms, null);

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Corpus [Alix]</title>
    <link href="../static/vendor/sortable.css" rel="stylesheet"/>
    <link href="../static/obvil.css" rel="stylesheet"/>
    <script src="../static/js/common.js">//</script>
    <script type="text/javascript">
const base = "<%=base%>"; // give code of texts base to further Javascript
    </script>
    <script src="../static/js/corpus.js">//</script>
  </head>
  <body class="corpus">
    <main>
        <details id="filter">
          <summary>Filtres</summary>
          <form>
            <input type="hidden" name="q" value="<%=Jsp.escape(q)%>"/>
            <label for="ord">Tri par défaut</label>
            <select name="ord" onchange="this.form.submit()">
              <option/>
              <%= options(sort) %>
            </select>
          </form>
          <label for="start">Années</label>
          <input id="start" name="start" type="number" min="<%=years.min()%>" max="<%=years.max()%>" placeholder="Début" class="year"/>
          <input id="end" name="end" type="number" min="<%=years.min()%>" max="<%=years.max()%>" placeholder="Fin" class="year"/>
          <br/><label for="author">Auteur</label>
          <input id="author" name="author" autocomplete="off" list="author-data" size="50" type="text" onclick="select()" placeholder="Nom, Prénom"/>
          <br/><label for="title">Titre</label>
          <input id="title" name="title" autocomplete="off" list="title-data" type="text" size="50" onclick="select()" placeholder="Chercher un titre"/>
        </details>
      <form method="post" id="corpus" target="_top" action=".">
        <table class="sortable" id="bib">
         <caption>
            <%=  (bits != null)?bits.cardinality():facet.docsAll %> chapitres.
            <input type="hidden" name="q" value="<%=Jsp.escape(q)%>"/>
            <button style="float: right;" name="save" type="submit">Enregistrer</button>
            <input style="float: right;" type="text" size="10" id="name" name="name" value="<%= (corpus != null) ? Jsp.escape(corpus.name()) : "" %>"
            title="Donner un nom à cette sélection"
            placeholder="Nom ?"
            oninvalid="this.setCustomValidity('Un nom est nécessaire pour enregistrer votre sélection.')"
            oninput="this.setCustomValidity('')"
            required="required"/>
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

  // sorting
switch(sort){
  case alpha:
    dic.sort();
    break;
  case freq:
    if (score) dic.sortByOccs();
    else dic.sort();
    break;
  case score:
    if (score) dic.sortByScores();
    else dic.sort();
    break;
  default:
    dic.sort();
}
 
  // Hack to use facet as a navigator in results
  // get and cache results in facet order, find a index 
  TopDocs topDocs = getTopDocs(pageContext, alix, corpus, q, DocSort.author);
  int[] nos = facet.nos(topDocs);
  dic.setNos(nos);


  while (dic.hasNext()) {
    dic.next();
    int coverId = dic.cover();
    Document doc = reader.document(coverId, FIELDS);
    String bookid = doc.get(Alix.BOOKID);
    // for results, do not diplay not relevant results
    if (score && dic.occs() == 0) continue;

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
    out.println("  <td class=\"title\">");
    int n = dic.n();
    String href;
    // hpp?
    if (score) href = "kwic?sort=author&amp;q="+Jsp.escape(q)+"&amp;start="+(n+1);
    else href = "doc?sort=author&amp;start="+(n+1);
    out.print("<a href=\""+href+"\">");
    // out.println("<a href=\"kwic?sort="+facetField+"&amp;q="+q+"&start="+(n+1)+"&amp;hpp="+hits+"\">");
    out.print(doc.get("title"));
    out.println("</a>");
    out.println("  </td>");
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

    <script src="../static/vendor/Sortable.js">//</script>
              <datalist id="author-data">
    <%
    {
      facet = alix.facet("author", TEXT);
      dic = facet.topTerms();
      dic.sort();
      while (dic.hasNext()) {
        dic.next();
        // long weight = facetEnum.weight();
        out.println("<option value=\""+dic.term()+"\"/>");
      }
    }
    %>

          </datalist>
          <datalist id="title-data">
    <%
    {
      facet = alix.facet("title", TEXT);
      dic = facet.topTerms();
      dic.sort();
      while (dic.hasNext()) {
        dic.next();
        // long weight = facetEnum.weight();
        out.println("<option value=\""+dic.term()+"\"/>");
      }
    }
    %>
          </datalist>
    <a href="#" id="gotop">▲</a>
    <script>
bottomLoad();
<%
if (corpus != null) out.println("showSelected();");
%>
    </script>
  </body>
</html>
