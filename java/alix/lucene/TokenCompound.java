package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource.State;

import alix.fr.dic.Tag;

/**
 * Plug behind TokenLem
 * @author fred
 *
 */
public class TokenCompound extends TokenFilter
{
  /** Size of a compound */
  private int skippedPositions;
  /** Increment position of a token */
  private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
  /** Position length of a token */
  private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** */
  private final Compound cache;

  /** Number of tokens to cache for compounds */
  final int size;
  protected TokenCompound(TokenStream input, final int size)
  {
    super(input);
    this.size = size;
    this.cache = new Compound(size);
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (!input.incrementToken()) {
      // empty the cache
      return false;
    }
    int tag = flagsAtt.getFlags();
    // compound names
    if (Tag.isName(tag)) {
      compound.start(term, offsetAtt.startOffset(), offsetAtt.endOffset());
      while (input.incrementToken()) {
        if ()
      }
    }
    
    
    // compounds start by lem, ex : faire comme si
    else if (lem.length() != 0) {
      if (!CharsMaps.compound1(lem)) return true;
    }
    else {
      if (!CharsMaps.compound1(term)) return true;
    }
    
    
    while (input.incrementToken()) {
      if (!cache.isFull()) {
        State state = captureState();
        cache.push(state);
      }
      if cha
      if (accept()) {
        if (skippedPositions != 0) {
          posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + skippedPositions);
        }
        return true;
      }
      skippedPositions += posIncrAtt.getPositionIncrement();
    }
    // reached EOS -- return false
    return false;
  }
  @Override
  public void reset() throws IOException {
    super.reset();
    skippedPositions = 0;
  }

  @Override
  public void end() throws IOException {
    super.end();
    posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + skippedPositions);
  }
}
