// open the split
var splitV = Split(['#header', '#middle', '#footer'], {
  sizes: [10, 80, 10],
  direction: 'vertical',
  gutterSize: 9,
});
var splitH = Split(['#aside', '#body'], {
  sizes: [20, 80],
  gutterSize: 10,
});
// TODO, tabs
