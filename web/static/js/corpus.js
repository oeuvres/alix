/** init page */
function init()
{
  console.log("?");
  var checks = document.getElementsByName("book");
  for (var i = 0, length = checks.length; i < length; i++) {
    checks[i].addEventListener('click', function(e) {
      console.log(this.checked);
    });
  }
}

init();
/*
Selon performance
var req = new Request('data/facet.jsp?field=author');
fetch(req)
  .then(function(response) { return response.json(); })
  .then(function(data) {
    var datalist = document.getElementById("author-list")
    for (var i = 0; i < data.products.length; i++) {
      var listItem = document.createElement('li');
      listItem.innerHTML = '<strong>' + data.products[i].Name + '</strong> can be found in ' +
                           data.products[i].Location +
                           '. Cost: <strong>£' + data.products[i].Price + '</strong>';
      myList.appendChild(listItem);
    }
  });
*/
