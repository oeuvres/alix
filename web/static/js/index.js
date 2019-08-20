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


// fill form
var url = new URL(window.location.href);
var q = url.searchParams.get("q");
var form = document.forms['qform'];
form['q'].value = q;

var tabs = document.getElementById('tabs').getElementsByTagName('a');
for (var i = 0; i < tabs.length; i++) {
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
  // get frame as a window object
  if (chrono.offsetHeight > 10 && chrono.offsetWidth > 10) {
    chrono.src = "chrono.html?q=" + q;
  }
  if (panel.offsetHeight > 10 && panel.offsetWidth > 10) {
    var url = new URL(panel.src);
    panel.src = url.pathname+"?q="+q;
  }
  return true;
}
