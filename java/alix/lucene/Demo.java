package alix.lucene;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

/**
 * Classe développée dans le cadre de l'ouvrage "Lucene in action"
 * http://java.codefetch.com/example/in/LuceneInAction/src/lia/analysis/AnalyzerUtils.java
 * Ajouts
 * <li>Compatible Lucene 3.5.0</li>
 * <li>Utilisable en Console et en Jsp</li>
 * <li>Permet d'utiliser des analyseurs lucene pour étiquetter des textes en
 * ligne de commande</li>
 */
public class Demo
{

  /**
   * Writer vers lequel sortir les résultats, par exemple pour utiliser cette
   * classe depuis une JSP.
   */
  public PrintWriter out;
  /** default field */
  public String field = "text";

  public Demo() {
    try {
      out = new PrintWriter(new PrintStream(System.out, true, "UTF-8"), true);
    }
    catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
  }

  public Demo(Writer out) {
    this.out = new PrintWriter(out);
  }

  /**
   * Classe interne pratique pour mimer le comportement de Lucene 2.*
   * 
   * @author Pierre DITTGEN
   */
  public static class MyToken
  {
    private String termText;
    private int positionIncrement;
    private String type;
    private int startOffset;
    private int endOffset;

    public MyToken(String termText, int positionIncrement, String type, int startOffset, int endOffset) {
      this.termText = termText;
      this.positionIncrement = positionIncrement;
      this.type = type;
      this.startOffset = startOffset;
      this.endOffset = endOffset;
    }

    public String termText()
    {
      return termText;
    }

    public int getPositionIncrement()
    {
      return positionIncrement;
    }

    public String type()
    {
      return type;
    }

    public int startOffset()
    {
      return startOffset;
    }

    public int endOffset()
    {
      return endOffset;
    }
  }

  public static MyToken[] tokensFromAnalysis(Analyzer analyzer, String text, String field) throws IOException
  {
    ;
    TokenStream stream = analyzer.tokenStream(field, new StringReader(text));
    CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
    PositionIncrementAttribute positionIncrementAttr = stream.addAttribute(PositionIncrementAttribute.class);
    TypeAttribute typeAttr = stream.addAttribute(TypeAttribute.class);
    OffsetAttribute offsetAttr = stream.addAttribute(OffsetAttribute.class);

    ArrayList<MyToken> tokenList = new ArrayList<MyToken>();
    while (stream.incrementToken()) {
      tokenList.add(new MyToken(term.toString(), positionIncrementAttr.getPositionIncrement(), typeAttr.type(),
          offsetAttr.startOffset(), offsetAttr.endOffset()));
    }

    return tokenList.toArray(new MyToken[0]);
  }

  public void displayTokens(Analyzer analyzer, String text) throws IOException
  {
    ;
    MyToken[] tokens = tokensFromAnalysis(analyzer, text, field);
    for (MyToken token : tokens) {
      out.println("[" + token.termText() + "] ");
    }
  }

  public void displayTokensWithPositions(Analyzer analyzer, String text) throws IOException
  {
    MyToken[] tokens = tokensFromAnalysis(analyzer, text, field);
    int position = 0;
    for (MyToken token : tokens) {
      int increment = token.getPositionIncrement();
      if (increment > 0) {
        position = position + increment;
        out.println();
        out.print(position + ":");
      }
      out.print(" [" + token.termText() + "]");
    }
    out.println();
  }

  public void displayTokensWithFullDetails(Analyzer analyzer, String text) throws IOException
  {
    MyToken[] tokens = tokensFromAnalysis(analyzer, text, field);
    int position = 0;
    for (MyToken token : tokens) {
      int increment = token.getPositionIncrement();
      if (increment > 0) {
        position = position + increment;
        out.println();
        out.print(position + ": ");
      }
      out.print(
          "[" + token.termText() + ":" + token.startOffset() + "->" + token.endOffset() + ":" + token.type() + "] ");
    }
    out.println();
  }

  /**
   * Lancer l'étiquetage d'un texte, en rétablissant les espaces autour des
   * ponctuations (selon les normes françaises).
   * 
   * @param args
   * @throws IOException
   */
  public void ponctuer(Analyzer analyzer, String text, String field) throws IOException
  {
    TokenStream stream = analyzer.tokenStream(field, new StringReader(text));
    CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
    // PositionIncrementAttribute posinc = (PositionIncrementAttribute)
    // stream.addAttribute(PositionIncrementAttribute.class);
    TypeAttribute type = stream.addAttribute(TypeAttribute.class);
    // OffsetAttribute offset = (OffsetAttribute)
    // stream.addAttribute(OffsetAttribute.class);
    String tok, tag;
    char c;
    boolean noSpaceBefore = true;
    while (stream.incrementToken()) {
      // pour accumulation dans un objet
      // tokenList.add(new MyToken(term.term(), posinc.getPositionIncrement(),
      // type.type(), offset.startOffset(), offset.endOffset()));
      tok = term.toString();
      c = tok.charAt(0);
      tag = type.type();
      // avant un mot
      if (noSpaceBefore)
        ;
      // espace insécable avant ponctuation double (?)
      else if (";".equals(tok) || ":".equals(tok) || "!".equals(tok) || "?".equals(tok) || "»".equals(tok))
        out.print(' ');
      // avant : pas d'espace
      else if (c == ',' || c == '.' || c == '…' || c == ')' || tok.startsWith("</") || tag.equals("PUNCT")
          || tag.equals("S"))
        ;
      else
        out.print(' ');

      out.print(tok);

      // après espace insécable
      if (tok.equals("«"))
        out.print(' ');
      // pas d'espace après un tag ouvrant
      if (c == '<' && !(tok.charAt(0) == '/'))
        noSpaceBefore = true;
      else
        noSpaceBefore = false;
    }
    out.println();
  }

  static public void main(String[] args) throws IOException
  {
    Demo u = new Demo();
    System.out.println("StandardAnalyzer");
    u.displayTokensWithFullDetails(new StandardAnalyzer(), "The quick brown fox....");
  }
}