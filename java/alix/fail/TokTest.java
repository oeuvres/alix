package alix.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import alix.fr.Tokenizer;
import alix.util.Occ;
import alix.util.OccRoller;

public class TokTest
{
  /**
   * @throws IOException
   * 
   *           TODO: dictionnaire
   */
  public static void main(String[] args) throws IOException
  {
    Path path = Paths.get("../alix-demo/WEB-INF/veks/1861.xml");
    // >Il
    String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    Tokenizer toks = new Tokenizer(text);
    OccRoller occs = new OccRoller(-10, 10);
    int i = 0;
    Occ occ;
    while ((occ = toks.word()) != null) {
      occs.push(occ); // envoyer l’occurrence
      i++;
    }
    System.out.println(i);
    System.out.println(occs);
    System.out.println(occs.get(9).end() + " " + occs.get(9).start());
    System.out.println(occs.last().end() + " " + occs.last().start());
  }
}
