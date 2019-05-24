package alix.frdo;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import alix.fr.Tokenizer;
import alix.util.Occ;

public class Tokcsv
{
  /** Cell separator */
  static final char TAB = '\t';
  /** Line separator */
  static final char LN = '\n';

  public static void parse(String xml, PrintWriter out) throws IOException
  {
    Tokenizer toks = new Tokenizer(xml);
    Occ occ;
    while ((occ = toks.word()) != null) {
      out.append(occ.graph());
      out.append(TAB);
      out.append(occ.orth());
      out.append(TAB);
      out.append(occ.tag().toString());
      out.append(TAB);
      out.append(occ.lem());
      out.append(LN);
    }
    out.flush(); // do not forget
  }

  public static void main(String[] args) throws IOException
  {
    if (args == null || args.length < 1) {
      System.out.println("Usage : java -cp \"alix.jar\" alix.frdo.Tokcsv src.xml dest.tsv");
      System.exit(0);
    }
    String src = args[0];
    String dest;
    if (args.length < 2)
      dest = src.substring(0, src.lastIndexOf('.')) + ".tsv";
    else
      dest = args[1];
    String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
    PrintWriter out = new PrintWriter(dest);
    parse(text, out);
    out.close();
    return;
  }
}
