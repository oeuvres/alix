package alix.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import alix.fr.Tag;
import alix.lucene.analysis.FrLemFilter;
import alix.lucene.analysis.FrPersnameFilter;
import alix.lucene.analysis.FrTokenizer;
import alix.lucene.analysis.tokenattributes.CharsAtt;

@Command(
  name = "Balinoms", 
  description = "Tag name in an XML/TEI file",
  mixinStandardHelpOptions = true
)
public class Balinoms implements Callable<Integer>
{
  static class AnalyzerNames extends Analyzer
  {
  
    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrLemFilter(source);
      result = new FrPersnameFilter(result);
      return new TokenStreamComponents(source, result);
    }
  
  }
  static Analyzer anaNoms = new AnalyzerNames();

  @Parameters(arity = "1..*", description = "au moins un fichier XML/TEI à baliser")
  File[] files;
  
  @Override
  public Integer call() throws Exception {
    for (final File src : files) {
      String dest = src.getParent() + "/" + "name_" + src.getName();
      parse(new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8),
          new PrintWriter(dest));
      System.out.println(src + " > " + dest);
    }
    System.out.println("C’est fini");
    return 0;
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
    TokenStream stream = anaNoms.tokenStream("stats", new StringReader(xml));
    int toks = 0;
    int begin= 0;
    // get the CharTermAttribute from the TokenStream
    final CharsAtt attChars = (CharsAtt)stream.addAttribute(CharTermAttribute.class);
    final OffsetAttribute attOff = stream.addAttribute(OffsetAttribute.class);
    final FlagsAttribute attFlags = stream.addAttribute(FlagsAttribute.class);
    try {
      stream.reset();
      // print all tokens until stream is exhausted
      while (stream.incrementToken()) {
        toks++;
        final int flag = attFlags.getFlags();
        // TODO test to avoid over tagging ?
        if (!Tag.NAME.sameParent(flag)) continue;
        out.print(xml.substring(begin, attOff.startOffset()));
        begin = attOff.endOffset();
        if (Tag.NAMEpers.flag == flag) {
          out.print("<persName>");
          out.write(attChars.buffer(), 0, attChars.length());
          out.print("</persName>");
        }
        else if (Tag.NAMEplace.flag == flag) {
          out.print("<placeName>");
          out.write(attChars.buffer(), 0, attChars.length());
          out.print("</placeName>");
        }
        else  {
          out.print("<name>");
          out.write(attChars.buffer(), 0, attChars.length());
          out.print("</name>");
        }
      }
      
      stream.end();
    }
    finally {
      stream.close();
      // analyzer.close();
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
    int exitCode = new CommandLine(new Balinoms()).execute(args);
    System.exit(exitCode);
  }

}

