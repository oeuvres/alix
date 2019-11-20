var cloudId = "wordcloud2";
var url = new URL(window.location.href);

let pars = [];

const SORT = "sort";
var sort = url.searchParams.get(SORT);
if (sort) pars.push(SORT+"="+sort);

const CAT = "cat";
var cat = url.searchParams.get(CAT);
if (cat) pars.push(CAT+"="+cat);

const COUNT = "count";
// cout maybe set from server
if(!count) count = url.searchParams.get(COUNT);
if (count) pars.push(COUNT+"="+count);

const Q = "q";
var q = url.searchParams.get(Q);
if (q) pars.push(Q+"="+encodeURIComponent(q));

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
    minRotation: -Math.PI / 3.5,
    maxRotation: Math.PI / 3.5,
    rotationSteps: 6,
    rotateRatio: 1,
    shuffle: false,
    shape: 'square',
    fontFamily: 'Roboto, Lato, Helvetica, "Open Sans", sans-serif',
    gridSize: 6,
    color: null,
    fontWeight: function(word, weight, fontSize) {
      var ratio = (fontSize - fontMin) / (fontMax - fontMin);
      var bold = 300 + Math.round(ratio * 16) * 50;
      return "" + bold;
    },
    backgroundColor: null,
    opacity : function(word, weight, fontSize) {
      var ratio = (fontSize - fontMin) / (fontMax - fontMin);
      var ratio = 1 - Math.pow( 1 - (ratio), 1.4);
      const dec = 100;
      let opacity = Math.round(dec * (1 - ratio * 0.9)) / dec;
      return opacity;
    },
  });
}
