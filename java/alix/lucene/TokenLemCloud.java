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
 * A token Filter to plus after a lemmatizer filter to choose between token to
 * 
 * @author fred
 *
 */
public class TokenLemCloud extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final TokenAttLem tokenAttLem = addAttribute(TokenAttLem.class); // ? needs to be declared in the tokenizer

  @Override
  public boolean incrementToken() throws IOException
  {
    // end of stream
    if (!input.incrementToken()) return false;
    int tag = flagsAtt.getFlags();
    // replace term by lemma for adjectives and verbs
    if (Tag.isAdj(tag) || Tag.isVerb(tag))
      if (tokenAttLem.length() != 0)
        termAtt.setEmpty().append(tokenAttLem);
    return true;
  }

  /**
   * 
   * @param in
   */
  public TokenLemCloud(TokenStream in)
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
