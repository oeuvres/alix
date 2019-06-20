// open the split
var splitH = Split(['#aside', '#main'], {
  sizes: [15, 85],
  gutterSize: 10,
});
var splitV = Split(['#body', '#footer'], {
  sizes: [70, 30],
  direction: 'vertical',
  gutterSize: 9,
});
// fill the form
var url = new URL(window.location.href);
var start = url.searchParams.get("start");
var end = url.searchParams.get("end");
var q = url.searchParams.get("q");
// if (!q) q = "poème, poésie ; théâtre, acteur ; critique";
var form = document.forms['qform'];
form['q'].value = q;
if (start) form['start'].value = start;
if (end) form['end'].value = end;


// TODO, tabs
var page = document.getElementById('page');
var chrono = document.getElementById('chrono');
var panel = document.getElementById('panel');


function dispatch(form)
{
  var query = "?q=" + form.q.value + "&start=" + form.start.value  + "&end=" + form.end.value;
  chrono.src = "chrono.html" + query;
  panel.src = "facet.jsp" + query;
}
