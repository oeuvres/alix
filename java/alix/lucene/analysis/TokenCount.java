package alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import alix.fr.Tag;

public class TokenCount extends FilteringTokenFilter
{
  /** Count tokens for this pass */
  int length;
  /** Last position */
  int pos;
  /** The term provided by the Tokenizer */
  PositionIncrementAttribute posAtt = addAttribute(PositionIncrementAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

  public TokenCount(TokenStream in)
  {
    super(in);
  }

  @Override
  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    if (!Tag.isPun(tag)) length++;
    pos += posAtt.getPositionIncrement();
    return true;
  }

  /**
   * Returns the width of document in positions (with also the skipped ones).
   * @return
   */
  public int width() {
    return pos;
  }

  /**
   * Returns the count of tokens.
   * @return
   */
  public int length() {
    return length;
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    length = 0;
    pos = 0;
  }

}
