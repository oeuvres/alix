// get the url params
var url = new URL(window.location.href);
var q = url.searchParams.get("q");
if (!q) q = "";
var form = document.forms['qform'];
if (self != top) { // no form embedded in a frame
  form.style.display = "none";
} else {
  form.q.value = q;
}
var div = document.getElementById("chart");
var url = new URL(window.location.href);
var ticks;
// download the json data
var jsonUrl = "data/chrono.jsp?q="+q;
fetch(jsonUrl).then(
  function(response) {
    return response.json();
  }
).then(
  function(json) {
    ticks = json.ticks;
    draw(div, json.data, json.labels);
  }
);
/*
.catch(function(err) {
  console.log('Fetch problem: ' + err.message);
});
*/
// function for the ticker
var yearTicks = function(a, b, pixels, opts, dygraph, vals) {
  return ticks;
}
// draw the graph with all the configuration
function draw(div, data, labels) {
  attrs = {
    title : "Répartitions des occurrences sur le corpus (en ordre chronologique)",
    labels: labels,
    // labelsKMB: true,
    legend: "always",
    labelsSeparateLines: true,
    ylabel: "Nombre d'occurrences pour 100 000 mots",
    // xlabel: "Répartition des années en nombre de mots",
    showRoller: true,
    rollPeriod: localStorage.getItem('chronRollPeriod'),
    drawCallback: function() {
      localStorage.setItem('chronRollPeriod', this.rollPeriod());
    },
    series: {
      "Occurrences": {
        axis: 'y1',
        drawPoints: true,
        pointSize: 3,
        color: "rgba( 0, 0, 0, 0.4)",
        strokeWidth: 0.5,
        fillGraph: true,
      },
    },
    colors:['rgba(255, 0, 0, 0.5)', 'rgba(0, 0, 128, 0.5)', 'rgba(0, 128, 0, 0.5)', 'rgba(192, 128, 0, 0.5)', 'rgba(0, 128, 192, 0.5)'],
    strokeBorderWidth: 0.5,
    strokeWidth: 5,
    drawGapEdgePoints: true,

    // logscale: true,
    axes : {
      x: {
        independentTicks: true,
        drawGrid: true,
        ticker: yearTicks,
        // gridLineColor: "rgba( 128, 128, 128, 0.1)",
        // gridLineWidth: 1,
      },
      y: {
        independentTicks: true,
        drawGrid: true,
        // gridLineColor: "rgba( 128, 128, 128, 0.1)",
        // gridLineWidth: 1,
      },
    },
  };
  g = new Dygraph(div, data, attrs);
}
