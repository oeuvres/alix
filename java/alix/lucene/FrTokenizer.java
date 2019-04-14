package alix.lucene;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.AttributeImpl;

import alix.fr.dic.Tag;
import alix.util.Char;

/**
 * A lucene tokenizer for French, adapted fron Lucene CharTokenizer.
 * 
 * @author glorieux-f
 *
 */
public class FrTokenizer extends Tokenizer
{
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term, as an array of chars */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** Tool for string testings */
  CharAtt test = new CharAtt();
  /** Store state */
  private State save;
  /** Source buffer of chars, delegate to Lucene experts */
  private final CharacterBuffer bufSrc = CharacterUtils.newCharacterBuffer(4096);
  /** Pointer in buffer */
  private int bufIndex = 0;
  /** Length of the buffer */
  private int bufLen = 0;
  /** Max length for a token */
  int maxTokenLen = 256;
  /** Input offset */
  private int offset = 0;
  /** Final input offset */
  private int finalOffset = 0;
  boolean xml = true;
  /** tags to send and translate */
  public static final HashMap<String, String> TAGS = new HashMap<String, String>();
  static {
    TAGS.put("p", "<p>");
    TAGS.put("section", "<section>");
    TAGS.put("/section", "</section>");
  }

  public FrTokenizer()
  {
    this(true);
  }

  /**
   * Handle xml tags ?
   * 
   * @param ml
   */
  public FrTokenizer(boolean xml)
  {
    super(new AlixAttributeFactory(AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY));
    this.xml = xml;
  }


  @Override
  public final boolean incrementToken() throws IOException
  {
    // The 
    clearAttributes();
    // send term event
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }

    int length = 0;
    int startOffset = -1; // this variable is always initialized
    int endOffset = -1;
    int ltOffset = -1;
    CharAtt term = (CharAtt)this.termAtt;
    CharAtt test = this.test;
    char[] buffer = bufSrc.getBuffer();
    boolean intag = false;
    boolean tagname = false;
    boolean xmlent = false;
    char lastChar = 0;

    boolean pun = false;

    while (true) {
      // grab more chars
      if (bufIndex >= bufLen) {
        offset += bufLen;
        // use default lucene code to read chars from source
        CharacterUtils.fill(bufSrc, input); // read supplementary char aware with CharacterUtils
        if (bufSrc.getLength() == 0) {
          bufLen = 0; // so next offset += bufLen won't decrement offset
          if (length > 0) {
            break;
          }
          else { // finish !
            finalOffset = correctOffset(offset);
            return false;
          }
        }
        bufLen = bufSrc.getLength();
        bufIndex = 0;
      }

      char c = buffer[bufIndex];
      bufIndex++;
      // got a char, let's work

      // a very light XML parser
      if (!xml) ;
      else if (c == '<') { // start tag
        // keep memory of start index of this tag
        ltOffset = offset + bufIndex - 1;
        intag = true;
        tagname = true;
        continue;
      }
      else if (intag) { // inside tag
        if (tagname) {
          if (!test.isEmpty() && (c == ' ' || c == '>' || (c == '/'))) tagname = false;
          else test.append(c);
        }
        if (c == '>') {
          intag = false;
          String el = TAGS.get(test); // test the tagname
          test.setEmpty();
          if (el == null) continue; // jump unknown tag
          // Known tag to send
          if (length != 0) { // A word has been started
            // save state with the word
            State state = captureState();
            // save state with xml tag for next
            offsetAtt.setOffset(correctOffset(ltOffset), correctOffset(offset + bufIndex));
            termAtt.setEmpty().append(el);
            flagsAtt.setFlags(Tag.PUNdiv);
            save = captureState();
            // send the word
            restoreState(state);
            break;
          }
          // A tag has to be sent
          startOffset = ltOffset;
          endOffset = offset + bufIndex;
          term.append(el);
          length = el.length();
          flagsAtt.setFlags(Tag.PUNdiv);
          break;
        }
        continue;
      }
      else if (c == '&') {
        if (length == 0) startOffset = offset + bufIndex - 1;
        xmlent = true;
        test.setEmpty();
        test.append(c);
        continue;
      }
      else if (xmlent == true) {
        test.append(c);
        if (c != ';') continue;
        // end of entity
        xmlent = false;
        c = Char.HTMLENT.get(test);
        test.setEmpty();
        term.append(c);
        endOffset = offset + bufIndex; // update offset to the end of entity
        length++;
        continue;
      }
      // decimals
      if (Char.isDigit(lastChar) && (c == '.' || c == ',')) {
        term.append(c);
        endOffset++;
        length++;
        continue;
      }

      // Sentence punctuation
      if (c == '.' || c == '…' || c == '?' || c == '!') {
        // a word have been started, send it
        if (length > 0 && !pun) {
          bufIndex--; // restart parser at this position
          break;
        }
        pun = true; // a flag used for ..., ???
        flagsAtt.setFlags(Tag.PUNsent);
        if (length == 0) { // start of token
          endOffset = startOffset = offset + bufIndex - 1;
          // record only the first char as a sentence delimiter
          term.append(c);
          endOffset++;
          length++;
          continue;
        }
        else {
          endOffset++;
          continue;
        }
      }
      // break on hyphen, let a filter restore compound
      if (c == '-' && length != 0) {
        // bufIndex--;
        break;
      }
      // word starting by a dot, pb
      if (pun) {
        bufIndex--; // restart parser at this position
        break;
      }
      if (Char.isToken(c)) { // it's a token char
        // start of token, record startOffset
        if (length == 0) startOffset = offset + bufIndex - 1;

        endOffset = offset + bufIndex;
        // soft hyphen, do not append to term
        if (c == (char) 0xAD) continue;
        if (c == '’') c = '\''; // normalize apos
        term.append(c);
        length++;
        if (c == '\'') {
          CharAtt val = CharDic.ELLISION.get(term);
          if (val != null) {
            val.copyTo(term);
            break;
          }
        }
        if (length >= maxTokenLen) break; // a too big token stop
      }
      //
      else if (length > 0) { // at non-Letter w/ chars
        break; // return 'em
      }
      lastChar = c;
    }
    // send term event
    termAtt.setLength(length);
    assert startOffset != -1;
    startOffset = correctOffset(startOffset);
    finalOffset = correctOffset(endOffset);
    offsetAtt.setOffset(startOffset, finalOffset);
    return true;

  }

  @Override
  public final void end() throws IOException
  {
    super.end();
    // set final offset
    offsetAtt.setOffset(finalOffset, finalOffset);
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    bufIndex = 0;
    offset = 0;
    bufLen = 0;
    finalOffset = 0;
    bufSrc.reset(); // make sure to reset the IO buffer!!
  }

 
  /**
   * An attribute factory 
   * @author fred
   *
   */
  private static final class AlixAttributeFactory extends AttributeFactory {
    private final AttributeFactory delegate;

    public AlixAttributeFactory(AttributeFactory delegate) {
      this.delegate = delegate;
    }

    @Override
    public AttributeImpl createAttributeInstance(Class<? extends Attribute> attClass) {
      if (attClass == CharTermAttribute.class)
        return new CharAtt();
      return delegate.createAttributeInstance(attClass);
    }
  }

  static class TestAnalyzer extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      return new TokenStreamComponents(source);
    }

  }

  public static void main(String[] args) throws IOException
  {
    // text to tokenize
    final String text = "<p xml:id='pp'>Qu'en penses-tu ? "
        + "C’est m&eacute;connaître 1,5 &lt; -1.5 cts &amp; M<b>o</b>t Avec de <i>l'italique</i>"
        + " -- Quadratin. U.K.N.O.W.N. La Fontaine... Quoi ???" + " Problème</section>. FIN.";

    Analyzer[] analyzers = { new TestAnalyzer(),
        // new StandardAnalyzer(),
        // new FrenchAnalyzer()
    };
    for (Analyzer analyzer : analyzers) {
      System.out.println(analyzer.getClass());
      System.out.println();
      TokenStream stream = analyzer.tokenStream("field", new StringReader(text));

      // get the CharTermAttribute from the TokenStream
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
      FlagsAttribute flags = stream.addAttribute(FlagsAttribute.class);
      PositionIncrementAttribute pos = stream.addAttribute(PositionIncrementAttribute.class);

      try {
        stream.reset();
        // print all tokens until stream is exhausted
        while (stream.incrementToken()) {
          System.out.println("\"" + term + "\" " + " " + offset.startOffset() + " " + offset.endOffset()+ " " + Tag.label(flags.getFlags()) + " |"
              + text.substring(offset.startOffset(), offset.endOffset()) + "|");
        }
        stream.end();
      }
      finally {
        stream.close();
        analyzer.close();
      }
      System.out.println();
    }
  }
}
