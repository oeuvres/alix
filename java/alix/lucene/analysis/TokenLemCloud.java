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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag;

/**
 * A final token filter before indexation,
 * to plug after a lemmatizer filter,
 * providing most significant tokens for word cloud. 
 * Index lemma instead of forms when available.
 * Strip punctuation and numbers.
 * Positions of striped tokens  are deleted.
 * This allows simple computation of a token context
 * (ex: span queries, co-occurrences).
 */
public class TokenLemCloud extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, see {@link Tag} */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A normalized orthographic form */
  private final CharsOrthAtt orthAtt = addAttribute(CharsOrthAtt.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);

  public TokenLemCloud(TokenStream in)
  {
    super(in);
  }

  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    // filter some non semantic token
    if (Tag.isPun(tag) || Tag.isNum(tag)) return false;
    // replace term by lemma for substantives, adjectives and verbs
    if ((Tag.isAdj(tag) || Tag.isVerb(tag) || Tag.isSub(tag)) && (lemAtt.length() != 0)) {
      termAtt.setEmpty().append(lemAtt);
    }
    // or take the normalized form
    else {
      termAtt.setEmpty().append(orthAtt);
    }
    // filter some names
    if (Tag.isName(tag)) {
      // if (termAtt.length() < 3) return false;
      // filter first names ?
      return true;
    }
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
