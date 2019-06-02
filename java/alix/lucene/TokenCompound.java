package alix.lucene;

import java.io.IOException;
import java.util.HashSet;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource.State;

import alix.fr.dic.Tag;
import alix.lucene.CharsMaps.NameEntry;

/**
 * Plug behind TokenLem
 * @author fred
 *
 */
public class TokenCompound extends TokenFilter
{
  /**
   * French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel
   */
  public static final HashSet<CharsAtt> PARTICLES = new HashSet<CharsAtt>();
  static {
    for (String w : new String[] { "d'", "D'", "de", "De", "du", "Du", "l'", "L'", "le", "la", "von", "Von" })
      PARTICLES.add(new CharsAtt(w));
  }
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
  /** Store state */
  private State save;
  /** A term used to concat names */
  private CharsAtt name = new CharsAtt();

  /** Number of tokens to cache for compounds */
  final int size;
  protected TokenCompound(TokenStream input, final int size)
  {
    super(input);
    this.size = size;
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }
    if (!input.incrementToken()) {
      return false;
    }
    CharTermAttribute term = termAtt;
    final int tag = flagsAtt.getFlags();
    
    // test if compound names
    if (Tag.isName(tag)) {
      final int startOffset = offsetAtt.startOffset();
      int endOffset = offsetAtt.endOffset();
      int pos = 1; 
      name.copy(term);
      boolean notlast;
      while ((notlast = input.incrementToken())) {
        // end of name
        if (!Tag.isName(flagsAtt.getFlags()) && !PARTICLES.contains(term)) break;
        endOffset = offsetAtt.endOffset();
        pos++;
        if (name.charAt(name.length()-1) != '\'') name.append(' ');
        name.append(term);
      }
      if (notlast) save = captureState();
      offsetAtt.setOffset(startOffset, endOffset);
      // get tag
      NameEntry entry = CharsMaps.name(name);
      if (entry == null) {
        flagsAtt.setFlags(tag);
        term.setEmpty().append(name);
      }
      else {
        flagsAtt.setFlags(entry.tag);
        if (entry.orth != null) term.setEmpty().append(entry.orth);
        else term.setEmpty().append(name);
      }
      return true;
    }
    
    /*
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
     */
    return true;
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
