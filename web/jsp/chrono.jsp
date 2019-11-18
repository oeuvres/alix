<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" trimDirectiveWhitespaces="true"%>
<%@ page import="alix.web.Jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Chronologie, Alix</title>
    <base target="page" href="kwic"/>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
  </head>
  <body class="chrono">
    <form id="qform">
        <input id="q" name="q" autocomplete="off" autofocus="true" 
          onfocus="this.setSelectionRange(this.value.length,this.value.length);"
        />
      <button type="submit" name="send" tabindex="-1" class="magnify">⚲</button>
      Entre
      <input id="start" name="start" size="4"/>
      et
      <input id="end" name="end" size="4"/>
    </form>
    <div id="chart" class="dygraph"></div>
    <script src="../static/vendor/dygraph.min.js">//</script>
    <script src="../static/js/chrono.js">//</script>
  </body>
</html>
