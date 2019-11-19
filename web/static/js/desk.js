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

const tabs = document.getElementById('tabs').getElementsByTagName('a');
for (var i = 0; i < tabs.length; i++) {
  var name = url4name(tabs[i]);
  tabs[i].id = name;
  tabs[i].name = name;
  tabs[i].className = name;
  // do not add an event to a tab that will not go in frame
  if (!tabs[i].target) continue;
  tabs[i].onclick = function(e) {
    form.action = this.href;
    form.submit();
    return false;
  }
}

function hiTab (name) {
  if (name == "facet" || name == "chrono") return false;
  for (var i = 0; i < tabs.length; i++) {
    tabs[i].className = tabs[i].className.replace(/ *here */, "");
    if (tabs[i].name == name) {
      tabs[i].className += " here";
    }
  }
}

var chrono = document.getElementById("chrono");
var panel = document.getElementById("panel");
function dispatch(form)
{
  console.log("dispatch");
  var q = form['q'].value;
  parTop("q", q); // update URL
  // get frame as a window object
  if (chrono.offsetHeight > 10 && chrono.offsetWidth > 10) {
    chrono.src = "chrono.jsp?q=" + encodeURIComponent(q);
  }
  if (panel.offsetHeight > 10 && panel.offsetWidth > 10) {
    var url = new URL(panel.src);
    panel.src = url.pathname+"?q=" + encodeURIComponent(q);
  }
  return true;
}
