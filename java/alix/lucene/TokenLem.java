package alix.lucene;

import java.io.IOException;
import java.util.HashSet;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.dic.Tag;
import alix.lucene.CharsMaps.LexEntry;
import alix.lucene.CharsMaps.NameEntry;
import alix.util.Calcul;
import alix.util.Char;

/**
 * 
 */
public final class TokenLem extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as an int, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** Last token was Punctuation */
  private boolean waspun = true; // first word considered as if it follows a dot
  /** Store state */
  private State save;
  /** For some tests */
  private final CharsAtt copy = new CharsAtt();


  
  /**
   *
   */
  public TokenLem(TokenStream input)
  {
    super(input);
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }
    // end of stream
    if (!input.incrementToken()) return false;
    final boolean waspun = this.waspun;
    CharsAtt term = (CharsAtt) termAtt;
    CharsAtt lem = (CharsAtt) lemAtt;
    int flags = flagsAtt.getFlags();
    // pass through zero-length terms
    if (term.length() == 0) return true;
    if (flags == Tag.PUNdiv || flags == Tag.PUNsent) {
      this.waspun = true;
      // clean the compound stack
      return true;
    }
    if (term.isEmpty()) {
      throw new IOException("Unexpected event, term is empty");
    }
    // Get first char
    char c1 = term.charAt(0);
    // Not a word
    if (!Char.isToken(c1)) return true;
    
    
    this.waspun = false;
    LexEntry word;
    NameEntry name;
    // norm case
    if (Char.isUpperCase(c1)) {
      
      copy.copy(term); // if nothing found, be conservative, restore (ex : USA, Grande-Bretagne)
      // acronyms, roman number
      if (term.length() > 1 && Char.isUpperCase(term.charAt(1))) {
        int roman = Calcul.roman2int(term.buffer());
        if (roman > 0) {
          flagsAtt.setFlags(Tag.NUM);
          term.setEmpty().append(Integer.toString(roman));
          return true;
        }
      }
      term.capitalize();
      boolean normalized = CharsMaps.norm(term); // normalise : coeur -> cœur, Etat -> État
      c1 = term.charAt(0); // get first char, may have been modified by normalization Etat -> État
      name = CharsMaps.name(term);
      if (name != null) {
        flagsAtt.setFlags(name.tag);
        if (name.orth != null) term.copy(name.orth);
        return true;
      }
      term.setCharAt(0, Character.toLowerCase(c1));
      word = CharsMaps.word(term);
      // if not after a pun, always capitalize, even if it's a known word (État...)
      if (!waspun) term.setCharAt(0, c1);
      // if word not found, infer it's a MAME
      if (word == null) {
        flagsAtt.setFlags(Tag.NAME);
        term.copy(copy);
        return true;
      }
    }
    else {
      CharsMaps.norm(term); // normalise oeil -> œil
      word = CharsMaps.word(term);
      if (word == null) return true;
    }
    // a word found in the dictionary
    if (word != null) {
      // known word
      flagsAtt.setFlags(word.tag);
      if (word.lem != null) {
        lem.append(word.lem);
      }
    }
    return true;
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    save = null;
    waspun = true;
  }
}
