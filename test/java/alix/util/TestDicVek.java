package alix.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import alix.deprecated.DicVek;
import alix.deprecated.DicVek.SimRow;

public class TestDicVek
{
  public static void main(String[] args) throws IOException
  {

    BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    if (args.length == 0) {
      System.out.print("Fichiers ? : ");
      String glob = keyboard.readLine().trim();
      System.out.println('"' + glob + '"');
      args = new String[] { glob };
    }
    // largeur avant-après
    int wing = 5;
    // le chargeur de vecteur a besoin d'une liste de mots vides pour éviter de
    // faire le vecteur de "de"
    // un lemmatiseur du pauvre sert à regrouper les entrées des vecteurs
    DicVek veks = new DicVek(-wing, wing);
    // Dicovek veks = new Dicovek(wing, wing, FrDics.STOPLIST);
    long start = System.nanoTime();
    // Boucler sur les fichiers
    for (int i = 0; i < args.length; i++) {
      veks.walk(args[i], new PrintWriter(System.out));
    }
    veks.prune(5);
    DicFreq dic = veks.dic();
    System.out.println(dic);

    System.out.println("Chargé en " + ((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println(veks.freqlist(true, 100));

    // Boucle de recherche
    List<SimRow> table;
    // DecimalFormat df = new DecimalFormat("0.0000");
    while (true) {
      System.out.println("");
      System.out.print("Mot : ");
      String line = keyboard.readLine().trim();
      line = line.trim();
      if (line.isEmpty()) System.exit(0);
      start = System.nanoTime();
      System.out.print("COOCCURRENTS : ");
      System.out.println(veks.coocs(line, 30, true));
      System.out.println("");
      start = System.nanoTime();
      table = veks.sims(line);
      System.out.println("Calculé en " + ((System.nanoTime() - start) / 1000000) + " ms");
      if (table == null) continue;
      int limit = 30;
      // TODO optimiser
      System.out.println("SIMINYMES (cosine) ");
      boolean first = true;
      for (SimRow row : table) {
        // if ( FrDics.isStop( row.term )) continue;
        if (first) first = false;
        else System.out.print(", ");
        System.out.print(dic.label(row.code));
        System.out.print(" (");
        System.out.print(row.score);
        System.out.print(")");
        if (--limit == 0) break;
      }
      System.out.println(".");

    }

  }
}
