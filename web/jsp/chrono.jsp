<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Chronologie, Alix</title>
    <link rel="stylesheet" type="text/css" href="../static/alix.css"/>
  </head>
  <body class="chrono">
    <form id="qform">
      <input id="q" name="q" autocomplete="off" size="60" autofocus="true"/>
      Entre
      <input id="start" name="start" size="4"/>
      et
      <input id="end" name="end" size="4"/>
      <button>▶</button>
    </form>
    <div id="chart" class="dygraph"></div>
    <script src="../static/vendors/dygraph.min.js">//</script>
    <script src="../static/js/chrono.js">//</script>
</body>
</html>
