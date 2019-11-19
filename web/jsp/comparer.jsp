<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%
// parameters
String id1 = tools.getString("leftid", null);
int docId1 = tools.getInt("leftdocid", -1);
String id2 = tools.getString("rightid", null);
int docId2 = tools.getInt("rightdocid", -1);
String q = tools.getString("q", null);
int start = tools.getInt("start", -1); // to come back to main window


// global variables
Corpus corpus = (Corpus)session.getAttribute(corpusKey);

String url1;
String ref = "";
if (id1 != null) { // doc by id requested
  url1 = "compdoc?" + "id=" + id1;
  ref = "&amp;refid=" + id1;
}
else if (docId1 >= 0) { // doc by docid requested
  url1 = "compdoc?" + "docid=" + docId1;
  ref = "&amp;refdocid=" + docId1;
}
else if (q != null) { // query
  url1 = "meta?" + "q=" + q;
}
else { // query
  url1 = "meta";
}

String url2;
if (id2 != null) { // doc by id requested
  url2 = "compdoc?" + "id=" + id2 + ref;
}
else if (docId2 >= 0) { // doc by docid requested
  url2 = "compdoc?" + "docid=" + docId2 + ref;
}
else if (id1 != null) { // reference document for list or hilite
  url2 = "meta?refid=" + id1;
}
else if (docId1 >= 0) { // reference document for list or hilite
  url2 = "meta?refdocid=" + docId1;
}
else { // help
  url2 = "../static/doc/index.html";
}


%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Comparer, <%= (corpus != null) ? Jsp.escape(corpus.name())+", " : "" %><%=props.get("name")%> [Obvil]</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/js/common.js">//</script>
    <style>
body, html {
  height: 100%;
  margin: 0;
  padding: 0;
}
#cont {
  position: relative;
  background-color: red;
  height: 100%;
}
    </style>
  </head>
  <body class="comparer">
    <header id="header">
      <span class="base"><%=props.get("name")%> <%
   if (corpus != null) {
     String name = corpus.name();
     out.println("<mark><a title=\"Déselectionner ce corpus\" href=\"?corpus=new&amp;q="+Jsp.escUrl(q)+"\">🗙</a>  "+name+"</mark>");

   }
 %></span>
      <a class="logo" href="." title="Annuler les recherches en cours"><img alt="Obvil app" src="../static/img/obvil_50.png"/></a>
      <form id="qform" name="qform" action=".">
        <a href="." class="reset">⟲</a>
        <input type="hidden" name="start" value="<%= ((start > 0)?""+start:"") %>"/>
        <input type="hidden" name="hpp"/>
        <input id="q" name="q" autocomplete="off" value="<%=Jsp.escape(q)%>"
          oninput="this.form['start'].value=''; this.form['hpp'].value=''"
        />
        <button type="submit" name="send" tabindex="-1" class="magnify">⚲</button>
        <div id="tabs">
          <button name="view" value="corpus">Corpus</button>
          <button name="view" value="cloud">Nuage</button>
          <button name="view" value="freqs">Fréquences</button>
          <button name="view" value="snip">Extraits</button>
          <button name="view" value="kwic">Concordance</button>
          <a class="here" href="">Comparer</a>
        </div>
      </form>
    </header>
    <div id="win">
      <iframe id="left" name="left" src="<%=url1%>">
      </iframe>
      <iframe id="right" name="right" src="<%=url2%>">
      </iframe>
    </div>
    <script src="../static/js/comparer.js">//</script>
  </body>
</html>
