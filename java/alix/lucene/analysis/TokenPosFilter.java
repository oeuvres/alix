package alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag.TagFilter;

/**
 * A token Filter to plus after a lemmatizer filter to choose between token to
 * 
 * @author fred
 *
 */
public class TokenPosFilter extends FilteringTokenFilter
{
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Filter tags */
  final TagFilter filter;

  /**
   * 
   * @param in
   */
  public TokenPosFilter(TokenStream in, final TagFilter filter)
  {
    super(in);
    this.filter = filter;
  }

  @Override
  protected boolean accept() throws IOException
  {
    return filter.accept(flagsAtt.getFlags());
  }

}
