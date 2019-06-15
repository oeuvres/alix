package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import alix.fr.dic.Tag;

/**
 * A token Filter to plug after a TokenLem filter, overriding positions with lemma and POS
 * 
 * @author fred
 *
 */
public class TokenLemFull extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt charsLemAtt = addAttribute(CharsLemAtt.class); // ? needs to be declared in the tokenizer
  /** A lemma when possible */
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class); // ? needs to be declared in the tokenizer
  /** Flag to say a tag has to be send */
  int tag;
  /** Flag to say */
  boolean lem;
  
  @Override
  public boolean incrementToken() throws IOException
  {
    CharTermAttribute term = this.termAtt;
    // purge things
    if (lem) {
      posIncAtt.setPositionIncrement(0);
      term.setEmpty().append(charsLemAtt);
      lem = false;
      return true;
    }
    if (tag != Tag.NULL) {
      posIncAtt.setPositionIncrement(0);
      term.setEmpty().append(Tag.label(tag));
      tag = Tag.NULL;
      return true;
    }
    
    // end of stream
    if (!input.incrementToken()) return false;
    tag = flagsAtt.getFlags();
    if (this.charsLemAtt.length() != 0) lem = true;
    return true;
  }

  /**
   * 
   * @param in
   */
  public TokenLemFull(TokenStream in)
  {
    super(in);
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
  }

  @Override
  public void end() throws IOException
  {
    super.end();
  }

}
