package alix.lucene.analysis;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.tokenattributes.CharsDic;
import alix.lucene.analysis.tokenattributes.CharsDic.Entry;
import alix.util.Dir;;

public class TestCompound
{
  static class AnalyzerTest extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrTokenLem(source);
      return new TokenStreamComponents(source, result);
    }

  }

  public static void main(String[] args) throws IOException
  {
    /*
     * char[] ar = "0123456789ABCDEFGHIJK".toCharArray(); int len = ar.length;
     * System.arraycopy(ar, 2, ar, 0, 10); System.out.print(ar);
     */

    Compound compound = new Compound(4);
    PrintStream out = System.out;
    Analyzer analyzer = new AnalyzerTest();
    TokenStream ts;
    CharsDic dic = new CharsDic();

    List<File> ls = Dir.ls("/home/fred/Documents/suisse/.*\\.html");
    TagFilter tagfilter = new TagFilter().setGroup(Tag.NAME).setGroup(Tag.SUB).setGroup(Tag.VERB).setGroup(Tag.ADJ)
        .set(Tag.NULL);
    for (File entry : ls) {
      out.println(entry.getName());
      Path path = entry.toPath();
      InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      ts = analyzer.tokenStream("cloud", reader);
      CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
      FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

      long occs = 0;
      try {
        ts.reset();
        while (ts.incrementToken()) {
          int tag = flags.getFlags();
          if (Tag.isPun(tag)) {
            compound.clear();
            continue;
          }
          occs++; // loop on all tokens
          int flag = 0;
          if (tagfilter.accept(tag)) flag = 1;
          compound.add(token, flag);
          if (compound.flag(0) != 1) {
            System.out.println(compound.chars(1));
            continue;
          }
          // System.out.println(compound.chars());
          for (int i = compound.size(); i >= 2; i--) {
            dic.inc(compound.chars(i));
          }
        }
        ts.end();
      }
      catch (Exception e) {
        out.println("token no=" + occs + " " + token);
        e.printStackTrace();
      }
      finally {
        ts.close();
      }
    }
    analyzer.close();
    Entry[] entries = dic.sorted();
    int max = entries.length;
    for (int i = 0; i < max; i++) {
      if (entries[i].count() == 2) break;
      System.out.println(entries[i]);
    }
  }
}
