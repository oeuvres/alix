var table;
const authors = {};
const titles = {};
var checks;
function bottomLoad() {
  var el;
  table = document.getElementById("bib");
  var nl = document.querySelectorAll('#author-data option');
  for (var i = 0, len = nl.length; i< len; i++) {
    authors[nl[i].value] = true;
  }
  el = document.getElementById("author");
  el.addEventListener('input', authorFilter);
  el.addEventListener('focus', authorFilter);

  el = document.getElementById("selection");
  el.addEventListener('click', function(evt) {
    showSelected();
  });

  el = document.getElementById("start");
  el.addEventListener('input', range);
  el.addEventListener('focus', range);

  el = document.getElementById("end");
  el.addEventListener('input', range);
  el.addEventListener('focus', range);

  var nl = document.querySelectorAll('#title-data option');
  for (var i = 0, len = nl.length; i< len; i++) {
    titles[nl[i].value] = true;
  }

  el = document.getElementById("title");
  el.addEventListener('keydown', titleEnter);
  el.addEventListener('input', titleFilter);
  el.addEventListener('focus', titleFilter);

  el = document.getElementById("all");
  el.addEventListener('click', function(evt) {
    Sortable.showAll(table);
    Sortable.zebra(table);
  });

  el = document.getElementById("checkall");
  el.addEventListener('click', checkAll)

  el = document.getElementById("none");
  el.addEventListener('click', none);

  checks = document.getElementsByName("book");
  for (var i = 0, length = checks.length; i < length; i++) {
    var check = checks[i];
    check.n = i;
    check.addEventListener('click', checkRange);
  }
}

var checkLast;
function checkRange(evt) {
  if (!evt.shiftKey && !evt.ctrlKey) {
    checkLast = this;
    return true;
  }
  if (!checkLast) return true;
  var checked = this.checked;
  if (checkLast.checked != checked) return true;
  var min = Math.min(this.n, checkLast.n);
  var max = Math.max(this.n, checkLast.n);
  for(var i = min; i <= max; i++) {
    if (checks[i].offsetParent === null) continue; // invisible
    checks[i].checked = checked;
  }
  checkLast = this;
}

function showSelected() {
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

function authorFilter(evt) {
  if (!authors[this.value]) return true;
  Sortable.filter(table, 1, this.value);
  Sortable.zebra(table);
}

function titleFilter(evt) {
  if (!titles[this.value]) return true;
  Sortable.filter(table, 3, this.value);
  Sortable.zebra(table);
  this.value = "";
}

// intercept enter to search for words
function titleEnter(evt) {
  // return false;
  if (evt.key != 'Enter') return true;
  Sortable.filter(table, 3, this.value);
  Sortable.zebra(table);
  this.value = "";
  return false;
}

function range(evt) {
  if (!this.validity.valid) return true;
  if (evt.type == "focus" && this.value === "") return true;
  Sortable.range(table, 2, start.value, end.value);
  Sortable.zebra(table);
}

function checkAll(evt) {
  var checked = this.checked;
  for(var i=0, len=checks.length; i < len; i++) {
    if (checks[i].offsetParent === null) continue; // invisible
    checks[i].checked = checked;
  }
}

function none(evt) {
  for(var i=0, len=checks.length; i < len; i++) {
    checks[i].checked = false;
  }
  showSelected();
}

/** Store corpus bookid as json on response from server */
const CORPORA = "alix:corpora";
function store(name, desc, json) {
  var corpora =  JSON.parse(localStorage.getItem(CORPORA));
  if (!corpora) corpora = {};
  if (name) corpora[name] = desc;
  localStorage.setItem(CORPORA, JSON.stringify(corpora));
  if (json) localStorage.setItem(name, json);
}

function remove(name) {
  if (!name) return;
  var corpora = JSON.parse(localStorage.getItem(CORPORA));
  if (corpora) delete corpora[name];
  var json = JSON.stringify(corpora);
  localStorage.setItem(CORPORA, JSON.stringify(corpora));
  localStorage.removeItem(name);
  corpusList("corpusList");
}

function corpusList(id) {
  var el =  document.getElementById(id);
  var html = "";
  var corpora =  JSON.parse(localStorage.getItem(CORPORA));
  for (var name in corpora) {
    var desc = corpora[name];
    html += "<li><button type=\"submit\" name=\""+name+"\" onclick=\"send(this)\" title=\""+desc+"\">"+name+"</button> <b title=\"Suppriner la sélection “"+name+"”\" onclick=\"remove('"+name+"');\">🗙</b></li>\n";
  }
  el.innerHTML = html;
}

function send(button) {
  var json = localStorage.getItem(button.name);
  button.form['json'].value = json;
  return false;
}
