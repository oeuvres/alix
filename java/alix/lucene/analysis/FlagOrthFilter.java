/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
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
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

/**
 * A final token filter before indexation,
 * to plug after a lemmatizer filter,
 * keep orthographic normalize form 
 * (names with capital, common words with 
 */
public class FlagOrthFilter extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** The position increment (inform it if positions are stripped) */
  private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
  /** A linguistic category as a short number, see {@link Tag} */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A normalized orthographic form */
  private final CharsOrthAtt orthAtt = addAttribute(CharsOrthAtt.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);


  public FlagOrthFilter(TokenStream in)
  {
    super(in);
  }

  @Override
  public final boolean incrementToken() throws IOException
  {
    // squeeze deleted positions, do not record holes
    while (input.incrementToken()) {
      if (accept()) return true;
    }
    return false;
  }

  /**
   * Most of the tokens are not rejected but rewrited
   * @return
   * @throws IOException
   */
  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    if (tag == Tag.TEST.flag) {
      System.out.println(termAtt+" orth="+orthAtt+" lem="+lemAtt);
    }
    // record an empty token at punctuation position for collocations
    if (Tag.PUN.sameParent(tag)) {
      if (tag == Tag.PUNcl.flag) termAtt.setEmpty().append(",");
      else if (tag == Tag.PUNsent.flag) termAtt.setEmpty().append(".");
      else if (tag == Tag.PUNdiv.flag) termAtt.setEmpty().append("§");
      else termAtt.setEmpty().append("");
    }
    // unify numbers
    else if (Tag.NUM.sameParent(tag)) {
      termAtt.setEmpty().append("NUM");
    }
    // replace term by normalized form if available
    else if (orthAtt.length() != 0) {
      termAtt.setEmpty().append(orthAtt);
    }
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

}
