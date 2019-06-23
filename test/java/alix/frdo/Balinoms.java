package alix.frdo;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.Occ;

public class Balinoms
{

  /**
   * Un jour on pourra passer des listes prédéfinies au parseur, exemple, une
   * liste de personnages poour un roman (résolution des prénoms)
   * 
   * @param xml
   * @param out
   */
  public Balinoms() {
  }

  /**
   * Traverser le texte, ramasser les infos, cracher à la fin
   * 
   * @param code
   * @param text
   * @throws IOException
   */
  public void parse(String xml, PrintWriter out) throws IOException
  {
    Tokenizer toks = new Tokenizer(xml);
    int begin = 0;
    Occ occ;
    while ((occ = toks.word()) != null) {
      if (!occ.tag().isName())
        continue;
      out.print(xml.substring(begin, occ.start()));
      begin = occ.end();
      if (occ.tag().equals(Tag.NAMEpers)) {
        out.print("<persName>");
        out.print(occ.graph());
        out.print("</persName>");
      }
      else if (occ.tag().equals(Tag.NAMEplace)) {
        out.print("<placeName>");
        out.print(occ.graph());
        out.print("</placeName>");
      }
      else {
        out.print("<name>");
        out.print(occ.graph());
        out.print("</name>");
      }
    }
    out.print(xml.substring(begin));
    out.flush();
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
    // TODO boucler sur un dossier
    String dir = "_balinoms/";
    String dest;
    // String dest="../alix-demo/WEB-INF/textes/test.xml";
    Balinoms parser = new Balinoms();
    for (final File src : new File(dir).listFiles()) {
      if (src.isDirectory())
        continue;
      if (src.getName().startsWith("."))
        continue;
      if (src.getName().startsWith("name"))
        continue;
      dest = src.getParent() + "/" + "name_" + src.getName();
      if (new File(dest).exists())
        continue;
      System.out.println(src + " > " + dest);
      parser.parse(new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8),
          new PrintWriter(dest));
    }
    System.out.println("C’est fini");
  }

}
