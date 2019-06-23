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
import alix.util.OccRoller;
import alix.util.DicFreq;

public class Castlist
{
  DicFreq dic = new DicFreq();

  /**
   * Un jour on pourra passer des listes prédéfinies au parseur, exemple, une
   * liste de personnages poour un roman (résolution des prénoms)
   * 
   * @param xml
   * @param out
   */
  public Castlist() {
  }

  public void pivot(String xml, PrintWriter out, Tag tag, int left, int right)
  {
    OccRoller win = new OccRoller(left, right);
    Tokenizer toks = new Tokenizer(xml);
    while (toks.word(win.add())) {
      System.out.println(win.get(0));
      if (!win.get(0).tag().equals(tag))
        continue;
      boolean first = true;
      for (int i = -left; i <= right; i++) {
        if (first)
          first = false;
        else
          out.print("\t");
        if (i == 0)
          out.print(win.get(i).orth());
        else {
          out.print(win.get(i).tag());
          out.print("\t");
          if (win.get(i).graph().equals("\""))
            out.print('«');
          else
            out.print(win.get(i).orth());
        }
      }
      out.print("\n");
    }
    out.flush();
  }

  public void pivot(String xml, PrintWriter out, String pivot, int left, int right)
  {
    OccRoller win = new OccRoller(left, right);
    Tokenizer toks = new Tokenizer(xml);
    while (toks.word(win.add())) {
      if (!win.get(0).orth().equals(pivot))
        continue;
      boolean first = true;
      for (int i = -left; i <= right; i++) {
        if (first)
          first = false;
        else
          out.print("\t");
        if (i == 0)
          out.print(win.get(i).orth());
        else {
          out.print(win.get(i).tag());
          out.print("\t");
          if (win.get(i).graph().equals("\""))
            out.print('«');
          else
            out.print(win.get(i).orth());
        }
      }
      out.print("\n");
    }
    out.flush();
  }

  public void parse(String xml) throws IOException
  {
    Tokenizer toks = new Tokenizer(xml);
    // est-ce qu’on a besoin d’une fenêtre glissante ?
    Occ occ = new Occ();
    while (toks.word(occ)) {
      if (!occ.tag().isName())
        continue;
      if (occ.tag().equals(Tag.NAMEpers)) {
        dic.inc(occ.orth(), Tag.NAMEpers);
      }
      else if (occ.tag().equals(Tag.NAME)) {
        dic.inc(occ.orth(), Tag.NAME);
      }
    }
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
    // est-ce qu’on a besoin d’une fenêtre glissante ?
    Occ occ = new Occ();
    int begin = 0;
    while (toks.word(occ)) {
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
    String dest = "_NAME/out.csv";
    String pivot = "côté";
    dest = "_NAME/" + pivot + ".csv";
    Tag tag = new Tag("NAME");
    dest = "_NAME/" + tag + ".csv";
    PrintWriter out = new PrintWriter(dest, "UTF-8");
    short left = -5;
    short right = 5;
    boolean first = true;
    for (int i = left; i <= right; i++) {
      if (first)
        first = false;
      else
        out.print("\t");
      if (i == 0)
        out.print(pivot);
      else {
        out.print("TAG" + i);
        out.print("\t");
        out.print("GRAPH" + i);
      }
    }
    out.println();

    // TODO boucler sur un dossier
    String dir = "_NAME";
    // String dir="../zola/";
    Castlist parser = new Castlist();
    for (final File src : new File(dir).listFiles()) {
      if (src.isDirectory())
        continue;
      if (src.getName().startsWith("."))
        continue;
      // if ( src.getName().startsWith( "name" )) continue;
      if (!src.getName().endsWith(".xml"))
        continue;
      System.out.println(src);
      // String src = "../zola/zola.xml";
      String xml = new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8);
      parser.pivot(xml, out, tag, left, right);
    }
    out.close();
    System.out.println("C’est fini");
    // parser.dic.csv( new PrintWriter(System.out) );
  }

}
