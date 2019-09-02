package alix.lucene.analysis;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.CharsMaps.NameEntry;

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
    for (String w : new String[] { "d'", "D'", "de", "De", "du", "Du", "l'", "L'", "le", "Le", "la", "La", "von", "Von" })
      PARTICLES.add(new CharsAtt(w));
  }
  /** Increment position of a token */
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
  /** Position length of a token */
  private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A lemma when possible */
  // private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** A stack of sates  */
  private LinkedList<State> stack = new LinkedList<State>();
  /** A term used to concat names */
  private CharsAtt name = new CharsAtt();

  /** Number of tokens to cache for compounds */
  final int size;
  public TokenCompound(TokenStream input, final int size)
  {
    super(input);
    this.size = size;
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (!stack.isEmpty()) {
      restoreState(stack.removeLast());
      return true;
    }
    if (!input.incrementToken()) {
      return false;
    }
    CharTermAttribute term = termAtt;
    PositionIncrementAttribute posInc = posIncAtt;
    OffsetAttribute offset = offsetAtt;
    FlagsAttribute flags = flagsAtt;
    final int tag = flags.getFlags();
    
    // Bad Pologne d'Ubu,  Zarathoustra de Nietzsche
    
    // test compound names : NAME (particle|NAME)* NAME
    if (Tag.isName(tag)) {
      final int startOffset = offsetAtt.startOffset();
      int endOffset = offsetAtt.endOffset();
      int pos = posInc.getPositionIncrement(); 
      name.copy(term);
      int lastlen = name.length();
      boolean notlast;
      while ((notlast = input.incrementToken())) {
        if (Tag.isName(flags.getFlags())) {
          endOffset = offset.endOffset();
          if (name.charAt(name.length()-1) != '\'') name.append(' ');
          name.append(term);
          lastlen = name.length(); // store the last length of name
          stack.clear(); // empty the stored paticles
          pos += posInc.getPositionIncrement(); // incremen tposition
          continue;
        }
        // test if it is a particle, but store it, avoid [Europe de l']atome
        if (PARTICLES.contains(term)) {
          stack.addFirst(captureState());
          name.append(' ').append(term);
          pos += posInc.getPositionIncrement();
          continue;
        }
        break;
      }
      // are there particles to exhaust ?
      if (!stack.isEmpty()) {
        pos = pos - stack.size();
        name.setLength(lastlen);
      }
      if (notlast) stack.addFirst(captureState());
      offsetAtt.setOffset(startOffset, endOffset);
      posIncAtt.setPositionIncrement(pos);
      posLenAtt.setPositionLength(pos);
      // get tag
      NameEntry entry = CharsMaps.name(name);
      if (entry == null) {
        flagsAtt.setFlags(Tag.NAME);
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
  }

  @Override
  public void end() throws IOException {
    super.end();
  }
}
