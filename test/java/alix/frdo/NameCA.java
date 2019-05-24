package alix.frdo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;

import alix.fr.Tokenizer;
import alix.util.Occ;

public class NameCA
{
  /** Liste des colonnes, conservé pour en garder l’ordre */
  final String[] words;
  /** Nombre de colonnes */
  final int width;
  /** Index des colonnes */
  private HashMap<String, Integer> cols;
  /** La matrice en ordre des lignes */
  private HashMap<String, int[]> lines = new HashMap<String, int[]>();

  public NameCA(final String[] words) {
    this.words = words;
    this.width = words.length;
    int lim = words.length;
    cols = new HashMap<String, Integer>(width);
    for (int i = 0; i < lim; i++) {
      cols.put(words[i], i);
    }
  }

  /**
   * Traverser le texte, ramasser les infos
   * 
   * @param code
   * @param text
   * @throws IOException
   */
  public void parse(String text, String[] props) throws IOException
  {
    Tokenizer toks = new Tokenizer(text);
    // current occurrence
    Occ occ;
    Integer col;
    int[] line;
    while ((occ = toks.word()) != null) {
      if (occ.orth().startsWith("Schwann"))
        System.out.println(occ);
      col = cols.get(occ.orth());
      // le mot n’est pas attendu on continue ?
      if (col == null)
        continue;
      // boucler sur les propriétés de ligne à modifier
      for (String th : props) {
        line = lines.get(th);
        if (line == null) {
          line = new int[width];
          lines.put(th, line);
        }
        line[col]++;
      }
    }
  }

  public void write(PrintWriter out)
  {
    char sep = '\t';
    /*
     * out.print( "AUTEURS\tTOTAL" ); for ( String w: words) { out.print( sep );
     * out.print( w ); } out.print( '\n' );
     */
    String[] props = lines.keySet().toArray(new String[0]);
    Arrays.sort(props);
    // entête, les propriétés des fichiers
    out.print("Mots\\Méta");
    for (String prop : props) {
      out.print(sep);
      out.print(prop);
    }
    out.print(sep);
    out.print("TOTAL");
    out.print('\n');
    // pour chaque ligne, un mot
    int value;
    for (int i = 0; i < words.length; i++) {
      out.print(words[i]);
      int total = 0;
      for (String prop : props) {
        out.print(sep);
        value = lines.get(prop)[i];
        out.print(value);
        total += value;
      }
      out.print(sep);
      out.print(total);
      out.print('\n');
    }
    out.close();
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String args[]) throws IOException
  {
    // liste des mots
    String wordfile = "_marine/savants.csv";
    String destfile = "_marine/critique_savants_dates.csv";
    String[] words = new String(Files.readAllBytes(Paths.get(wordfile)), StandardCharsets.UTF_8).split("[\n\r]+");
    NameCA parser = new NameCA(words);
    try (BufferedReader br = new BufferedReader(new FileReader("_marine/marine_critique.csv"))) {
      String[] cells;
      int pos;
      br.readLine(); // skip first line
      String[] props;
      for (String line; (line = br.readLine()) != null;) {
        cells = line.split("\t");
        pos = cells[0].indexOf('_');
        String src = "../critique/" + cells[0].substring(0, pos) + "/" + cells[0] + ".xml";
        System.out.println(src);
        props = new String[] { cells[2] };
        parser.parse(new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8), props);
      }
    }
    parser.write(new PrintWriter(destfile));
  }

}
