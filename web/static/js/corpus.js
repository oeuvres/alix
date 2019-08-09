var table = document.getElementById("bib");
var showSelected = function() {
  var checks = document.getElementsByName("book");
  for (var i = 0, length = checks.length; i < length; i++) {
    // onload, ensure visibility of checked
    var check = checks[i];
    // get the <tr> container
    parent = check;
    while((parent = parent.parentNode)) {
      if (parent.nodeName.toLowerCase() !== "tr") continue;
      if (check.checked) parent.style.display = "table-row";
      else parent.style.display = "none";
      break;
    }
  }
  Sortable.zebra(table);
}

const authors = {};
var nl = document.querySelectorAll('#author-data option');
for (var i = 0, len = nl.length; i< len; i++) {
  authors[nl[i].value] = true;
}
var author = document.getElementById("author");
function authorFilter(evt) {
  if (!authors[this.value]) return true;
  Sortable.filter(table, 1, this.value);
  Sortable.zebra(table);
}
author.addEventListener('input', authorFilter);
author.addEventListener('focus', authorFilter);

const titles = {};
var nl = document.querySelectorAll('#title-data option');
for (var i = 0, len = nl.length; i< len; i++) {
  titles[nl[i].value] = true;
}
var title = document.getElementById("title");
function titleFilter(evt) {
  if (!titles[this.value]) return true;
  Sortable.filter(table, 3, this.value);
  Sortable.zebra(table);
  this.value = "";
}
// intercept enter to search for words
title.onkeydown = function(evt) {
  // return false;
  if (evt.key != 'Enter') return true;
  Sortable.filter(table, 3, this.value);
  Sortable.zebra(table);
  this.value = "";
  return false;
}
title.addEventListener('input', titleFilter);
title.addEventListener('focus', titleFilter);

var start = document.getElementById("start");
var end = document.getElementById("end");
function range(evt) {
  if (!this.validity.valid) return true;
  if (evt.type == "focus" && this.value === "") return true;
  Sortable.range(table, 2, start.value, end.value);
  Sortable.zebra(table);
}
start.addEventListener('input', range);
start.addEventListener('focus', range);
end.addEventListener('input', range);
end.addEventListener('focus', range);

var checkall = document.getElementById("checkall");
checkall.addEventListener('click', function(evt) {
  var checks = document.getElementsByName("book");
  var checked = this.checked;
  for(var i=0, len=checks.length; i < len; i++) {
    if (checks[i].offsetParent === null) continue; // invisible
    checks[i].checked = checked;
  }
});
var all = document.getElementById("all");
all.addEventListener('click', function(evt) {
  Sortable.showAll(table);
  Sortable.zebra(table);
});
var selection = document.getElementById("selection");
selection.addEventListener('click', function(evt) {
  showSelected();
});
/** Store corpus bookid as json on response from server */
const CORPORA = "alix:corpora";
var store = function(name, desc, json) {
  var corpora =  JSON.parse(localStorage.getItem(CORPORA));
  if (!corpora) corpora = {};
  if (name) corpora[name] = desc;
  localStorage.setItem(CORPORA, JSON.stringify(corpora));
  if (json) localStorage.setItem(name, json);
}
var remove = function(name) {
  if (!name) return;
  var corpora = JSON.parse(localStorage.getItem(CORPORA));
  if (corpora) delete corpora[name];
  localStorage.setItem(CORPORA, JSON.stringify(corpora));
  localStorage.removeItem(name);
}
var listCorpora = function(id) {
  var el =  document.getElementById(id);
  var html = "";
  var corpora =  JSON.parse(localStorage.getItem(CORPORA));
  for (var name in corpora) {
    var desc = corpora[name];
    html += "<li><button type=\"submit\" name=\""+name+"\" onclick=\"send(this)\" title=\""+desc+"\">"+name+"</button> <span onclick=\"remove('"+name+"'); parent = this.parentNode; parent.parentNode.removeChild(parent);\">🗙<span></li>\n";
  }
  el.innerHTML = html;
}
//display corpora list
listCorpora("listCorpora");
var send = function(button) {
  var json = localStorage.getItem(button.name);
  button.form['json'].value = json;
  return false;
}
