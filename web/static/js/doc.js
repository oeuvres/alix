if (self == top) { // no form embedded in a frame
  q = document.getElementById("q");
  q.type = "text";
}
window.onhashchange = function () {
  window.scrollBy(0, -100);
}
