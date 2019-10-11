/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import alix.fr.Tag;

/**
 * A token Filter to plug after a TokenLem filter, overriding positions with lemma and POS
 * 
 * @author fred
 *
 */
public class TokenLemFull extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt charsLemAtt = addAttribute(CharsLemAtt.class); // ? needs to be declared in the tokenizer
  /** A lemma when possible */
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class); // ? needs to be declared in the tokenizer
  /** Flag to say a tag has to be send */
  int tag;
  /** Flag to say */
  boolean lem;
  
  @Override
  public boolean incrementToken() throws IOException
  {
    CharTermAttribute term = this.termAtt;
    // purge things
    if (lem) {
      posIncAtt.setPositionIncrement(0);
      term.setEmpty().append(charsLemAtt);
      lem = false;
      return true;
    }
    if (tag != Tag.NULL) {
      posIncAtt.setPositionIncrement(0);
      term.setEmpty().append(Tag.label(tag));
      tag = Tag.NULL;
      return true;
    }
    
    // end of stream
    if (!input.incrementToken()) return false;
    tag = flagsAtt.getFlags();
    if (this.charsLemAtt.length() != 0) lem = true;
    return true;
  }

  /**
   * 
   * @param in
   */
  public TokenLemFull(TokenStream in)
  {
    super(in);
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
