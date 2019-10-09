var cloudId = "wordcloud2";
var url = new URL(window.location.href);

const PATHNAME = url.pathname;
const SORTER = "sorter";
var sorter = url.searchParams.get(SORTER);
if (sorter) localStorage.setItem(PATHNAME + SORTER, sorter);
else if (sorter === "") localStorage.removeItem(PATHNAME + SORTER);
else sorter = "";

console.log(sorter);

// var log = url.searchParams.get("log");


fetch("freqlist.jsp?sorter="+sorter).then(
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
