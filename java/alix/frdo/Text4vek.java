package alix.frdo;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import alix.fr.Tokenizer;
import alix.util.Occ;

/**
 * Optimise du texte pour la vectorisation
 * 
 * @author glorieux-f
 *
 */
public class Text4vek
{

  public static void main(String[] args) throws IOException
  {
    // String src = "../alix-demo/WEB-INF/textes/proust_recherche.xml";
    // String dest = "/Local/word2vec/proust.txt";
    // String src = "../alix-demo/WEB-INF/textes/zola.xml";
    // String dest = "/Local/word2vec/zola.txt";
    // String src = "../alix-demo/WEB-INF/textes/dumas.xml";
    // String dest = "/Local/word2vec/dumas.txt";
    // String src = "../alix-demo/WEB-INF/textes/lacan_ecrits.xml";
    // String dest = "/Local/word2vec/lacan.txt";
    // String src =
    // "../alix-demo/WEB-INF/textes/levi-strauss_anthropologie-structurale.xml";
    // String dest = "/Local/word2vec/levistrauss.txt";
    String src = "../alix-demo/WEB-INF/textes/barthes_compilationstructurale.html";
    String dest = "/Local/word2vec/barthes.txt";
    String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
    System.out.println(src + " > " + dest);
    PrintWriter out = new PrintWriter(dest);
    Tokenizer toks = new Tokenizer(text);
    // est-ce qu’on a besoin d’une fenêtre glissante ?
    Occ occ = new Occ();
    while (toks.word(occ)) {
      if (occ.graph().equals("/")) {
        out.println("");
        continue;
      }
      else if (occ.tag().isPun())
        continue;
      else if (occ.tag().isName())
        out.print("ONOMA");
      // else if ( occ.tag.isVerb() || occ.tag.isAdj() || occ.tag.isSub() ) out.print(
      // occ.lem );
      else
        out.print(occ.lem());
      out.print(' ');
    }
    out.close();
    System.out.println("Fini");
  }

}
