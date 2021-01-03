package alix.lucene.analysis;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

public class TestAnalyzer
{
  static class LuceneStandard extends Analyzer
  {
    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new StandardTokenizer();
      return new TokenStreamComponents(source);
    }
  }

  static class LuceneWhite extends Analyzer
  {
    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new WhitespaceTokenizer();
      return new TokenStreamComponents(source);
    }
  }

  static class MetaAnalyzer extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new MetaTokenizer();
      return new TokenStreamComponents(source);
    }

  }
  static class AnalyzerTokfr extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      return new TokenStreamComponents(source);
    }

  }

  static class AnalyzerFull extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrLemFilter(source);
      result = new FlagAllFilter(result);
      return new TokenStreamComponents(source, result);
    }

  }
  
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

  static class AnalyzerCompounds extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrLemFilter(source);
      result = new FrPersnameFilter(result);
      result = new LocutionFilter(result);
      return new TokenStreamComponents(source, result);
    }

  }

  
  static Analyzer anaStd = new LuceneStandard();
  static Analyzer anaWhite = new LuceneWhite();
  static Analyzer anaMeta = new MetaAnalyzer();
  static void analyzers(final String text, final boolean print) throws IOException
  {
    long time;
    Analyzer[] analyzers = {  
      anaWhite,
      anaStd,
      anaMeta,
    };
    for (int loop = 1; loop > 0 ; loop--) {
      for (Analyzer analyzer : analyzers) {
        time = System.nanoTime();
        TokenStream stream = analyzer.tokenStream("stats", new StringReader(text));
        int toks = 0;
        // get the CharTermAttribute from the TokenStream
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        try {
          stream.reset();
          // print all tokens until stream is exhausted
          while (stream.incrementToken()) {
            toks++;
            if (print) System.out.println(term);
          }
          
          stream.end();
        }
        finally {
          stream.close();
          // analyzer.close();
        }
        System.out.println("" + analyzer.getClass() + " " + ((System.nanoTime() - time) / 1000000) + " ms. "+toks);
      }
    }

  }

  static String cases = "Emploie-t-il beaucoup de navires ? Réveille-le. "
      + "<a href=\"note\">XVII.</a> et XLV, GRYMALKIN. Mais lorsque la Grâce t’illumine de nouveau. "
      + "Le Siècle, La Plume, La Nouvelle Revue. Mot<a>note</a>. "
      + " -- Quadratin. U.K.N.O.W.N. La Fontaine... Quoi ???\" + \" Problème</section>. "
      + "L'errance de la Personne. François de Saint-Ouen Est-ce bien ?"
      + "M. P. de Noailles. Jackques Le Cornu. Comité d'État, let. A. dit-il. Souviens-toi !"
      + "6. Des experts suisses sont associés aux Parties contractantes peuvent échanger. "
      + "II La Suisse jouit des mêmes droits que les Etats membres. "
      + "L'Etat, c'est moi. C'est-à-dire l'Etre.<script>NAZE</script> "
      + "Souviens-toi. Que faut-il en faire ? Faut-il en dire plus ?"
      + "<p rend=\"block\"> (1) La confession des États-Unis. 1. Le petit chat est mort. "
      + "relativité souvenez-vous des décrets humains.<lb/>\n" + 
      "Le prix de mes <p xml:id='pp'>Qu'en penses-tu ? "
      + "C’est m&eacute;connaître 1,5 &lt; -1.5 cts &amp; M<b>o</b>t. Avec de <i>l'italique</i>"
      + "FIN.";
  
  static String tags = "\n" + 
      "         <span class=\"byline\">\n" + 
      "            <span class=\"persName\">\n" + 
      "               <span class=\"surname\">Âubignac</span>, François H&eacute;delin &gt; &eacute;  Black & Mortimer </span>.</span> \n" + 
      "         <span class=\"title\">Dissertation sur la condemnation des théâtres</span> \n" + 
      "         <span class=\"year\">(1666)</span>. <span class=\"pages\">pp. 188-216</span>.  « <span class=\"analytic\">Disseration sur la Condemnation."
      + "   des Théâtres. — Chapitre IX.   Que les Acteurs des Poèmes Dramatiques n'étaient point infâmes parmi les Romains, mais seulement les Histrions ou Bateleurs.</span> »"
      + "";


  public static void vertical(final String text, Analyzer analyzer) throws IOException
  {
    System.out.println(analyzer.getClass());
    System.out.println();
    TokenStream stream = analyzer.tokenStream("stats", new StringReader(text));

    // get the CharTermAttribute from the TokenStream
    CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
    CharsLemAtt lem = stream.addAttribute(CharsLemAtt.class);
    CharsOrthAtt orth = stream.addAttribute(CharsOrthAtt.class);
    OffsetAttribute offsets = stream.addAttribute(OffsetAttribute.class);
    FlagsAttribute flags = stream.addAttribute(FlagsAttribute.class);
    PositionIncrementAttribute posInc = stream.addAttribute(PositionIncrementAttribute.class);
    PositionLengthAttribute posLen = stream.addAttribute(PositionLengthAttribute.class);
    
    try {
      stream.reset();
      // print all tokens until stream is exhausted
      while (stream.incrementToken()) {
        System.out.print(
          term 
          + "\t" + orth  
          + "\t" + Tag.label(flags.getFlags())
          + "\t" + lem  
          + " |" + text.substring(offsets.startOffset(), offsets.endOffset()) + "|"
          + " " + offsets.startOffset() + "-" + offsets.endOffset()
          + " (" + posInc.getPositionIncrement() + ", " + posLen.getPositionLength() + ")"
        );
        System.out.println();
      }
      
      stream.end();
    }
    finally {
      stream.close();
      analyzer.close();
    }
    System.out.println();
  }
  
  public static void names() throws IOException
  {
    // text to tokenize
    final String text = "V. Hugo. Victor Hugo. Les États-Nations, les états-nations, l’État, l’état, à Vrai dire… De Maître Eckhart à Jean de la Croix.  Jules Marie, Pierre de Martin ou Peut-être lol ? Les U.S.A., un grand pays. L'orange et l'Europe de l'acier. ";
    vertical(text, new AnalyzerNames());
  }
  
  public static void compounds() throws IOException
  {
    String text = "";// text to tokenize
    text = " qui a de bons points (un bon point). Or les bons points vont aux parfaits imitateurs."
        + " Allons-y ! Mon Dieu… Mais alors ? D’abord, d’autre part, d’ailleurs, "
        + " le chemin de fer d’intérêt local au moyen âge. <num>xiii<hi rend=\"sup\">e</hi></num> siècle."
        + " J’ai écrit ce livre à New York, dit l’Évangile."
        + " Et l’Éternel Dieu dit : Qui t’a appris que tu es nu ? "
        + " Traduction française par J. Herbomez et R. Beaurieux. faire faire <pb n=\"404\" xml:id=\"p404\"/> l’amour. "
        + " Je, ça va, suis content de chemin de fer, aïe. Ici la clé de ma composition. Le 21 juin 1938.</byline>\n</div>"
    ;
    // text = Files.readString(Paths.get("/home/fred/code/xmlbug/hugo_prefaces.xml"));
    String text2 = "Tous les principes que cette époque a posés, pour\n" + 
        "              le monde des intelligences comme pour le monde des affaires, amènent déjà rapidement\n" + 
        "              leurs conséquences. Espérons qu’un jour le dix-neuvième siècle, politique et\n" + 
        "              littéraire, pourra être résumé d’un mot : la liberté dans l’ordre, la liberté dans\n" + 
        "              l’art.</p>"; // bug on art.
    text = " Le bon point du chemin au douzième siècle, 12e siècle, c’est celui du chemin de fer d’intérêt local.";
    text = " De sorte qu’alors il fut fait, au lieu d’aller partout. Ça ne va pas de soi. Elles sont parties où ?";
    // text = "Il y avait beaucoup de bonnes volontés engagées.";
    // text = " n’était qu’un Pacte d’alliance entre vingt‑cinq </span><span class=\"right\"><a href=\"#pos99\">États</a> absolument souverains.";
    vertical(text, new AnalyzerCompounds());
  }

  public static void tokfr() throws IOException
  {
    String text;
    // mode search
    text =  "monnaie d’un -nombre toujours plus restreint";
    // TODO, does not work
    text = "le rebondissement de l<emph>’action</emph>, E<anchor xml:id=\"_GoBack\"/>h, la V<hi rend=\"sup\">e</hi> République";
    vertical(text, new AnalyzerTokfr());
  }

  public static void localDic() throws IOException
  {
    Path tmpfile = Files.createTempFile("alix-", ".csv");
    String dic = "Club de Rome;NAMEorg"
      + "\nclub de Rome;NAMEorg;Club de Rome;"
      + "\nClub des Jacobins;NAMEorg;"
      + "\nsuisse;NAME;Suisse"
      + "\nsuisses;NAME;Suisse"
    ;
    Files.write(tmpfile, dic.getBytes());
    FrDics.load(tmpfile.toFile());
    String text;
    // mode search
    text =  "Les temps sont proches, nous dit le rapport du Club de Rome. Nous avons vu aussi que l’industrie suisse n’est pas comme les suisses.";
    System.out.println("———————————");
    vertical(text, new AnalyzerNames());
    System.out.println("———————————");
    vertical(text, new FrAnalyzer());
  }


  public static void main(String[] args) throws IOException
  {
    // tokfr();
    compounds();
    // localDic();
  }
}