<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@include file="prelude.jsp" %>
<%

// params
String[] checks = request.getParameterValues("book");
String json = tools.getString("json", null);
String q = tools.getString("q", null);
String id = tools.getString("id", null);
String view = tools.getString("view", null);
int start = tools.getInt("start", -1);
final boolean expression = tools.getBoolean("expression", false);

String url;

// pars
String pars = "";
if (q != null) {
  pars += "q=" + Jsp.escUrl(q);
  if (start > 1) pars += "&amp;start="+start;
  if (expression) pars += "&amp;expression=true";
}
if (id != null) {
  if (pars.length() > 0) pars += "&amp;";
  pars += "id="+id;
}

if (pars.length() > 0) pars = "?" + pars;

if (checks != null || json != null) {
  view = "corpus";
  url = view;
}
else if (id != null) {
  view = "doc";
  url = view + pars;
}
else if ("corpus".equals(view) || "snip".equals(view)  || "kwic".equals(view) || "freqs".equals(view) || "cloud".equals(view) ) {
  url = view + pars;
}
else if (q != null) {
  view = "kwic";
  url = view + pars;
}
else {
  view = "corpus";
  url = view;
}


//prepare a corpus ?
String js = "";
Corpus corpus = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  // handle paramaters to change the corpus
  String name = tools.getString("name", null);
  String desc = tools.getString("desc", null);
  if (name == null) name = "Ma sélection";
  if (checks != null) {
    corpus = new Corpus(alix, Alix.BOOKID, name, desc);
    corpus.add(checks);
    session.setAttribute(corpusKey, corpus);
    json = corpus.json();
    // corpus has been modified, store on client
    js += "corpusStore(\""+name+"\", \""+desc+"\", '"+json+"');\n";
  }
  //json send, client wants to load a new corpus
  else if (json != null) {
   corpus = new Corpus(alix, Alix.BOOKID, json);
   name = corpus.name();
   desc = corpus.desc();
   session.setAttribute(corpusKey, corpus);
  }
}
else if ("new".equals(tools.getString("corpus", null))) {
  session.setAttribute(corpusKey, null);
}
corpus = (Corpus)session.getAttribute(corpusKey);


%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title><%= (corpus != null) ? Jsp.escape(corpus.name())+", " : "" %><%=props.get("name")%> [Obvil]</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script> const base = "<%=base%>"; </script> <%-- give code of the text base for all further js  --%>
    <script src="../static/vendor/split.js">//</script>
    <script src="../static/js/common.js">//</script>
    <script src="../static/js/corpora.js">//</script>
    <script>
       <%=js %>
    </script>
  </head>
  <body class="split">
    <header id="header">
      <span class="base"><%=props.get("name")%> <%
   if (corpus != null) {
     String name = corpus.name();
     out.println("<mark><a title=\"Déselectionner ce corpus\" href=\"?corpus=new&amp;q="+Jsp.escUrl(q)+"\">🗙</a>  "+name+"</mark>");

   }
 %></span>
      <a class="logo" href="." title="Annuler les recherches en cours"><img alt="Obvil app" src="../static/img/obvil_50.png"/></a>
      <form id="qform" name="qform" onsubmit="return dispatch(this)" target="page" action="<%=view%>">
        <a href="." class="reset">⟲</a>
        <input type="hidden" name="start" value="<%= ((start > 0)?""+start:"") %>"/>
        <input type="hidden" name="hpp"/>
        <input id="q" name="q" autocomplete="off" autofocus="true" value="<%=Jsp.escape(q)%>"
          onfocus="this.setSelectionRange(this.value.length,this.value.length);"
          oninput="this.form['start'].value=''; this.form['hpp'].value=''"
        />
        <button type="submit" name="send" tabindex="-1" class="magnify">⚲</button>
        <div id="tabs">
          <a href="corpus" target="page">Corpus</a>
          <a href="snip" target="page">Extraits</a>
          <a href="kwic" target="page">Concordance</a>
          <a href="doc" target="page">Document</a>
          <a href="freqs" target="page">Fréquences</a>
          <a href="cloud" target="page">Nuage</a>
          <a href="comparer">Comparer</a>
        </div>
      </form>
    </header>
    <div id="win">
      <div id="aside">
        <iframe id="panel" name="panel" src="facet<%= pars %>">
        </iframe>
      </div>
      <div id="main">
        <div id="body">
          <iframe name="page" id="page" src="<%= url %>">
          </iframe>
        </div>
        <footer id="footer">
          <iframe id="chrono" name="chrono" src="chrono<%= pars %>">
          </iframe>
         </footer>
      </div>
    </div>
    <script src="../static/js/desk.js">//</script>
  </body>
</html>
