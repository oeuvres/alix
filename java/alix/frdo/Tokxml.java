package alix.frdo;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.Char;
import alix.util.Occ;

public class Tokxml
{

  public static void parse(String xml, PrintWriter out) throws IOException
  {
    Tokenizer toks = new Tokenizer(xml);
    int lasti = 0;
    CharSequence inter;
    Occ occ;
    while ((occ = toks.word()) != null) {
      inter = xml.subSequence(lasti, occ.start());
      int len = inter.length();
      if (len > 0) {
        // tester s’il y a autre chose qu’un espace avant d’imprimer
        int i = 0;
        for (; i < len; i++) {
          if (!Char.isSpace(inter.charAt(i))) {
            out.print(inter);
            break;
          }
        }
        // espace
        if (i == len)
          out.print("\n<c> </c>");
      }
      if (occ.tag().equals(Tag.PUNdiv))
        ; // on ne sort pas les tokens de structuration ¶, §
      else if (occ.tag().isPun()) {
        out.print("\n<pc tag=\"" + occ.tag() + "\">" + occ.graph() + "</pc>");
      }
      else {
        out.print("\n<w orth=\"" + occ.orth() + "\" lem=\"" + occ.lem() + "\" tag=\"" + occ.tag() + "\">" + occ.graph()
            + "</w>");
      }
      lasti = occ.end();
    }
    out.print(xml.subSequence(lasti, xml.length()));
    out.flush();
  }

  public static void main(String[] args) throws IOException
  {
    if (args == null || args.length < 1) {
      System.out.println("Usage : java -cp \"alix.jar\" alix.frdo.Teiw src.xml dest.xml");
      System.exit(0);
    }
    String src = args[0];
    String dest;
    if (args.length < 2)
      dest = src.substring(0, src.lastIndexOf('.')) + "_w.xml";
    else
      dest = args[1];
    String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
    parse(text, new PrintWriter(dest));
    return;
  }
}
