/** Update location bar */
function episode(key, value) {
  if (!key) return;
  // update url of top window if caller has given an id of doc
  const topUrl = top.location;
  var search = topUrl.search;
  var pars = new URLSearchParams(search);
  if (pars.has(key)) pars.delete(key);
  // null  value nullify the parameter
  if (value !== null) pars.append(key, value);
  top.history.replaceState(null, null, "?"+pars.toString());
}
//
