if (window.name) {
  document.body.className += " "+window.name;
  const topUrl = top.location;
  var search = topUrl.search;
  var key = window.name+"id";
  var pars = new URLSearchParams(search);
  if (pars.has(key)) pars.delete(key);
  top.history.pushState(null, null, "?"+pars.toString());
}

const input= document.getElementById("q");
const nav = document.getElementById("chapters");
/** update results */
function resup(e)
{
  if (e.data == "^") return;
  var q = this.value.replace(/\s+/g, " ");
  var c = q.slice(-1);
  if (c != "*" && c != " ") q += "*"; // last word considered as prefix
  q = q.replace(/^ *| *$/g, "");
  if (q == this.last) return;
  this.last = q;
  var url = "meta.htf?hpp=30&q="+ q
  fetch(url).then(function(response) {
    if (response.ok) return response.text();
    else console.log("ERROR "+response.status+" "+url);
  }).then(function(html) {
    nav.innerHTML = html;
  }).catch(function(err) {
    console.log('Fetch Error', err);
  });
}
if (input) input.addEventListener('input', resup);
