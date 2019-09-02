package alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.CharsMaps.LexEntry;
import alix.lucene.analysis.CharsMaps.NameEntry;
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
  /**  */


  
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
      return true;
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
      // roman number already detected
      if (flagsAtt.getFlags() == Tag.NUM) return true;
      int n = Calcul.roman2int(term.buffer(), 0, term.length());
      if (n > 0) {
        flagsAtt.setFlags(Tag.NUM);
        return true;
      }
      // USA ?
      term.capitalize(); // GRANDE-BRETAGNE -> Grande-Bretagne
      CharsMaps.norm(term); // normalise : Etat -> État
      copy.copy(term);
      c1 = term.charAt(0); // keep initial cap, maybe useful
      name = CharsMaps.name(term); // known name ?
      if (name != null) {
        flagsAtt.setFlags(name.tag);
        if (name.orth != null) term.copy(name.orth);
        return true;
      }
      word = CharsMaps.word(term.toLower()); // known word ?
      if (word != null) { // known word
        // if not after a pun, maybe a capitalized concept État, or a name La Fontaine, 
        // or a title — Le Siècle, La Plume, La Nouvelle Revue, etc. 
        // if (!waspun)
        flagsAtt.setFlags(word.tag);
        if (word.lem != null) {
          lem.append(word.lem);
        }
        return true;
      }
      else { // unknown word, infer it's a MAME
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
