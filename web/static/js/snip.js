episode("id", null); // reset doc view
episode("view", "snip");
var form = document.getElementById('qform');
if (self != top) { // no form embedded in a frame
  form.style.display = 'none';
}
