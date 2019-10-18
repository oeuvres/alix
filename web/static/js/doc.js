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
  if (!hash || hash == "#") {
    return;
  }
  // hash exists as an id
  if (document.getElementById(hash)) {
    window.scrollBy(0, -100);
  }
  // seems a word
  else {
    var form = decodeURIComponent(hash);
    if (form[0] == "#") form = form.substring(1);
    url.hash = "";
    history.replaceState(null, null, url);
    hitoks(form);
  }
}

function clickTok(e)
{
  var a = e.target;
  if (!a.matches("a")) return;
  var classes = a.className.split(/ +/);
  var form = classes[1];
  if (winaside) {
    var url = new URL(winaside.src);
    url.hash = '#'+form;
    winaside.src = url;
  }
  hitoks(form);
}

if (text) text.addEventListener('click', clickTok, true);
function hitoks(form)
{
  var matches = text.querySelectorAll("a."+form);
  for (var i = 0, len = matches.length; i < len; i ++) {
    var el = matches[i];
    var classes = el.className.split(/ +/);
    if (classes[3]) delete classes[3];
    else classes[3]="tokhi";
    el.className = classes.join(" ");
  }

}
