var checks = document.getElementsByName("book");
for (var i = 0, length = checks.length; i < length; i++) {
  checks[i].addEventListener('click', function(e) {
    console.log(this.checked);
  });
}
const authors = {};
var nl = document.querySelectorAll('#author-data option');
for (var i = 0, len = nl.length; i< len; i++) {
  authors[nl[i].value] = true;
}
var author = document.getElementById("author");
var table = document.getElementById("corpus");
author.addEventListener('input', function(evt) {
  var col = 1;
  var value = this.value;
  // value is not in datalist, not yet a selection
  if (!authors[this.value]) return true;
  Sortable.filter(table, col, value);
  return false;
});
var checkall = document.getElementById("checkall");
checkall.addEventListener('click', function(evt) {
  var checks = document.getElementsByName("book");
  var checked = this.checked;
  for(var i=0, len=checks.length; i < len; i++) {
    if (checks[i].offsetParent === null) continue; // invisible
    checks[i].checked = checked;
  }
});
var show = document.getElementById("show");
show.addEventListener('click', function(evt) {
  var col = 1;
  Sortable.filter(table, col, null);
});

/*
Selon performance
var req = new Request('data/facet.jsp?field=author');
fetch(req)
  .then(function(response) { return response.json(); })
  .then(function(data) {
    var datalist = document.getElementById("author-list")
    for (var i = 0; i < data.products.length; i++) {
      var listItem = document.createElement('li');
      listItem.innerHTML = '<strong>' + data.products[i].Name + '</strong> can be found in ' +
                           data.products[i].Location +
                           '. Cost: <strong>£' + data.products[i].Price + '</strong>';
      myList.appendChild(listItem);
    }
  });
*/
