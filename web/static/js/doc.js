var sibling;
// document in the compare widows
if (window.name == "right") sibling = window.parent.frames["left"];
else if (window.name == "left") sibling = window.parent.frames["right"];

var text = document.getElementById("text");

// scrol after anchor clicked
function scrollAnchor()
{
  let id = location.hash;
  if (!id) return;
  if (id[0] == "#") id = id.substring(1);
  if (!document.getElementById(id)) return;
  window.scrollBy(0, -100);
}
window.addEventListener('load', scrollAnchor);
window.addEventListener('hashchange', scrollAnchor);

// a set of colors for hilite tokens
var styleMap = {
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
  const styleArray = a.className.split(/ +/);
  const form = styleArray[0];
  // test if hilited
  const styleLast = styleArray[styleArray.length - 1];
  let style = null;
  if (typeof styleMap[styleLast] !== 'undefined') { // this is hilited, free a color
    styleMap[styleLast] = true;
  }
  else {
    style = getStyle();
  }
  let win;
  let count = 0;
  if(sibling && sibling.hitoks) {
    count += sibling.hitoks(form, style);
  }
  count += hitoks(form, style);
  if (count && style) { // style used, block it
    if (styleMap.hasOwnProperty(style)) styleMap[style] = false;
    if (win) {
      win.styleMap = styleMap;
    }
  }

}

/**
 * Hilite a block of words
 */
function clickSet(label)
{
  let style = "";
  if (label.lastClass) {
    if (styleMap.hasOwnProperty(label.lastClass)) styleMap[label.lastClass] = true;
    label.lastClass = null;
  }
  else {
    style = getStyle();
    label.lastClass = style;
    if (styleMap.hasOwnProperty(style)) styleMap[style] = false;
  }

  let matches = label.parentNode.querySelectorAll("a");
  for (let i = 0, len = matches.length; i < len; i ++) {
    let styleArray = matches[i].className.split(/ +/);
    let form = styleArray[0];
    var styleOld = styleArray[styleArray.length-1];
    // already painted, deete ticks before paint it
    if (typeof styleMap[styleOld] !== 'undefined') {
      styleMap[styleOld] = true;
      hitoks(form, null);
    }
    hitoks(form, style);
    if(sibling && sibling.hitoks) {
      sibling.hitoks(form, style);
      sibling.styleMap = styleMap;
    }
  }

}


if (text) text.addEventListener('click', clickTok, true);
var blocks = document.querySelectorAll('p.keywords'), i;
for (i = 0; i < blocks.length; ++i) {
  blocks[i].addEventListener('click', clickTok, true);
}

const rulhi = document.getElementById("rulhi");
function clickTick(e) {
  location.replace(this.href);
  return false;
}
function hitoks(form, style)
{
  let count = 0;
  let matches = document.querySelectorAll("a."+form);
  for (let i = 0, len = matches.length; i < len; i ++) {
    let el = matches[i];
    let classes = el.className.split(/ +/);
    // alway delete to avoir multiple colors
    var styleArray = [];
    for (let i = 0; i < classes.length; i++) {
      if (typeof styleMap[classes[i]] !== 'undefined') continue;
      styleArray.push(classes[i]);
    }
    if (style) styleArray.push(style);
    el.className = styleArray.join(" ");
    if (!el.id) continue;
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
      a.onclick = clickTick;
      let perc = Math.round(1000 * n / docLength) / 10;
      a.setAttribute("style", "top:"+perc+"%;");
      a.id = tickid;
      rulhi.append(a);
    }
  }
  return count;
}
