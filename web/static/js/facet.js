function activate()
{
  if (document.lastA) document.lastA.className = "";
  this.className = "active";
  document.lastA = this;
}
let links = document.getElementsByTagName("a");
for(let i = 0, limit = links.length; i < limit; i++) {
  let a = links[i];
  a.addEventListener('click', activate);
}
