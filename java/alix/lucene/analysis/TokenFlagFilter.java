/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache licence.
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
import java.util.BitSet;

import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag.TagFilter; // for documentation

/**
 * A token Filter selecting tokens by flag values.
 * The set of selected int values is efficiently given by a {@link BitSet}.
 * See {@link TagFilter#bits()} for an example of such filter, 
 * providing codes for part-of-speech.
 * Positions with holes are kept.
 */
@SuppressWarnings("unused")
public class TokenFlagFilter extends FilteringTokenFilter
{
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Filter tags */
  final BitSet filter;

  /**
   * 
   * @param in
   */
  public TokenFlagFilter(TokenStream in, final BitSet filter)
  {
    super(in);
    this.filter = filter;
  }

  @Override
  protected boolean accept() throws IOException
  {
    return filter.get(flagsAtt.getFlags());
  }

}
