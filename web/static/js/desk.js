// open the split
var splitH = Split(['#aside', '#main'], {
  sizes: [20, 80],
  gutterSize: 3,
});
var splitV = Split(['#body', '#footer'], {
  sizes: [70, 30],
  direction: 'vertical',
  gutterSize: 3,
});


var form = document.getElementById("qform");
/*
// fill form
var url = new URL(window.location.href);
var q = url.searchParams.get("q");
form['q'].value = q;
if (q) {
  form.submit();
  dispatch(form);
}
*/


var tabs = document.getElementById('tabs').getElementsByTagName('a');
for (var i = 0; i < tabs.length; i++) {
  var name = url4name(tabs[i]);
  tabs[i].id = name;
  tabs[i].className = name;
  // do not add an event to a tab that will not go in frame
  if (!tabs[i].target) continue;
  tabs[i].onclick = function(e) {
    form.action = this.href;
    form.submit();
    return false;
  }
}

var chrono = document.getElementById("chrono");
var panel = document.getElementById("panel");
function dispatch(form)
{
  var q = form['q'].value;
  console.log(q);
  parTop("q", q); // update URL
  // get frame as a window object
  if (chrono.offsetHeight > 10 && chrono.offsetWidth > 10) {
    chrono.src = "chrono.jsp?q=" + q;
  }
  if (panel.offsetHeight > 10 && panel.offsetWidth > 10) {
    var url = new URL(panel.src);
    panel.src = url.pathname+"?q="+q;
  }
  return true;
}
