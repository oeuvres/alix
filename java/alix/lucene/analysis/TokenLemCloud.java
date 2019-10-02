package alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag;

/**
 * A token Filter to plug after a lemmatizer filter. Positions of striped tokens
 * are deleted. 
 * 
 * @author fred
 *
 */
public class TokenLemCloud extends TokenFilter
{
  // no sense to record stats here if filter is not behind a caching filer
  // exhausting tokens before the index is writed.
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class); // ? needs to be declared in the tokenizer

  public TokenLemCloud(TokenStream in)
  {
    super(in);
  }

  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    // filter some non semantic token
    if (Tag.isPun(tag) || Tag.isNum(tag)) return false;
    // filter some names
    if (Tag.isName(tag)) {
      if (termAtt.length() < 3) return false;
      // filter first names ?
      return true;
    }
    // replace term by lemma for adjectives and verbs
    if (Tag.isAdj(tag) || Tag.isVerb(tag) || Tag.isSub(tag))
      if (lemAtt.length() != 0) termAtt.setEmpty().append(lemAtt);
    return true;
  }

  @Override
  public final boolean incrementToken() throws IOException
  {
    while (input.incrementToken()) {
      if (accept()) return true;
    }
    return false;
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

}
