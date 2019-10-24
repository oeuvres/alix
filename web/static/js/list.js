var input= document.getElementById("q");
var nav = document.getElementById("chapters");
function resup(e)
{
  if (e.data == "^") return;
  var q = this.value.replace(/\s+/g, " ");
  var c = q.slice(-1);
  if (c != "*" && c != " ") q += "*"; // last word considered as prefix
  q = q.replace(/^ *| *$/g, "");
  if (q == this.last) return;
  this.last = q;
  console.log(q);
  var url = "meta.jsp?hpp=30&q="+ q
  fetch(url).then(function(response) {
    if (response.ok) return response.text();
    else console.log("ERROR "+response.status+" "+url);
  }).then(function(html) {
    nav.innerHTML = html;
  }).catch(function(err) {
    console.log('Fetch Error', err);
  });
}
/*
window.onload = function() {
  var input = document.getElementById("q");

  if (input.addEventListener) {
    input.addEventListener("focus", placeCursorAtEnd, false);
  } else if (input.attachEvent) {
    input.attachEvent('onfocus', placeCursorAtEnd);
  }

  input.focus();
}
*/
input.addEventListener('input', resup);
