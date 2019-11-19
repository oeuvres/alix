// get the url params
var url = new URL(window.location.href);
var form = document.forms['qform'];
var q = url.searchParams.get("q");
form['q'].value = q;
var start = url.searchParams.get("start");
if (start) form['start'].value = start;
var end = url.searchParams.get("end");
if (end) form['end'].value = end;
if (self != top) { // no form embedded in a frame
  form.style.display = "none";
} else {
  form.q.value = q;
}
var div = document.getElementById("chart");

var dots = url.searchParams.get("dots");
if (!dots) dots = "";
var ticks;
var legend;
function load(q) {
  // download the json data, only if a query
  if (!q) return;
  var jsonUrl = "chronojson?q="+encodeURIComponent(q)+"&dots="+dots;
  fetch(jsonUrl).then(
    function(response) {
      return response.json();
    }
  ).then(
    function(json) {
      ticks = json.ticks;
      legend = json.legend;
      draw(div, json.data, json.labels);
    }
  );
  return ticks;
}
load(q, dots);
var rollPeriod = localStorage.getItem('chronoRollPeriod');
if (!rollPeriod) rollPeriod = 20;
// function for the ticker
const yearTicks = function(a, b, pixels, opts, dygraph, vals) {
  return ticks;
}
const yearFormat = function(num) {
  // console.log(num);
  return legend[num].year;
}
const xClick = function(event, x, points) {
  if (!legend[x]) return; // no legend ?

  var href;
  let base = document.getElementsByTagName('base')[0];
  if(base && base.target) {
    win = top.frames[base.target];
  }
  if(base && base.href) {
    href = base.href;
  } else {
    href = "kwic";
  }
  href += "?q="+encodeURIComponent(q)+"&sort=year";
  href += "&start=" + (1 + legend[x].start);
  if(!win) win = self;
  win.location = href;
}

const hiCirc = function(g, name, ctx, cx, cy, color, radius) {
  ctx.beginPath();
  ctx.lineWidth = 3;
  ctx.strokeStyle = "#ea5b0c";
  ctx.fillStyle = "white";
  ctx.arc(cx, cy, radius, 0, 2 * Math.PI, false);
  // ctx.fill();
  ctx.stroke();
}

// draw the graph with all the configuration
function draw(div, data, labels) {
  attrs = {
    // title : "Répartitions des occurrences sur le corpus (en ordre chronologique)",
    labels: labels,
    // labelsKMB: true,
    legend: "always",
    labelsSeparateLines: true,
    // ylabel: "occurrence / 100 000 mots",
    // xlabel: "Répartition des années en nombre de mots",
    showRoller: true,
    rollPeriod: rollPeriod,
    clickCallback: xClick,
    drawCallback: function() {
      localStorage.setItem('chronoRollPeriod', this.rollPeriod());
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
    colors:['rgba(26, 26, 128, 0.5)', 'rgba(192, 128, 0, 0.5)', 'rgba(0, 128, 192, 0.5)', 'rgba(146,137,127, 0.7)'],
    strokeBorderWidth: 0.5,
    strokeWidth: 5,
    highlightCircleSize: 8,
    drawHighlightPointCallback : hiCirc,
    drawGapEdgePoints: true,
    logscale: true,

    // logscale: true,
    axes : {
      x: {
        independentTicks: true,
        drawGrid: true,
        ticker: yearTicks,
        valueFormatter: yearFormat,
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
