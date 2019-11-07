/**
 * Methods to control storing of corpus on client
 */
// storage key, needs the name of corpus, set by server
const CORPORA = "alix:"+base+":corpora";
/**
 * Store corpus as a json array of bookids
 * on response from server .
 * Client key on localStorage should be paramtrized by
 * document base.
 */
function corpusStore(name, desc, json) {
  var corpora =  JSON.parse(localStorage.getItem(CORPORA));
  if (!corpora) corpora = {};
  if (name) corpora[name] = desc;
  localStorage.setItem(CORPORA, JSON.stringify(corpora));
  if (json) localStorage.setItem(name, json);
}

function corpusRemove(name) {
  if (!name) return;
  var corpora = JSON.parse(localStorage.getItem(CORPORA));
  if (corpora) delete corpora[name];
  var json = JSON.stringify(corpora);
  localStorage.setItem(CORPORA, JSON.stringify(corpora));
  localStorage.removeItem(name);
  corpusList("corpusList");
}
