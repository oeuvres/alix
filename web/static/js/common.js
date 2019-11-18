/**
 * Update an URL parameter in location bar of parent window if caller is in a frame
 */
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

/**
 * Find name without extension from a location or a link object
 */
function url4name(link) {
  let parts = link.pathname.split('/');
  // if trailing slash, name will be empty
  let name = parts.pop();
  return name.replace(/\.[^/.]+$/, "");
}

/**
 * Store a parameter
 */

/**
 * Inform parent window (desk) of view
 */
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
    let url = location;
    let pars = new URLSearchParams(url.search);
    // update start index in general form
    let start = pars.get("start");
    let hpp = pars.get("hpp");
    let qform = top.document.forms[0];

    if (start && qform['start']) qform['start'].value = start;
    if (hpp && qform['hpp']) qform['hpp'].value = hpp;

    let panel = top.frames['panel'];
    let panDoc = panel.document || panel.contentWindow.document || panel.contentDocument;
    let panBase = panDoc.getElementsByTagName('base')[0];
    var cursorViews = {"snip":1, "kwic":1, "doc": 1};
    if(selfName in cursorViews) panBase.href = selfName;
    switch(selfName) {
      case "facet":
      case "chrono":
        break;
      case "doc":
        parTop("view", selfName);
        top.document.body.className = "split view_"+selfName;
        // id should be defined by server who knows if doc exists
        if (window.docId) {
          let id = window.docId;
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
  window.addEventListener('load', function () {
    let input = document.getElementById("q");
    if (input && input.type == "hidden") input.type = "text";
  });
}
