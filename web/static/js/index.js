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
var form = document.getElementById("qform");
form['q'].value = q;
var chrono = document.getElementById("chrono");
var panel = document.getElementById("panel");
if (q) {
  form.submit();
  dispatch(form);
}
var tabs = document.getElementById('tabs').getElementsByTagName('a');
for (var i = 0; i < tabs.length; i++) {
  tabs[i].onclick = function(e) {
    form.action = this.href;
    form.submit();
    return false;
  }
}

function dispatch(form)
{
  var q = form['q'].value;
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

// handle event of hash changing, used to display current corpus
function hashing() {
  var hash = location.hash;
  var split = hash.split("corpus=");
  if (split.length > 1) {
    var corpus = split[1];
    var el = document.getElementById("corpus");
    if (!el) return;
    // unknown corpus
    if(corpus && !localStorage.getItem(corpus)) {
      location.hash = "";
      corpus = "";
    }

    var html = "";
    if (corpus) {
      html = "<button name=\"new\" onclick=\"this.form.action = 'corpus.jsp';\" title=\"Déselectionner la collection “"+name+"”\">🗙</button>" + corpus;
    }
    el.innerHTML = html;
    panel.src = panel.src;
    chrono.src = chrono.src;
  }
}
window.onhashchange = hashing;
hashing();
