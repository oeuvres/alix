package alix.frdo;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.Occ;

public class Mythographies
{
  PrintWriter csv;

  public Mythographies(PrintWriter csv) {
    this.csv = csv;
  }

  /**
   * Traverser le texte, ramasser les infos et cracher à la fin
   * 
   * @param code
   * @param text
   * @throws IOException
   */
  public void parse(String text, String source) throws IOException
  {
    Tokenizer toks = new Tokenizer(text);
    Occ occ;
    ;
    while ((occ = toks.word()) != null) {
      if (!occ.tag().isName())
        continue;
      if (occ.tag().equals(Tag.NAMEauthor))
        csv.println(source + ";" + occ.orth());
      if (occ.tag().equals(Tag.NAME))
        System.out.println(occ);
    }
    csv.flush();
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   * @throws ParseException
   */
  public static void main(String args[]) throws IOException, ParseException
  {
    // charger le dictionnaire local
    Lexik.loadFile("_mythographies/mythographies_dic.csv", Lexik._NAME);
    PrintWriter csv = new PrintWriter("_mythographies/mythographies_edges.csv");
    csv.println("Source;Target");
    Mythographies parser = new Mythographies(csv);
    for (final File src : new File("../mythographies").listFiles()) {
      if (src.isDirectory())
        continue;
      String filename = src.getName();
      if (filename.startsWith("."))
        continue;
      if (!filename.endsWith(".xml"))
        continue;
      parser.parse(new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8),
          src.getName().substring(0, filename.length() - 4));
    }

  }

}
