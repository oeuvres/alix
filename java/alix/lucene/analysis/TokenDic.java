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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;

/**
 * A token Filter writing terms to a dictionary.
 * Needs a specific implementation of CharTermAttribute : CharsAtt.
 * An AttributeFactory is needed.
 * 
 * @author fred
 *
 */
public class TokenDic extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A dictionary to populate with the token stream */
  private final CharsDic dic;
  /** A linguistic category as an int, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

  @Override
  public boolean incrementToken() throws IOException
  {
    // end of stream
    if (!input.incrementToken()) return false;
    dic.inc((CharsAtt) termAtt, flagsAtt.getFlags());
    return true;
  }

  /**
   * Constructor
   * 
   * @param in
   *          the source of tokens
   * @param dic
   *          a dictionary to populate with counts
   */
  public TokenDic(TokenStream in, final CharsDic dic)
  {
    super(in);
    this.dic = dic;
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

  public static class AnalyzerDic extends Analyzer
  {
    final CharsDic dic;

    public AnalyzerDic(final CharsDic dic)
    {
      this.dic = dic;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new TokenizerFr();
      TokenStream result = new TokenLem(source);
      result = new TokenCompound(result, 5);
      if (fieldName.equals("name")) {
        TagFilter tags = new TagFilter().setName();
        result = new TokenPosFilter(result, tags);
      }
      else if (fieldName.equals("sub")) {
        TagFilter tags = new TagFilter().setGroup(Tag.SUB);
        result = new TokenPosFilter(result, tags);
      }
      else {
        result = new TokenLemCloud(result);
      }
      result = new TokenDic(result, dic);
      return new TokenStreamComponents(source, result);
    }

  }

}
