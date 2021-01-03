package alix.deprecated;

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
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.CharsDic;
import alix.lucene.analysis.LocutionFilter;
import alix.lucene.analysis.FrLemFilter;
import alix.lucene.analysis.FrPersnameFilter;
import alix.lucene.analysis.FrTokenizer;
import alix.lucene.analysis.CharsDic.Entry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;
import alix.util.Dir;;

public class TestCompound
{
  static class AnalyzerTest extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrLemFilter(source);
      result = new FrPersnameFilter(result); // link names: V. Hugo
      result = new LocutionFilter(result); // link locutions: XX e
      return new TokenStreamComponents(source, result);
    }

  }
  
  public static void cycle()
  {
    Compound compound = new Compound(3);
    String[] words = "Le grand fromager de l’ interzone vous mangera confit. Mais alors ?".split("\\s+");
    for (String w: words) {
      compound.add(w, Tag.SUB);
      System.out.print(compound+"\t");
      boolean first = true;
      for (int i = compound.size(); i >= 2; i--) {
        if (first) first = false;
        else System.out.print(", ");
        System.out.print("«"+compound.chars(i)+"»");
      }
      System.out.println();
    }
  }

  public static void collocs() throws IOException
  {
    /*
     * char[] ar = "0123456789ABCDEFGHIJK".toCharArray(); int len = ar.length;
     * System.arraycopy(ar, 2, ar, 0, 10); System.out.print(ar);
     */

    final int width = 5;
    
    Compound compound = new Compound(width);
    PrintStream out = System.out;
    Analyzer analyzer = new AnalyzerTest();
    TokenStream stream;
    CharsDic dic = new CharsDic();
    // ddr1982partdia_part-diable
    // ddr1972ao
    List<File> ls = Dir.ls("/home/fred/code/ddr-test/ddr1977aena*\\.xml");
    System.out.println(ls);
    
    TagFilter tagfilter = new TagFilter().setGroup(Tag.NAME).setGroup(Tag.SUB).setGroup(Tag.ADJ)
        .setGroup(Tag.VERB).clear(Tag.VERBaux).clear(Tag.VERBsup)
        .set(Tag.NULL).setGroup(Tag.NAME)
    ;
    for (File entry : ls) {
      out.println(entry.getName());
      Path path = entry.toPath();
      String text = Files.readString(path);
      InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      stream = analyzer.tokenStream("cloud", reader);
      
      // get the CharTermAttribute from the TokenStream
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      CharsOrthAtt orth = stream.addAttribute(CharsOrthAtt.class);
      CharsLemAtt lem = stream.addAttribute(CharsLemAtt.class);
      FlagsAttribute flags = stream.addAttribute(FlagsAttribute.class);
      OffsetAttribute offsets = stream.addAttribute(OffsetAttribute.class);


      long occs = 0;
      int wc = 0;
      try {
        stream.reset();
        while (stream.incrementToken()) {
          occs++; // loop on all tokens
          int tag = flags.getFlags();
          boolean plainword = tagfilter.accept(tag);
          /*
          if (plainword) {
            if (lem.length() > 0) dic.inc((CharsAtt)lem);
            else dic.inc((CharsAtt)orth);
          }
          */
          if (wc == 0 && !plainword) continue;
          boolean pun = Tag.isPun(tag);
          if (pun || wc >= width) {
            compound.clear();
            wc = 0;
            continue;
          }
          
          if (lem.length() > 0 && Tag.isVerb(tag)) {
            compound.add(lem);
          }
          else compound.add(orth);
          wc++;
          // 2nd plain word, restart shingle, and keep the current word
          if (wc > 1 && plainword) {
            dic.inc(compound.chars());
            compound.clear();
            if (lem.length() > 0 && Tag.isVerb(tag)) {
              compound.add(lem);
            }
            else compound.add(orth);
            wc = 1;
          }
          // if (freqs > 100) break;
        }
        stream.end();
      }
      catch (Exception e) {
        out.println("ERROR token no=" + occs + " " + term);
        e.printStackTrace();
      }
      finally {
        stream.close();
      }
    }
    analyzer.close();
    Entry[] entries = dic.sorted();
    int max = Math.min(entries.length, 1000);
    for (int i = 0; i < max; i++) {
      if (entries[i].count() == 2) break;
      System.out.println(entries[i]);
    }
  }
  
  public static void main(String[] args) throws IOException
  {
    // collocs();
    cycle();
  }
}
