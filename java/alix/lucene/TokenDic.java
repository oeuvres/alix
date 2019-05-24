package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.dic.Tag;
import alix.fr.dic.Tag.TagFilter;

/**
 * A token Filter writing terms to a dictionary.
 * Needs a specific implementation of CharTermAttribute : CharsAtt.
 * An AttributeFactory is needed.
 * 
 * @author fred
 *
 */
public class TokenDic extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A dictionary to populate with the token stream */
  private final CharsAttDic dic;
  /** A linguistic category as an int, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

  @Override
  public boolean incrementToken() throws IOException
  {
    // end of stream
    if (!input.incrementToken()) return false;
    dic.inc((CharsAtt) termAtt, flagsAtt.getFlags());
    return true;
  }

  /**
   * Constructor
   * 
   * @param in
   *          the source of tokens
   * @param dic
   *          a dictionary to populate with counts
   */
  public TokenDic(TokenStream in, final CharsAttDic dic)
  {
    super(in);
    this.dic = dic;
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

  public static class AnalyzerDic extends Analyzer
  {
    final CharsAttDic dic;

    public AnalyzerDic(final CharsAttDic dic)
    {
      this.dic = dic;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new TokenizerFr();
      TokenStream result = new TokenLem(source);
      if (fieldName.equals("cloud")) {
        result = new TokenLemCloud(result);
      }
      else if (fieldName.equals("name")) {
        TagFilter tags = new TagFilter().setName();
        result = new TokenPosFilter(result, tags);
      }
      else if (fieldName.equals("sub")) {
        TagFilter tags = new TagFilter().setGroup(Tag.SUB);
        result = new TokenPosFilter(result, tags);
      }
      result = new TokenDic(result, dic);
      return new TokenStreamComponents(source, result);
    }

  }

}
