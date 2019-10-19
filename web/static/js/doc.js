if (self == top) // no form embedded in a frame
{
  q = document.getElementById("q");
  if (q && q.type == "hidden") q.type = "text";
}

var text = document.getElementById("contrast");
window.onhashchange = function (e)
{
  var url = new URL(e.newURL);
  var hash = url.hash;
  var id = decodeURIComponent(hash);
  if (id[0] == "#") id = id.substring(1);
  if (!id) return;
  // hash exists as an id
  if (document.getElementById(id)) {
    window.scrollBy(0, -100);
  }
}

// a set of colors for hilite tokens
var classtoks = [false, false, false, false, false];

function clickTok(e)
{
  var a = e.target;
  if (!a.matches("a")) return;
  var classes = a.className.split(/ +/);
  var form = classes[1];
  var classtok = null;
  if (classes[3]) { // free a class name
    var n = Number(classes[3].slice(-1));
    classtoks[n] = false;
  }
  else {
    for (var i = 0, len = classtoks.length; i < len; i++) {
      if(classtoks[i] == true) continue;
      classtoks[i] = true;
      classtok = "tk"+i;
      break;
    }
    if (!classtok) classtok = "tk0";
  }
  var sibling = "right";
  if (window.name == "right") sibling = "left";
  if(window.parent.frames[sibling].hitoks) {
    var win = window.parent.frames[sibling];
    win.classtoks = classtoks; // share same colors
    win.hitoks(form, classtok);
  }
  hitoks(form, classtok);
}

if (text) text.addEventListener('click', clickTok, true);
var rulhi = document.getElementById("rulhi");
function hitoks(form, classtok)
{
  var matches = text.querySelectorAll("a."+form);
  for (var i = 0, len = matches.length; i < len; i ++) {
    var el = matches[i];
    var classes = el.className.split(/ +/);
    if (classtok) classes[3] = classtok;
    else delete classes[3];
    el.className = classes.join(" ");
    var tokid = el.id;
    var n = 0 + tokid.substring(3);
    var tickid="kot"+n;
    // delete
    if (!classtok) {
      var el = document.getElementById(tickid);
      if (el) el.remove();
    }
    else {
      var a = document.createElement("a");
      a.setAttribute("href", "#"+tokid);
      a.className = classtok;
      var perc = Math.round(1000 * n / rulhiLength) / 10;
      a.setAttribute("style", "top:"+perc+"%;");
      a.id = tickid;
      rulhi.append(a);
    }
  }

}
