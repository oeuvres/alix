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
import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.Tag;

/**
 * A token Filter writing terms to a dictionary. Needs a specific implementation
 * of CharTermAttribute : CharsAtt. An AttributeFactory is needed.
 * 
 * @author fred
 *
 */
public class TokenCooc extends FilteringTokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** A linguistic category as an int, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A dictionary to populate with the token stream */
  private final CharsDic dic;
  /** Pivot token */
  private final CharsAtt pivot;
  /** Windows */
  private final CharsAttWin win;
  /** Left width */
  private final int left;
  /** Right width */
  private final int right;
  /** Position globally incremented */
  private int pos = 0;
  /** Last position of a pivot */
  private int lastpos = 0;

  /**
   * Constructor
   * 
   * @param in
   *          the source of tokens
   * @param dic
   *          a dictionary to populate with counts
   */
  public TokenCooc(TokenStream in, final CharsDic dic, final CharsAtt pivot, final int left, final int right)
  {
    super(in);
    this.dic = dic;
    this.pivot = pivot;
    this.win = new CharsAttWin(left, right);
    this.left = left;
    this.right = right;
  }

  @Override
  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    if (Tag.isPun(tag)) return false;
    // replace term by lemma for adjectives and verbs
    CharsAtt term = (CharsAtt) termAtt;
    if (Tag.isVerb(tag)) if (lemAtt.length() != 0) term.setEmpty().append(lemAtt);
    win.push(term);
    if (lastpos >= 0) {
      if (pos - lastpos >= right) {
        lastpos = -1;
        for (int i = left; i <= right; i++)
          dic.inc(win.get(i));
      }
    }
    else if (pivot.equals(term)) {
      lastpos = pos;
    }
    pos++;
    return true;
  }

  public static class AnalyzerCooc extends Analyzer
  {
    final CharsDic dic;
    final CharsAtt pivot;
    final int left;
    final int right;

    public AnalyzerCooc(final CharsDic dic, String pivot, final int left, final int right)
    {
      this.dic = dic;
      this.pivot = new CharsAtt(pivot);
      this.right = right;
      this.left = left;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new TokenizerFr();
      TokenStream result = new TokenLem(source);
      result = new TokenCooc(result, dic, pivot, left, right);
      return new TokenStreamComponents(source, result);
    }

  }
}
