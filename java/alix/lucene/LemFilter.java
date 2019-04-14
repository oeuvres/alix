package alix.lucene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import alix.fr.dic.Tag;
import alix.lucene.CharDic.LexEntry;
import alix.lucene.CharDic.NameEntry;
import alix.util.Chain;
import alix.util.Char;


/**
 * 
 */
public final class LemFilter extends TokenFilter
{

  private final LemAtt lemAtt = addAttribute(LemAtt.class); // ? needs to be declared in the tokenizer
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private final PositionIncrementAttribute posincAtt = addAttribute(PositionIncrementAttribute.class);
  private final KeywordAttribute keyordAtt = addAttribute(KeywordAttribute.class);
  private final PositionLengthAttribute poslenAtt = addAttribute(PositionLengthAttribute.class);
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Last token was Punctuation */
  private boolean waspun;
  /** Store state */
  private State save;
  /** Keep trace of tokens */
  private final TermStack stack = new TermStack();

  /**
   * French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel
   */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>();
  static {
    for (String w : new String[] { "-ce", "-ci", "-elle", "-elles", "-en", "-eux", "-il", "-ils", "-je", "-la", "-là",
        "-le", "-les", "-leur", "-lui", "-me", "-moi", "-nous", "-on", "-t", "-t-", "-te", "-toi", "-tu", "-vous",
        "-y" })
      HYPHEN_POST.add(w);
  }

  /**
   *
   */
  public LemFilter(TokenStream input)
  {
    super(input);
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }
    // end of stream
    if (!input.incrementToken()) return false;
    final boolean waspun = this.waspun;
    CharAtt term = (CharAtt)termAtt;
    CharAtt lem = (CharAtt)lemAtt;
    int flags = flagsAtt.getFlags();
    // pass through zero-length terms
    if (term.length() == 0) return true;
    if (flags == Tag.PUNdiv || flags == Tag.PUNsent) {
      this.waspun = true;
      // clean the term stack
      return true;
    }
    // Get first char
    char c1 = term.charAt(0);
    // a tag do not affect the prev flags
    if (c1 == '<') return true;
    this.waspun = false;
    LexEntry word;
    NameEntry name;
    // norm case
    if (Char.isUpperCase(c1)) {
      name = CharDic.name(term);
      if (name != null) {
        flagsAtt.setFlags(name.tag);
        return true;
      }
      // if not after pun, say it's a name
      if (!waspun) {
        flagsAtt.setFlags(Tag.NAME);
        return true;
      }
      // test if it is a known word 
      term.setCharAt(0, Char.toLower(c1));
      word = CharDic.word(term);
      if (word == null) { 
        // unknown, restore cap, let other filters say better
        term.setCharAt(0, Char.toUpper(c1));
        return true;
      }
    }
    else {
      word = CharDic.word(term);
      if (word == null) return true;
    }
    // known word
    flagsAtt.setFlags(word.tag);
    if (word.lem != null) {
      lem.append(word.lem);
    }
    return true;
  }
  @Override
  public void reset() throws IOException {
    input.reset();
    save = null;
  }
  static class TestAnalyzer extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new LemFilter(source);
      return new TokenStreamComponents(source, result);
    }

  }

  public static void main(String[] args) throws IOException
  {
    Analyzer analyzer = new TestAnalyzer();
    Chain test = new Chain("Alain");
    CharAtt att = new CharAtt(test);
    System.out.println("'"+test+"' '"+att+"'");
    System.out.println(CharDic.name(att));
    System.out.println(analyzer.getClass());
    System.out.println();
    
    /*
    Path path = Paths.get("work/zola.xml");
    InputStream is = Files.newInputStream(path);
    BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    */
    // text to tokenize
    final String text = "<p xml:id='pp'>Qu'en penses-tu ? Je n'en sais rien, Henri, c'est bidon."
        + "C’est m&eacute;connaître 1,5 &lt; -1.5 cts &amp; m<b>o</b>ts, avec de <i>l'italique</i>"
        + " -- Quadratin. U.K.N.O.W.N. La Fontaine... Quoi ???" + " Problème</section>. FIN.";

    TokenStream ts = analyzer.tokenStream("field", new StringReader(text));

    // get the CharTermAttribute from the TokenStream
    CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
    LemAtt lem = ts.addAttribute(LemAtt.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);
    OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);
    PositionIncrementAttribute pos = ts.addAttribute(PositionIncrementAttribute.class);

    try {
      ts.reset();
      // print all tokens until stream is exhausted
      while (ts.incrementToken()) {
        System.out.println(term + " \"" + lem + "\" " + Tag.label(flags.getFlags()) + " " + pos.getPositionIncrement() + " " + " |"
            + text.substring(offset.startOffset(), offset.endOffset()) + "|");
      }
      ts.end();
    }
    finally {
      ts.close();
      analyzer.close();
    }
    System.out.println("END ?");
  }
}
