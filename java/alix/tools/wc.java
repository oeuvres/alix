package alix.tools;

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
import java.util.ArrayList;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import alix.lucene.CharsDic;
import alix.lucene.TokenDic;
import alix.lucene.TokenDic.AnalyzerDic;
import alix.util.Dir;

public class wc
{
  public static void main(String[] args) throws IOException
  {
    ArrayList<File> files = new ArrayList<File>();
    Dir.ls("/var/www/html/Rougemont/ocr/.*\\.xml", files);
    int reed = files.size();
    Dir.ls("/var/www/html/Rougemont/reed/.*\\.xml", files);
    
    CharsDic dic = new CharsDic();
    Analyzer analyzer = new AnalyzerDic(dic);
    TokenStream ts;
    long total = 0;
    PrintStream out = System.out;
    int n = 0;
    out.println("No\tFicher\tAnnée\tSigle\t Réédition\tMots");
    for (File entry : files) {
      String name = entry.getName();
      out.print(entry.getName() + "\t" + name.substring(3, 7)+"\t"+name.substring(name.indexOf("_")+1)+"\t");
      if (n >= reed) out.print(1);
      out.print("\t");
      Path path = entry.toPath();
      InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      ts = analyzer.tokenStream("cloud", reader);
      CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
      OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);
      long occs = 0;
      try {
        ts.reset();
        while (ts.incrementToken()) {
          occs++; // loop on all tokens
        }
        ts.end();
      }
      catch(Exception e) {
        out.println("token no="+occs+" "+term);
        e.printStackTrace();
      }
      finally {
        ts.close();
      }
      out.println(occs);
      total += occs;
      n++;
    }
    analyzer.close();

  }

}
