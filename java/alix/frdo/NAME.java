package alix.frdo;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import alix.fr.Tokenizer;
import alix.util.Occ;
import alix.util.OccRoller;
import alix.util.Term;

public class NAME
{
  /** Nombre de mots */
  private final int step;
  /** Où on écrit la concordance */
  private final PrintWriter html;
  /** Où on écrit les arc */
  private final PrintWriter csv;
  /** Liste des mots nœuds */
  static HashSet<String> NODES = new HashSet<String>();
  static {
    String l;
    try {
      BufferedReader buf = new BufferedReader(new InputStreamReader(new FileInputStream("savants.csv"), "UTF-8"));
      while ((l = buf.readLine()) != null) {
        l = l.trim();
        if (l.startsWith("#"))
          continue;
        NODES.add(l.trim());
      }
      buf.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /** Size on the left */
  final int left = -20;
  /** Size on the right */
  final int right = 20;

  public NAME(int step, PrintWriter html, PrintWriter csv) {
    this.step = step;
    this.html = html;
    this.csv = csv;
  }

  public void head()
  {
    csv.println("file\tdate\tSource\tTarget");
    html.println("<!doctype html>");
    html.println("<html>");
    html.println("  <head>");
    html.println("    <meta charset=\"utf-8\">");
    html.println("    <style>");
    html.println(
        "table.conc { font-family: sans-serif; color: #666; border-spacing : 2px; background-color: #EEEEEE; }");
    html.println(".conc td, .conc th { padding: 0 1ex; }");
    html.println(".conc td { vertical-align: top; border-bottom: #FFF solid 1px; }");
    html.println("td.num { font-size: 70%; }");
    html.println("td.left { text-align:right; }");
    html.println(".conc th { color: #000; background: #FFFFFF}");
    html.println(".conc i { font-style: normal; color: #000; }");
    html.println(".conc th.NAMEplace { background: rgba(255, 0, 0, 0.2) ; }");
    html.println(".conc i.NAMEplace { color:  rgba(255, 0, 0, 0.6); }");
    html.println(".conc th.NAMEpers, .conc th.NAMEpersm, .conc th.NAMEpersf { background: rgba(0, 0, 255, 0.2) ; }");
    html.println(".conc i.NAMEpers, .conc i.NAMEpersm, .conc i.NAMEpersf { color: rgba(0, 0, 255, 0.6) ; }");
    html.println("    </style>");
    html.println("  </head>");
    html.println("  <body>");
    html.println("    <table class=\"conc\">");
    html.println("      <tr>");
    html.println("       <th>Fichier</th>");
    html.println("       <th>Date</th>");
    html.println("       <th>Relation</th>");
    html.println("       <th>Contexte</th>");
    html.println("      </tr>");
  }

  public void foot()
  {
    html.println("    </table>");
    html.println("  </body>");
    html.println("</html>");
    html.println();
    html.close();
    csv.close();
  }

  /**
   * Traverser le texte, ramasser les infos, cracher à la fin
   * 
   * @param code
   * @param text
   * @throws IOException
   */
  public void parse(String filename, String date, String text) throws IOException
  {
    Tokenizer toks = new Tokenizer(text);
    OccRoller win = new OccRoller(left, right);
    // une pile FIFO,
    LinkedList<Source> stack = new LinkedList<Source>();
    int wn = 0; // le compteur de mots
    Term term;
    Source node;
    Occ occ;
    while (toks.word(win.add())) {
      wn++;
      // le mot n’est pas attendu on continue;
      if (!NODES.contains(win.get(0).orth())) {
        // if (win.get( 0 ).tag.isName()) win.get( 0 ).orth
        continue;
      }
      // on normalise ?
      occ = win.get(0);
      // boucler sur la pile
      for (Iterator<Source> iterator = stack.iterator(); iterator.hasNext();) {
        node = iterator.next();
        // ce nœud est trop loin, il n'y a plus rien à faire, on supprime
        if (wn - node.wn > step) {
          iterator.remove();
          continue;
        }
        // ce nœud est le même que le précédent, on supprie le précédent
        if (win.get(0).orth().equals(node.label)) {
          iterator.remove();
          continue;
        }
        // ici on peut écrire
        html.println("<tr>");
        html.print("  <td>");
        html.print(filename);
        csv.print(filename);
        html.println("</td>");
        html.print("  <td>");
        html.print(date);
        csv.print("\t" + date);
        html.println("</td>");
        html.print("  <td nowrap>");
        if (occ.orth().compareTo(node.label) > 0) {
          csv.print("\t" + node.label + "\t" + occ.orth());
          html.print(node.label);
          html.print("<br/>");
          html.print(occ.orth());
        }
        else {
          csv.print("\t" + occ.orth() + "\t" + node.label);
          html.print(occ.orth());
          html.print("<br/>");
          html.print(node.label);
        }
        html.println("</td>");
        html.print("  <td>");
        // contexte gauche
        html.print(node.left);
        // nœud source
        html.print("<b>");
        html.print(node.label);
        html.println("</b>");
        // milieu
        html.print(Tokenizer.xml2txt(text.substring(node.end, occ.start())));
        // html.println( (wn - node.wn)+" "+ node.end+" "+occ.start );
        // nœud destination
        html.print("<b>");
        html.print(occ.orth());
        html.println("</b>");
        // contexte droit
        html.print(Tokenizer.xml2txt(text.substring(occ.end(), win.get(right).end())));
        html.println("</td>");
        html.println("</tr>");
        csv.println();
      }
      // ouvrir un arc qui commence avec ce nœud
      stack.add(new Source(Tokenizer.xml2txt(text.substring(win.get(left).end(), occ.start())), occ.orth().toString(),
          wn, occ.end()));
    }

  }

  class Source
  {
    /** contexte gauche **/
    final String left;
    /** label du nœud source */
    final String label;
    /** index en mot */
    final int wn;
    /** index caractère de fin de mot */
    final int end;

    Source(final String left, final String label, final int wn, final int end) {
      this.left = left;
      this.label = label;
      this.wn = wn;
      this.end = end;
    }
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String args[]) throws IOException
  {
    // fixer la sortie
    // new PrintWriter( "marine_savants.html" )
    // print to out new PrintWriter(new OutputStreamWriter( System.out, "UTF-8"))
    NAME parser = new NAME(30, new PrintWriter("marine_savants.html"), new PrintWriter("marine_savants.csv"));

    parser.head();
    try (BufferedReader br = new BufferedReader(new FileReader("marine_critique.csv"))) {
      String[] cells;
      int pos;
      br.readLine(); // skip first line
      for (String line; (line = br.readLine()) != null;) {
        cells = line.split("\t");
        pos = cells[0].indexOf('_');
        String src = "../critique/" + cells[0].substring(0, pos) + "/" + cells[0] + ".xml";
        System.out.println(src);
        parser.parse(cells[0], cells[2], new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8));
      }
    }
    parser.foot();

  }

}
