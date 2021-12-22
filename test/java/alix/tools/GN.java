/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import alix.deprecated.Tokenizer;
import alix.fr.Tag;
import alix.util.DicFreq;
import alix.util.OccRoll;

public class GN
{
  /** Counter */
  int n = 0;
  /** Tokenizer */
  Tokenizer toks;
  /** Where to write results */
  PrintWriter out;
  /** Size on the left */
  final int left = -10;
  /** Size on the right */
  final int right = 10;

  public GN(Path src) throws IOException {
    toks = new Tokenizer(new String(Files.readAllBytes(src), StandardCharsets.UTF_8));
  }

  /**
   * Contstructeur pour JSP
   * 
   * @param text
   * @throws IOException
   */
  public GN(String text) throws IOException {
    toks = new Tokenizer(text);
  }

  public void htmlHead(PrintWriter out)
  {
    out.println("<!doctype html>");
    out.println("<html>");
    out.println("  <head>");
    out.println("    <meta charset=\"utf-8\">");
    out.println("    <style>");
    out.println(
        "table.conc { font-family: sans-serif; color: #666; border-spacing : 2px; background-color: #EEEEEE; }");
    out.println(".conc td, .conc th { padding: 0; }");
    out.println("td.num { font-size: 70%; }");
    out.println("td.left { text-align:right; }");
    out.println(".left b { padding: 0 1ex; margin-left: 1ex; }");
    out.println(".right b { padding: 0 1ex; margin-right: 1ex; }");
    out.println(".conc b, .conc th {  font-weight: normal; color: #000; background-color: #FFFFFF; }");
    out.println(".conc i { font-style: normal; color: red;  }");
    out.println("    </style>");
    out.println("  </head>");
    out.println("  <body>");
    out.println("    <table class=\"conc\">");
  }

  public void htmlFoot(PrintWriter out)
  {
    out.println("    </table>");
    out.println("  </body>");
    out.println("</html>");
    out.println();
    out.close();
  }

  /*
   * public void parse( ) throws IOException { htmlHead( htmlWriter ); TermDic dic
   * = parse( toks ); htmlFoot( htmlWriter ); csvWriter.write(
   * "ADJECTIF\tANTE\tPOST\tANTE+POST\t% ANTE\n" ); dic.csv( csvWriter );
   * csvWriter.close(); }
   */
  /**
   * 
   */
  public DicFreq parse()
  {
    return parse(null, -1);
  }

  /**
   * If out not null, an html concordance will be written during parsing
   * 
   * @param out
   * @param limit
   * @return
   */
  public DicFreq parse(PrintWriter out, int limit)
  {
    DicFreq dic = new DicFreq();
    OccRoll win = new OccRoll(left, right);
    // loop on all tokens
    while (toks.word(win.add())) {
      // limited concordance
      if (limit > 0 && n >= limit)
        return dic;
      if (!win.get(0).tag().equals(Tag.SUB)) {
        // increment global count of dictionary to calculate a frequency for the indexed
        // chain
        dic.inc();
        continue;
      }
      int lpos = 0;
      boolean ladj = false;
      while (lpos > left) {
        final int tag = win.get(lpos - 1).tag().code();
        if (tag == Tag.ADJ) {
          dic.inc(win.get(lpos - 1).lem());
          lpos--;
          ladj = true;
          continue;
        }
        else if (tag == Tag.VERBppass) {
          dic.inc(win.get(lpos - 1).orth()); // ? TODO lem
          lpos--;
          win.get(lpos).tag(Tag.ADJ);
          ladj = true;
          continue;
        }
        else if (ladj && tag == Tag.CONJcoord) {
          lpos--;
          continue;
        }
        else if (ladj && Tag.isAdv(tag)) {
          lpos--;
          continue;
        }
        else if (Tag.isDet(tag)) {
          lpos--;
          break;
        }
        else if (tag == Tag.DETnum) {
          lpos--;
          break;
        }
        else if (tag == Tag.PREP) {
          lpos--;
          break;
        }
        break;
      }
      int rpos = 0;
      boolean radj = false;
      // TODO
      // une chose sans cause , incompréhensible
      // un âge] à jamais révolu
      // Ou bien en dormant j’avais rejoint sans effort un âge à jamais révolu de …
      // … [ma vie primitive, retrouvé] telle de mes terreurs enfantines comme celle
      // que mon grand-oncle me tirât par mes boucles et qu’avait dissipée le jour —
      // date pour moi d’une ère nouvelle — où on les avait coupées.
      while (rpos < right) {
        final int tag = win.get(rpos + 1).tag().code();
        if (tag == Tag.ADJ) {
          dic.inc2(win.get(rpos + 1).lem());
          rpos++;
          radj = true;
          continue;
        }
        else if (tag == Tag.VERBppass) {
          dic.inc2(win.get(rpos + 1).orth()); // TODO lem
          rpos++;
          // correct participle,seems adj
          win.get(rpos).tag(Tag.ADJ);
          radj = true;
          continue;
        }
        // une femme belle mais redoutable
        else if (radj && tag == Tag.CONJcoord) {
          rpos++;
          continue;
        }
        // une chose vraiment obscure
        // si adverbe suivi d’un adjectif
        // un <vol> plus léger , plus immatériel , plus vertigineux , plus
        else if (Tag.isAdv(tag) && rpos < right - 1 && win.get(rpos + 2).tag().equals(Tag.ADJ)) {
          rpos++;
          continue;
        }
        // si déjà adj, puis virgule, voir après
        else if (radj && win.get(rpos + 1).orth().equals(",")) {
          rpos++;
          continue;
        }
        // exclure la virgule finale
        if (win.get(rpos).orth().equals(","))
          rpos--;
        // exclure la conjonction finale
        if (win.get(rpos).tag().equals(Tag.CONJcoord))
          rpos--;
        break;
      }
      if (!ladj && !radj)
        continue;
      if (out != null)
        html(out, win, lpos, rpos);
    }
    return dic;
  }

  /**
   * Write the window
   */
  private void html(PrintWriter out, final OccRoll win, final int lpos, final int rpos)
  {
    n++;
    out.print("<tr>");
    out.print("<td class=\"num\">");
    out.print(n);
    out.print(".</td>");
    out.print("<td class=\"left\">");
    for (int i = left; i < 0; i++) {
      if (i == lpos)
        out.print("<b>");
      if (win.get(i).tag().equals(Tag.ADJ))
        out.print("<i>");
      out.print(win.get(i).graph());
      if (win.get(i).tag().equals(Tag.ADJ))
        out.print("</i>");
      if (i < -1)
        out.print(" ");
    }
    if (lpos < 0)
      out.print("</b>");
    out.print("</td>");
    out.print("<th>");
    out.print(win.get(0).graph());
    out.print("</th>");
    out.print("<td class=\"right\">");
    if (rpos > 0)
      out.print("<b>");
    for (int i = 1; i <= right; i++) {
      if (win.get(i).tag().equals(Tag.ADJ))
        out.print("<i>");
      out.print(win.get(i).graph());
      if (win.get(i).tag().equals(Tag.ADJ))
        out.print("</i>");
      if (i == rpos)
        out.print("</b>");
      out.print(" ");
    }
    out.print("</td>");
    out.print("</tr>");
    out.println();
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String args[]) throws IOException
  {
    File dir = new File("../proust");
    for (String srcname : dir.list()) {
      if (srcname.startsWith("."))
        continue;
      if (!srcname.endsWith(".xml"))
        continue;
      String destname = srcname.substring(0, srcname.length() - 4);
      Path srcpath = Paths.get(dir.toString(), srcname);
      Path destpath = Paths.get(dir.toString(), destname);
      System.out.println(srcpath + " > " + destpath);
      // GN gn = new GN(srcpath);
      // htmlWriter = new PrintWriter( destName.toString()+".html" );
      // csvWriter = new PrintWriter( destName.toString()+".csv" );
      // gn.parse();
    }
  }

  /**
   * Bugs — un ton modeste et vrai — à peu près — à peine — rendre compte — un
   * tour [un peu particulier] — par exemple — à toute vitesse — au premier
   * instant — son valet de chambre émerveillé
   */
}
