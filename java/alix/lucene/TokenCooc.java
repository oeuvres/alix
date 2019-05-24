package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
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
public class TokenCooc extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as an int, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A dictionary to populate with the token stream */
  private final CharsAttDic dic;
  /** Pivot token */
  private final CharsAtt pivot;
  /** Windows */
  private final CharsAttWin win;
  /** Left width */
  private final int left;
  /** Right width */
  private final int right;
  /** Position globally incremented */
  private int pos = 0;
  /** Last position of a pivot */
  private int lastpos = 0;

  /**
   * Constructor
   * 
   * @param in
   *          the source of tokens
   * @param dic
   *          a dictionary to populate with counts
   */
  public TokenCooc(TokenStream in, final CharsAttDic dic, final CharsAtt pivot, final int left, final int right)
  {
    super(in);
    this.dic = dic;
    this.pivot = pivot;
    this.win = new CharsAttWin(left, right);
    this.left = left;
    this.right = right;
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    // end of stream
    if (!input.incrementToken()) return false;
    CharsAtt term = (CharsAtt)termAtt;
    win.push(term);
    if (lastpos >= 0) {
      if (pos - lastpos >= right) {
        lastpos = -1;
        for (int i = left; i <= right; i++) dic.inc(win.get(i));
      }
    } else if(pivot.equals(term)) {
      lastpos = pos;
    }
    // dic.inc((CharsAtt) termAtt, flagsAtt.getFlags());
    pos ++;
    return true;
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

  public static class AnalyzerCooc extends Analyzer
  {
    final CharsAttDic dic;
    final CharsAtt pivot;
    final int left;
    final int right;

    public AnalyzerCooc(final CharsAttDic dic, String pivot, final int left, final int right)
    {
      this.dic = dic;
      this.pivot = new CharsAtt(pivot);
      this.right = right;
      this.left = left;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new TokenizerFr();
      TokenStream result = new TokenLem(source);
      result = new TokenLemCloud(result);
      result = new TokenCooc(result, dic, pivot, left, right);
      return new TokenStreamComponents(source, result);
    }

  }
}
