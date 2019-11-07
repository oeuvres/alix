/** Update an URL parameter in location bar of parent window if caller is in a frame */
function episode(key, value) {
  if (!key) return;
  if (self == top) return;
  const topUrl = top.location;
  var search = topUrl.search;
  var pars = new URLSearchParams(search);
  if (pars.has(key)) pars.delete(key);
  // null  value nullify the parameter
  if (value !== null) pars.append(key, value);
  top.history.replaceState(null, null, "?"+pars.toString());
}
