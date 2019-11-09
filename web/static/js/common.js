/** Update an URL parameter in location bar of parent window if caller is in a frame */
function parTop(key, value) {
  if (!key) return;
  const topUrl = top.location;
  var search = topUrl.search;
  var pars = new URLSearchParams(search);
  // null value will nullify the parameter
  if (pars.has(key)) pars.delete(key);
  if (value !== null) pars.append(key, value);
  top.history.replaceState(null, null, "?"+pars.toString());
}
/** give  */
function url4name(link) {
  let parts = link.pathname.split('/');
  // if trailing slash, name will be empty
  let name = parts.pop();
  return name.replace(/\.[^/.]+$/, "");
}

/** Inform parent window (desk) of view */
if (self != top) {
  var topName = url4name(top.location);
  var selfName = url4name(window.location);
  // the compare view
  if (topName == "comparer") {
    // to style left or right frame
    window.addEventListener('load', function () {
      document.body.className += " "+window.name;
      document.documentElement.className += " "+window.name;
    });
    var key = window.name+"id";
    if (window.id) parTop(key, window.id);
  }
  // probably the desk
  else {
    switch(selfName) {
      case "facet":
      case "chrono":
        break;
      case "doc":
        parTop("view", selfName);
        top.document.body.className = "split view_"+selfName;
        // id should be defined by server who knows if doc exists
        if (window.id) {
          let id = window.id;
          parTop("id", id);
          var butComp = top.document.querySelector("#tabs .comparer");
          if (butComp) butComp.href = butComp.pathname + "?leftid=" + id;
        }
        break;
      default:
        parTop("view", selfName);
        top.document.body.className = "split view_"+selfName;
        parTop("id", null);
        break;
    }
    // console.log(window.id);
  }
}
else { // for the views outside
  let input = document.getElementById("q");
  if (input && input.type == "hidden") input.type = "text";
}
