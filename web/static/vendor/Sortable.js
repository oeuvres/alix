/**
 * © 2009, frederic.glorieux@fictif.org et École nationale des chartes
 * © 2012, frederic.glorieux@fictif.org
 * © 2015, frederic.glorieux@fictif.org et LABEX OBVIL
 *
 * This program is a free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License
 * http://www.gnu.org/licenses/lgpl.html
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 */
/**
<h1>Sorting tables, short and fast.</h1>

Examples
<ul>
  <li><a href="http://developpements.enc.sorbonne.fr/diple/modules/xmlstats/?corpus=Littr%C3%A9,%20Nuances&taglist=">http://developpements.enc.sorbonne.fr/diple/modules/xmlstats/</a></li>
</ul>


<p>
  On a today 2011 laptop, less than 1s. on 10 000 rows. Idea
</p>

<b>onLoad</b>
<ul>
  <li>Create a String Array image of a table.</li>
  <li>For each row, keep the html as a String object in the Array.</li>
  <li>For each cell, store a sort key as an Attribute of the String image of the row.</li>
  <li>Add onClick events on top cells</li>
</ul>
<b>onClick</b>
<ul>
  <li>Modify the String.prototype.toString to return the key of the requested column.</li>
  <li>Sort the String array, image of the table, with the regular javascript sort() method. Sorting will be done on what will return the toString() method, modified upper, so that sort will be donne on a column sort key, and not the html of a row.</li>
  <li>(restore String.prototype.toString)</li>
  <li>The array will now have all the html row in the right order, affect it as html for the table, tbody.innerHTML=tbody.lines.join("\n");</li>
</ul>

Credit :

<ul>
  <li><a href="http://blog.vjeux.com/2009/javascript/speed-up-javascript-sort.html">http://blog.vjeux.com/2009/javascript/speed-up-javascript-sort.html</a></li>
  <li><a href="http://www.joostdevalk.nl/code/sortable-table/">http://www.joostdevalk.nl/code/sortable-table/</a></li>
  <li><a href="http://www.kryogenix.org/code/browser/sorttable/">http://www.kryogenix.org/code/browser/sorttable/</a></li>
</ul>
*/
if (!document.createElementNS) document.createElementNS = function(uri, name) {
  return document.createElement(name);
};

var Sortable = {
  /** last key */
  lastKey : 'key0',
  /**
   * call on the load document event, loop on table to put sort arrows
   */
  load: function()
  {
    // Find all tables with class sortable and make them sortable
    if (!document.getElementsByTagName) return;
    tables = document.getElementsByTagName("table");
    for (var i=tables.length - 1; i >= 0; i--) {
      table = tables[i];
      if (((' ' + table.className.toLowerCase() + ' ').indexOf("sortable") != -1)) {
        Sortable.create(table);
      }
    }
  },
  /**
   * Sort a prepared table. For string values, the trick is to modify the String.toString() method
   * so that we can give what we want for table row <tr>, especially a prepared sort key
   * for the requested row.
   */
  sort: function(table, col, desc)
  {
    var tbody = table.tBodies[0],
    arr = Array.prototype.slice.call(tbody.rows, 0),
    ret = (desc)?-1:1;
    arr.sort(function(rowA, rowB) {
      a = rowA.keys[col];
      b = rowB.keys[col];
      if (a > b) return ret;
      else if (a == b) return 0;
      else return -ret;
    });
    for(var i = 0, len = arr.length; i < len; i++) {
      tbody.appendChild(arr[i]);
    }
    // zebra the table after sort
    Sortable.zebra(table);
  },

  /**
   * Hide lines not equal to a value.
   * Selected values
   */
  filter: function(table, col, filter)
  {
    tbody = table.tBodies[0];
    filter = Sortable.key(filter);
    for (i = tbody.rows.length-1; i >=0; i--) {
      row = tbody.rows[i];
      value = row.keys[col];
      if (value.search(filter) >= 0) row.style.display = "table-row";
      else if (!row.style.display) row.style.display = "none";
    }
  },
  /**
   * Hide lines inferior to a value.
   */
  range: function(table, col, min, max)
  {
    tbody = table.tBodies[0];
    for (i = tbody.rows.length-1; i >=0; i--) {
      row = tbody.rows[i];
      value = row.keys[col];
      if (min === "") {
        if (max === "") row.style.display = "";
        else if (value <= max) row.style.display = "";
        else row.style.display = "none";
      }
      else if (max === "") {
        if (min === "") row.style.display = "";
        else if (value >= min) row.style.display = "";
        else row.style.display = "none";
      }
      else {
        if (value < min) row.style.display = "none";
        else if (value > max) row.style.display = "none";
        else if (row.style.display) row.style.display = "";
      }
    }
  },
  /**
   * Show all rows (after the filter selection)
   */
  showAll: function(table)
  {
    tbody = table.tBodies[0];
    for (i = tbody.rows.length-1; i >=0; i--) {
      row = tbody.rows[i];
      row.style.display = "";
    }
  },
  /**
   * normalize table, add events to top cells, take a copy of the table as an array of string
   */
  create: function(table)
  {
    // already done
    if (table.sortable) return false;
    // not enough rows, go away
    if (table.rows.length < 2) return false;
    // if no tHead, create it with first row
    if (!table.tHead) {
      table.createTHead().appendChild(table.rows[0]);
    }
    firstRow = table.tHead.rows[0];
    // We have a first row: assume it's the header, and make its contents clickable links
    var i = firstRow.cells.length;
    while (--i >= 0) (function (i) { // hack to localize i
      var cell = firstRow.cells[i];
      var text = cell.innerHTML.replace(/<.+>/g, '');
      if (cell.className.indexOf("unsort") != -1 || cell.className.indexOf("nosort") != -1 || Sortable.trim(text) == '') return;
      cell.className = cell.className+' sorting';
      cell.addEventListener('click', function () {
        Sortable.sort(table, i, cell.desc);
        if (cell.desc) cell.className = cell.className.replace(/ asc/, "") + " desc";
        else cell.className = cell.className.replace(/ desc/, "") + " asc";
        cell.desc = !cell.desc;
      });
    }(i));
    if (!table.tBodies) {
      tbody = table.createTBody();
      for (var i = 1, len = table.rows.length; i < len; i++) tbody.appendChild(table.rows[i]);
    }
    else tbody = table.tBodies[0];
    // for each line, prepare a key to use for sorting
    for (var i = 0, len = tbody.rows.length; i < len; i++) {
      var row = tbody.rows[i];
      Sortable.paint(row, i+1);
      row.keys = [];
      // prepare the key
      for (var j = 0, len2 = row.cells.length; j < len2 ; j++) {
        row.keys[j] = Sortable.key(row.cells[j]);
      }
    }
    // do it one time
    table.sortable=true;
    return true;
  },
  /**
   * build sortable key from element content
   */
  key: function(text)
  {
    if (!text) return "";
    if (typeof text == 'string');
    else if (text.hasAttribute("sort")) text=text.getAttribute("sort");
    else if (text.hasAttribute("data-sort")) text=text.getAttribute("data-sort");
    else if (text.textContent) text=text.textContent;
    else text = text.innerHTML.replace(/<.+>/g, '');
    // innerText could be very slow (infers CSS visibility)
    text=Sortable.trim(text);
    // num
    n=parseFloat(text.replace(/,/g, '.').replace(/[  x×/]/g, ''));
    // text
    if (isNaN(n)) {
      text=text.toLowerCase().replace(/’/, "'").replace(/^(d'|de |le |les |la |l')/, '').replace(/œ/g, 'oe').replace(/æ/g, 'ae').replace(/ç/g, 'c').replace(/ñ/g, 'n').replace(/[éèêë]/g, 'e').replace(/[áàâä]/g, 'a').replace(/[íìîï]/g, 'i').replace(/úùûü/, 'u').replace(/\W/g, '') ;
      // +"__________" still usefull ?
      return text;
    }
    else {
      return n;
    }
  },
  /**
   * add alternate even odd classes on table rows
   */
  zebra: function (table)
  {
    var n = 1;
    for (var i = 0; i < table.tBodies.length; i++) {
      for (var j=0, len = table.tBodies[i].rows.length; j < len; j++) {
        var row = table.tBodies[i].rows[j];
        if (row.style.display == "none") continue;
        this.paint(row, n);
        n++;
      }
    }
  },
  /**
   * Paint a row according to its index
   */
  paint: function(row, i) {
    row.className = " "+row.className+" ";
    row.className = row.className.replace(/ *(odd|even|mod3|mod5|mod10|\d+) */g, ' ');
    if ((i % 2) == 1) row.className+=" even";
    else if ((i % 2) == 0) row.className+=" odd";
    if ((i % 5) == 1) row.className+=" mod1";
    if ((i % 5) == 3) row.className+=" mod3";
    if ((i % 5) == 0) row.className+=" mod5";
    if ((i % 10) == 0) row.className+=" mod10";
    // row.className=row.className.replace(/^\s\s*|\s(?=\s)|\s\s*$/g, ""); // normalize-space, will bug a bit on \n\t
  },
  /**
   * A clever and fast trim, http://flesler.blogspot.com/2008/11/fast-trim-function-for-javascript.html
   */
  trim : function (s)
  {
    var start = -1,
    end = s.length;
    while(s.charCodeAt(--end) < 33);
    while(s.charCodeAt(++start) < 33);
    return s.slice(start, end + 1);
  },
  /**
   * A light csv parser (not clever enough for "tab \t in cell"\t"cell tab separated"
   */
  csv2table: function(srcid, dstid)
  {
    if (srcid.contentWindow && srcid.contentWindow.document) {
      var txt = srcid.contentWindow.document.body.childNodes[0].innerHTML;
    }
    if (!txt) return;
    var dst = document.getElementById(dstid);
    if (!dst) return;
    var html = [];
    html[0] = '<table class="sortable">';
    txt = txt.replace("\r\n", "\n").replace("\r", "\n");
    var lines = txt.split("\n");
    var cells;
    for (var i = 0; i < lines.length; i++) {
      cells = lines[i].split(/"?\t"?/);
      // header line
      if (0 == i) {
        html[i+1] = "<tr><th>" + cells.join("</th><th>") + "</th></tr>";
      }
      else {
        html[i+1] = "<tr><td>" + cells.join("</td><td>") + "</td></tr>";
      }
    }
    html[lines.length + 1] = "</table>";
    dst.innerHTML= html.join("\n");
  }
}

// if loaded as bottom script, create tables
if(window.document.body) Sortable.load();
