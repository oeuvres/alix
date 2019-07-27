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
  load: function() {
    // Find all tables with class sortable and make them sortable
    if (!document.getElementsByTagName) return;
    tables = document.getElementsByTagName("table");
    for (var i=tables.length - 1; i >= 0; i--) {
      table = tables[i];
      if (((' ' + table.className.toLowerCase() + ' ').indexOf("sortable") != -1)) {
        Sortable.create(table);
      }
    }
    Sortable.css();
  },
  css: function() {
    if (!document) return;
    var css = document.createElementNS("http://www.w3.org/1999/xhtml", "style");
    css.type = "text/css";
    css.innerHTML = "\
table.sortable {font-family: sans-serif; font-size: 12px; line-height: 105%; border: 1px solid; border-color: #CCCCCC; margin-top: 1rem; margin-bottom: 2em; border-collapse: collapse; } \
table.sortable caption {background-color: #F5F3EB; padding: 7px 1ex 5px 1ex; font-size: 16px; border-top: #FFFFFF 1px solid; border-left: #FFFFFF 1px solid; border-right: #FFFFFF 1px solid; color: #666; font-weight: bold; line-height: 1.2em; } \
table.sortable td {vertical-align: top; border-left: #BBD 1px solid; border-right: #BBD 1px solid; padding: 2px 1ex; color: #333; } \
table.sortable b {color: black; }\
table.sortable td.string {text-align: left; } \
tr.even {background-color: #FFFFFF; } \
tr.odd {background: -moz-linear-gradient(left, #EEE, #FFF 30%, #F5F3EB); background: -webkit-linear-gradient(left, #EEE, #FFF 30%, #F5F3EB); background: -ms-linear-gradient(left, #EEE, #FFF 30%, #F5F3EB); background: -o-linear-gradient(left, #EEE, #FFF 30%, #F5F3EB); background: linear-gradient(to right, #EEE, #FFF 30%, #F5F3EB); } /* #F5F3EB; */ \
tr.odd td {border-bottom: 1px solid #EEF; border-top: 1px solid #EEF } \
table.sortable th {text-align: center; vertical-align: middle; text-align: left; padding: 5px 1ex 5px 1ex; background-color: #FFFFFF; border-top: 2px solid #CCCCCC; border-bottom: 1px solid #666; box-shadow: 0 4px 2px -2px #99C; } \
table.sortable thead th {border-left: #BBD 1px solid; } \
table.sortable th.head, table.sortable td.head {vertical-align: bottom; } \
tr.even th, tr.odd th {text-align: right; } \
table.sortable tr.mod5 td {border-bottom: solid 1px rgba(171, 170, 164, 0.8); } \
table.sortable tr.mod10 td {border-bottom: solid 2px rgba(171, 170, 164, 0.5); box-shadow: 0 4px 2px -2px rgba(171, 170, 164, 0.8); } \
table.sortable tr:hover {background: #FFFFEE; color: black; } \
table.sortable tr:hover a {color: black; } \
table.sortable a {text-decoration: none; } \
table.sortable th a {display:block; } \
table.sortable a:hover {background: #FFFFFF; box-shadow: 0px 0px 20px #AAAAAA; } \
table.sortable th a:hover {background-color: #FFFFFF; border: none} \
th.num, table.sortable th.num {text-align: right; font-weight: 100; font-size: 85%; padding: 0 1px; } \
.sortheader {cursor: pointer; } \
/* .sortheader b {-webkit-touch-callout: none; -webkit-user-select: none; -khtml-user-select: none; -moz-user-select: none; -ms-user-select: none; user-select: none; } */ \
";
    var head = document.getElementsByTagName('head')[0];
    head.insertBefore(css, head.firstChild);
  },
  /**
   * A light csv parser (not clever enough for "tab \t in cell"\t"cell tab separated"
   */
  csv2table: function(srcid, dstid) {
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
  },
  /**
   * Sort a prepared table. For string values, the trick is to modify the String.toString() method
   * so that we can give what we want for table row <tr>, especially a prepared sort key
   * for the requested row.
   */
  sort: function(table, key, reverse) {
    // waited object not found, go out
    if (!table.lines) return;
    // get the first non empty value of the column
    /*
    var i=0;
    var max = table.lines.length;
    while (!table.lines[i][key] && i < max) i++;
    */
    // seems numerical key, do something ?
    // if (table.lines[i][key] === 0+table.lines[i][key])
    // table.reverse() does not seem to accept a sort function
    if (reverse) table.lines.sort(function(a, b) {
      if (a[key] > b[key]) return -1;
      else if (a[key] == b[key]) return 0;
      else return 1;
    });
    else table.lines.sort(function(a, b) {
      if (a[key] > b[key]) return 1;
      else if (a[key] == b[key]) return 0;
      else return -1;
    });
    /*
    // This hack was a quite efficient sort
    // save native String.toString()
    var save = String.prototype.toString;
    // localeCompare() is to slow, our ADCII key approach is better
    // set the method
    String.prototype.toString = function () {return this[key];};
    // do the sort
    if (reverse) table.lines.reverse();
    else table.lines.sort();
    // restore native String.toString()
    String.prototype.toString = save;
    */
    // affect the sorted <tr> to the table as a innerHTML
    // var tbody = table.getElementsByTagName('tbody')[0];
    table.tBodies[0].innerHTML= table.lines.join("\n");
    // zebra the table after sort
    Sortable.zebra(table);
  },
  /**
   * normalize table, add events to top cells, take a copy of the table as an array of string
   */
  create: function(table) {
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
    var cell;
    for (var i=0; i < firstRow.cells.length; i++) {
      cell = firstRow.cells[i];
      var text = cell.innerHTML.replace(/<.+>/g, '');
      if (cell.className.indexOf("unsort") != -1 || cell.className.indexOf("nosort") != -1 || Sortable.trim(text) == '') continue;
      cell.className = cell.className+' head';
      cell.table=table;
      cell.innerHTML = '<b class="sortheader" onclick="Sortable.sort(this.parentNode.table, \'key'+i+'\', this.reverse); this.reverse=!this.reverse ; return false;">↓'+text+'↑</b>'; //
    }
    if (!table.tBodies) {
      tbody = table.createTBody();
      var length = table.rows.length;
      for (i=1; i < len; i++) tbody.appendChild(table.rows[i]);
    }
    else tbody = table.tBodies[0];

    // to paint fast the sorted table, keep rows as an array of strings
    var row, s;
    table.lines=new Array();
    for (i = tbody.rows.length-1; i >=0; i--) {
      row = tbody.rows[i];
      Sortable.paint(row, i+1);
      // get the <tr> html as a String object
      s=new String(row.outerHTML || new XMLSerializer().serializeToString(row).replace(' xmlns="http://www.w3.org/1999/xhtml"', ''));
      // prepare the key
      for (k=row.cells.length -1; k>-1; k--) s['key'+k]=Sortable.key(row.cells[k]);
      table.lines[i]=s;
    }
    // do it one time
    table.sortable=true;
    return true;
  },
  /**
   * build sortable key from element content
   */
  key: function(text) {
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
  zebra: function (table) {
    for (var i = 0; i < table.tBodies.length; i++) {
      for (j=table.tBodies[i].rows.length -1; j >= 0; j--) this.paint(table.tBodies[i].rows[j], j);
    }
  },
  /**
   * Paint a row according to its index
   */
  paint: function(row, i) {
    row.className=" "+row.className+" ";
    row.className=row.className.replace(/ *(odd|even|mod5|mod10) */g, ' ');
    if ((i % 2) == 1) row.className+=" even";
    if ((i % 2) == 0) row.className+=" odd";
    if ((i % 5) == 3) row.className+=" mod3";
    if ((i % 5) == 0) row.className+=" mod5";
    if ((i % 10) == 0) row.className+=" mod10";
    // row.className=row.className.replace(/^\s\s*|\s(?=\s)|\s\s*$/g, ""); // normalize-space, will bug a bit on \n\t
  },
  /**
   * A clever and fast trim, http://flesler.blogspot.com/2008/11/fast-trim-function-for-javascript.html
   */
  trim : function (s){
    var start = -1,
    end = s.length;
    while(s.charCodeAt(--end) < 33);
    while(s.charCodeAt(++start) < 33);
    return s.slice(start, end + 1);
  }

}

// if loaded as bottom script, create tables
if(window.document.body) Sortable.load();
