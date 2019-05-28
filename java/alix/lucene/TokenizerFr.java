package alix.lucene;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.BytesRef;

import alix.fr.dic.Tag;
import alix.util.Chain;
import alix.util.Char;

/**
 * A lucene tokenizer for French, adapted fron Lucene CharTokenizer.
 * 
 * @author glorieux-f
 *
 */
public class TokenizerFr extends Tokenizer
{
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term, as an array of chars */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** For string testings */
  CharsAtt test = new CharsAtt();
  /** For string storing */
  CharsAtt copy = new CharsAtt();
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
  /**
   * French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel
   */
  public static final HashSet<CharsAtt> HYPHEN_POST = new HashSet<CharsAtt>();
  static {
    for (String w : new String[] { "ce", "ci", "elle", "elles", "en", "eux", "il", "ils", "je", "la", "là", "le", "les",
        "leur", "lui", "me", "moi", "nous", "on", "t", "te", "toi", "tu", "vous", "y" })
      HYPHEN_POST.add(new CharsAtt(w));
  }

  /** Parse as XML */
  boolean xml = true;
  /** tags to send and translate */
  public static final HashMap<CharsAtt, CharsAtt> TAGS = new HashMap<CharsAtt, CharsAtt>();
  static {
    TAGS.put(new CharsAtt("p"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("section"), new CharsAtt("<section>"));
    TAGS.put(new CharsAtt("/section"), new CharsAtt("</section>"));
  }
  /** tag content to skip */
  public static final HashSet<CharsAtt> SKIP = new HashSet<CharsAtt>();
  static {
    SKIP.add(new CharsAtt("teiHeader"));
    SKIP.add(new CharsAtt("head"));
    SKIP.add(new CharsAtt("script"));
    SKIP.add(new CharsAtt("style"));
  }
  /** Store closing tag to skip */
  private CharsAtt skip = new CharsAtt();

  public TokenizerFr()
  {
    this(true);
  }

  /**
   * Handle xml tags ?
   * 
   * @param ml
   */
  public TokenizerFr(boolean xml)
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

    int startOffset = -1; // this variable is always initialized
    int ltOffset = -1;
    int hyphOffset = -1; // keep offset of last hyphen
    CharsAtt term = (CharsAtt) this.termAtt;
    CharsAtt test = this.test;
    FlagsAttribute flags = flagsAtt;
    char[] buffer = bufSrc.getBuffer();
    boolean intag = false;
    boolean tagname = false;
    boolean xmlent = false;
    char lastChar = 0;
    int endSub = 1; // subtract to offset to have precise endOffset

    boolean pun = false;
    char c = 0; // current char
    while (true) {
      final int length = term.length();
      // grab more chars
      if (bufIndex >= bufLen) {
        offset += bufLen;
        // use default lucene code to read chars from source
        CharacterUtils.fill(bufSrc, input); // read supplementary char aware with CharacterUtils
        bufLen = bufSrc.getLength();
        bufIndex = 0;
        // end of buffer
        if (bufLen != 0) ;
        else if (length > 0) { // a term to send
          endSub = 0;
          break;
        }
        else { // finish !
          finalOffset = correctOffset(offset);
          return false;
        }
      }

      // got a char, let's work
      c = buffer[bufIndex];
      bufIndex++;

      // a very light XML parser
      if (!xml) ;
      else if (c == '<') { // start tag
        // keep memory of start index of this tag
        ltOffset = offset + bufIndex - 1;
        intag = true;
        tagname = true;
        test.setEmpty();
        continue;
      }
      else if (intag) { // inside tag
        if (tagname) { // start to record tagname
          if (!test.isEmpty() && (c == ' ' || c == '>' || (c == '/'))) tagname = false;
          else test.append(c);
        }
        if (c == '>') {
          intag = false;
          // end of skip element
          if (!skip.isEmpty() && skip.equals(test)) {
            skip.setEmpty();
            continue;
          }
          // inside skip element
          else if (!skip.isEmpty()) {
            continue;
          }
          // start of a skip element
          else if (SKIP.contains(test)) {
            skip.setEmpty().append("/").append(test);
            continue;
          }
          CharsAtt el = TAGS.get(test); // test the tagname
          test.setEmpty();
          if (el == null) continue; // skip unknown tag
          // Known tag to send
          if (length != 0) { // A word has been started
            // save state with the word
            State state = captureState();
            // save state with xml tag for next
            offsetAtt.setOffset(correctOffset(ltOffset), correctOffset(offset + bufIndex));
            termAtt.setEmpty().append(el);
            flags.setFlags(Tag.PUNdiv);
            save = captureState();
            // send the word
            restoreState(state);
            break;
          }
          // A tag has to be sent
          startOffset = ltOffset;
          term.append(el);
          flags.setFlags(Tag.PUNdiv);
          break;
        }
        continue;
      }
      // inside a tag to skip, go throw
      else if (!skip.isEmpty()) {
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
        continue;
      }
      
      // Possible sentence delimiters
      if (c == '.' || c == '…' || c == '?' || c == '!' || c == '«' || c == '—' || c == ':') {
        // dot after a digit, decimal number
        if (length == 0) {
          flags.setFlags(Tag.PUNsent);
          startOffset = offset + bufIndex - 1;
          term.append(c);
          lastChar = c; // give for ..., or 1.
          continue;
        }
        // ... ???
        if (flags.getFlags() == Tag.PUNsent) {
          continue;
        }
        if (Char.isDigit(lastChar)) {
          term.append(c);
          lastChar = c; // for 6. 7.
          continue;
        }
        // test if it's an abreviation with a dot
        if (c == '.') {
          term.append('.');
          if (CharsMaps.brevidot(term)) {
            endSub = 0;
            break;
          }
          term.setLength(term.length() - 1);
          // restore
        }
        // seems a sentence dot 
        endSub = 0;
        bufIndex--;
        break;
      }

      // french decimals
      if (Char.isDigit(lastChar) && c == ',') {
        term.append(c);
        continue;
      }

      // store the position of an hyphen, and check if there is not one
      if (c == '-' && length != 0) {
        hyphOffset = offset + bufIndex;
        test.setEmpty();
      }
      if (Char.isToken(c)) { // it's a token char
        // start of token, record startOffset
        if (length == 0) {
          if (Char.isDigit(c)) flagsAtt.setFlags(Tag.NUM);
          startOffset = offset + bufIndex - 1;
        }

        // soft hyphen, do not append to term
        if (c == (char) 0xAD) continue;
        if (c == '’') c = '\''; // normalize apos
        term.append(c);
        if (hyphOffset > 0 && c != '-') test.append(c);
        if (c == '\'') {
          CharsAtt val = CharsMaps.ELLISION.get(term);
          if (val != null) {
            val.copyTo(term);
            break;
          }
        }
        if (length >= maxTokenLen) break; // a too big token stop
      }
      // a non token char, a word to send
      else if (length > 0) {
        break;
      }
      lastChar = c;
    }
    // send term event
    int endOffset = offset + bufIndex - endSub;
    // something like 1. 2.
    if ((lastChar == '.' || c == ')' || c == '°') && flags.getFlags() == Tag.NUM) {
      term.setEmpty().append('#');
      flags.setFlags(Tag.PUNsent);
    }
    // splitable hyphen ? split on souviens-toi, murmura-t-elle, but not
    // Joinville-le-Pont,
    if (hyphOffset > 0 && HYPHEN_POST.contains(test)) {
      // swap terms to store state of word after hyphen
      copy.copy(term);
      term.copy(test);
      offsetAtt.setOffset(correctOffset(hyphOffset - 1), correctOffset(endOffset));
      save = captureState();
      // send the word before hyphen
      term.copy(copy);
      endOffset = hyphOffset - 1;
    }
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
   * 
   * @author fred
   *
   */
  private static final class AlixAttributeFactory extends AttributeFactory
  {
    private final AttributeFactory delegate;

    public AlixAttributeFactory(AttributeFactory delegate)
    {
      this.delegate = delegate;
    }

    @Override
    public AttributeImpl createAttributeInstance(Class<? extends Attribute> attClass)
    {
      if (attClass == CharTermAttribute.class) return new CharsAtt();
      return delegate.createAttributeInstance(attClass);
    }
  }

  /**
   * Get offsets of a text as an array of ints that could be indexed in a binary
   * field.
   * 
   * @param text
   * @param offsets
   * @throws IOException
   */
  static public void offsets(String text, OffsetList offsets) throws IOException
  {
    offsets.reset();
    Tokenizer tokens = new TokenizerFr();
    tokens.setReader(new StringReader(text));
    // listen to offsets
    OffsetAttribute offsetAtt = tokens.addAttribute(OffsetAttribute.class);
    try {
      tokens.reset();
      while (tokens.incrementToken()) {
        offsets.put(offsetAtt.startOffset(), offsetAtt.endOffset());
      }
      tokens.end();
    }
    finally {
      tokens.close();
    }
  }

}
