window.onhashchange = function (e)
{
  let url = new URL(e.newURL);
  let hash = url.hash;
  return propaghi(hash);
}

function propaghi(hash)
{
  let text = decodeURIComponent(hash);
  if (text[0] == "#") text = text.substring(1);
  words = text.split(/[,;]/);
  for (let w of words) {
    // console.log(w);
  }
}
if (window.location.hash) propaghi(window.location.hash);
