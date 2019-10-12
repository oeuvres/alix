/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
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

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import alix.fr.Tag;

/**
 * A token filter counting tokens. 
 * Before a CachingTokenFilter, it allows to get some 
 * counts if the token stream has been exhausted.
 */

public class TokenOffsets extends FilteringTokenFilter
{
  /** Count tokens for this pass */
  int length;
  /** Last position */
  int pos;
  /** The term provided by the Tokenizer */
  PositionIncrementAttribute posAtt = addAttribute(PositionIncrementAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

  public TokenOffsets(TokenStream in)
  {
    super(in);
  }

  @Override
  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    if (!Tag.isPun(tag)) length++;
    pos += posAtt.getPositionIncrement();
    return true;
  }

  /**
   * Returns the width of document in positions (with also the skipped ones).
   * @return
   */
  public int width() {
    return pos;
  }

  /**
   * Returns the count of tokens.
   * @return
   */
  public int length() {
    return length;
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    length = 0;
    pos = 0;
  }

}
