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

function clickTok(e)
{
  var a = e.target;
  if (!a.matches("a")) return;
  var classes = a.className.split(/ +/);
  var form = classes[1];
  var tokhi = "tokhi";
  if (classes[3]) tokhi = null;
  var sibling = "right";
  if (window.name == "right") sibling = "left";
  if(window.parent.frames[sibling].hitoks) {
    window.parent.frames[sibling].hitoks(form, tokhi)
  }
  hitoks(form, tokhi);
}

if (text) text.addEventListener('click', clickTok, true);
function hitoks(form, tokhi)
{
  var matches = text.querySelectorAll("a."+form);
  for (var i = 0, len = matches.length; i < len; i ++) {
    var el = matches[i];
    var classes = el.className.split(/ +/);
    if (tokhi) classes[3] = tokhi;
    else delete classes[3];
    el.className = classes.join(" ");
  }

}
