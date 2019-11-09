var cloudId = "wordcloud2";
var url = new URL(window.location.href);

let pars = [];

const SORTER = "sorter";
var sorter = url.searchParams.get(SORTER);
if (sorter) pars.push(SORTER+"="+sorter);

const CAT = "cat";
var cat = url.searchParams.get(CAT);
if (cat) pars.push(CAT+"="+cat);

const HPP = "hpp";
var hpp = url.searchParams.get(HPP);
if (hpp) pars.push(HPP+"="+hpp);

const Q = "q";
var q = url.searchParams.get(Q);
if (q) pars.push(Q+"="+q);

let query = "";
if (pars.length > 0) query = "?"+pars.join('&');

fetch("freqs.json" + query).then(
  function(response) {
  return response.json();
  }
).then(
  function(json) {
    list = json.data;
    cloud(document.getElementById(cloudId), list);
  }
);
/*
.catch(function(err) {
  console.log('Fetch problem: ' + err.message);
});
*/
var fontMin = 14;
var fontMax = 80;
function cloud(div, list) {
  WordCloud(div, {
    list: list,
    fontMin: fontMin,
    fontMax: fontMax,
    // origin : [0, 0],
    // drawOutOfBound: false,
    // minSize : fontmin,
    minRotation: -Math.PI / 4,
    maxRotation: Math.PI / 4,
    rotationSteps: 5,
    rotateRatio: 1,
    shuffle: false,
    shape: 'square',
    fontFamily: 'Roboto, Lato, Helvetica, "Open Sans", sans-serif',
    gridSize: 6,
    color: null,
    fontWeight: function(word, weight, fontSize) {
      var ratio = (fontSize - fontMin) / (fontMax - fontMin);
      var bold = 300 + Math.round(ratio * 12) * 50;
      return "" + bold;
    },
    backgroundColor: null,
    opacity : function(word, weight, fontSize) {
      var ratio = (fontSize - fontMin) / (fontMax - fontMin);
      return 1 - ratio * 0.8;
    },
  });
}
