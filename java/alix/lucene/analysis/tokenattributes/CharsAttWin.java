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
package alix.lucene.analysis.tokenattributes;

/**
 * Used in a Lucene Analyzer, a LIFO stack of terms to keep trace of tokens for
 * compounds.
 * 
 * @author fred
 *
 */
public class CharsAttWin
{
  private final CharsAtt[] data;
  /** Size of left wing */
  protected final int left;
  /** Size of right wing */
  protected final int right;
  /** Size of the widow */
  protected final int size;
  /** Index of center cell */
  protected int center;
  


  public CharsAttWin(final int left, final int right) {
    if (left > 0)
      throw new IndexOutOfBoundsException(this.getClass().getName()+" left context should be negative or zero.");
    this.left = left;
    if (right < 0)
      throw new IndexOutOfBoundsException(this.getClass().getName()+" right context should be positive or zero.");
    this.right = right;
    size = -left + right + 1;
    center = left;
    data = new CharsAtt[size];
    for (int i = 0; i < size; i++) data[i] = new CharsAtt();
  }

  /**
   * Return a value for a position, positive or negative, relative to center
   * 
   * @param pos
   * @return the primary value
   */
  public CharsAtt get(final int pos)
  {
    return data[pointer(pos)];
  }
  /**
   * Get pointer on the data array from a position. Will roll around array if out
   * the limits
   */
  protected int pointer(final int pos)
  {
    /*
     * if (ord < -left) throw(new ArrayIndexOutOfBoundsException(
     * ord+" < "+(-left)+", left context size.\n"+this.toString() )); else if (ord >
     * right) throw(new ArrayIndexOutOfBoundsException(
     * ord+" > "+(+right)+", right context size.\n"+this.toString() ));
     */
    return (((center + pos) % size) + size) % size;
  }

  /**
   * Push a term value at front
   */
  public CharsAttWin push(final CharsAtt term)
  {
    center = pointer(+1);
    data[pointer(right)].copy(term);
    return this;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for(int i = left; i <= right; i++) {
      if (i==0) sb.append("|");
      sb.append(get(i));
      if (i==0) sb.append("| ");
      else sb.append(" ");
    }
    return sb.toString();
  }
  
}
