// open the split
var splitH = Split(['#aside', '#main'], {
  sizes: [20, 80],
  gutterSize: 10,
});
var splitV = Split(['#body', '#footer'], {
  sizes: [70, 30],
  direction: 'vertical',
  gutterSize: 9,
});

// iframes
var page = document.getElementById('page');
var chrono = document.getElementById('chrono');
var panel = document.getElementById('panel');

// fill the form
var url = new URL(window.location.href);
var start = url.searchParams.get("start");
var end = url.searchParams.get("end");
var q = url.searchParams.get("q");

var form = document.forms['qform'];
if (start) form['start'].value = start;
if (end) form['end'].value = end;

if (!q) {
  q = "poème, poésie ; théâtre, acteur ; critique";
  form['q'].value = q;
  console.log("submit ?");
  form.submit();
  dispatch(form);
}
else {
  form['q'].value = q;
}




function dispatch(form)
{
  console.log("dispath ?");
  var query = "?q=" + form.q.value + "&start=" + form.start.value  + "&end=" + form.end.value;
  chrono.src = "chrono.html" + query;
  panel.src = "facet.jsp" + query;
}
