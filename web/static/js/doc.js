if (self == top) // no form embedded in a frame
{
  q = document.getElementById("q");
  if (q && q.type == "hidden") q.type = "text";
}

var text = document.getElementById("contrast");
window.onhashchange = function (e)
{
  let url = new URL(e.newURL);
  let hash = url.hash;
  let id = decodeURIComponent(hash);
  if (id[0] == "#") id = id.substring(1);
  if (!id) return;
  // hash exists as an id
  if (document.getElementById(id)) {
    window.scrollBy(0, -100);
  }
}

// a set of colors for hilite tokens
const styleMap = {
  "tk1":true,
  "tk2":true,
  "tk3":true,
  "tk4":true,
  "tk5":true,
};

function getStyle()
{
  for (var style in styleMap) {
    if(!styleMap.hasOwnProperty(style)) continue;
    if(styleMap[style]) return style;
  }
  // return default
  return style;
}

function clickTok(e)
{
  let a = e.target;
  if (!a.matches("a")) return;
  let aStyles = a.className.split(/ +/);
  let form = aStyles[1];
  let style = null;
  if (aStyles[3]) { // free a class name
    styleMap[aStyles[3]] = true;
  }
  else {
    style = getStyle();
  }
  let sibling = "right";
  if (window.name == "right") sibling = "left";
  let win;
  let count = 0;
  if(window.parent.frames[sibling].hitoks) {
    win = window.parent.frames[sibling];
    count += win.hitoks(form, style);
  }
  count += hitoks(form, style);
  if (count && style) { // style used, block it
    if (styleMap.hasOwnProperty(style)) styleMap[style] = false;
    if (win) win.styleMap = styleMap;
  }

}



if (text) text.addEventListener('click', clickTok, true);
var rulhi = document.getElementById("rulhi");
function hitoks(form, style)
{
  let count = 0;
  let matches = text.querySelectorAll("a."+form);
  for (let i = 0, len = matches.length; i < len; i ++) {
    let el = matches[i];
    let classes = el.className.split(/ +/);
    if (style) classes[3] = style;
    else delete classes[3];
    el.className = classes.join(" ");
    let tokid = el.id;
    let n = 0 + tokid.substring(3);
    let tickid="kot"+n;
    // delete tick
    if (!style) {
      let el = document.getElementById(tickid);
      if (el) el.remove();
    }
    else {
      count++;
      let a = document.createElement("a");
      a.setAttribute("href", "#"+tokid);
      a.className = style;
      let perc = Math.round(1000 * n / rulhiLength) / 10;
      a.setAttribute("style", "top:"+perc+"%;");
      a.id = tickid;
      rulhi.append(a);
    }
  }
  return count;
}
