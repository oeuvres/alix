package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.dic.Tag;

public class TokenCount extends FilteringTokenFilter
{
  /** Count tokens for this pass */
  int count;
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class); // ? needs to be declared in the tokenizer

  public TokenCount(TokenStream in)
  {
    super(in);
  }

  @Override
  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    if (!Tag.isPun(tag)) count++;
    return true;
  }

  public int count() {
    return count;
  }
  
  @Override
  public void reset() throws IOException
  {
    super.reset();
    count = 0;
  }

}
