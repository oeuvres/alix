function placeCursorAtEnd() {
  if (this.setSelectionRange) {
    // Double the length because Opera is inconsistent about
    // whether a carriage return is one character or two.
    var len = this.value.length * 2;
    this.setSelectionRange(len, len);
  } else {
    // This might work for browsers without setSelectionRange support.
    this.value = this.value;
  }

  if (this.nodeName === "TEXTAREA") {
    // This will scroll a textarea to the bottom if needed
    this.scrollTop = 999999;
  }
};

/*
window.onload = function() {
  var input = document.getElementById("q");

  if (input.addEventListener) {
    input.addEventListener("focus", placeCursorAtEnd, false);
  } else if (input.attachEvent) {
    input.attachEvent('onfocus', placeCursorAtEnd);
  }

  input.focus();
}
*/
