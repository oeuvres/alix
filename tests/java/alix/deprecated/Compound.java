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
package alix.deprecated;

import alix.lucene.analysis.tokenattributes.CharsAtt;

/**
 * A mutable String used to merge compounds words.
 * An index of each token is kept to easily test different sizes of the String,
 * with no more writing.
 * 
 * 
 * @author fred
 *
 */
public class Compound
{
  /** The maximum capacity in tokens */
  private final int capacity;
  /** Number of tokens */
  private int size;
  /** The chars of term */
  private CharsAtt term = new CharsAtt();
  /** The start offset of each token in the term */
  private final int[] offset;
  /** The lengths of each token in the term */
  private final int[] length;
  /** A flag for each token */
  private final int[] flag;

  /**
   * 
   * @param capacity
   */
  public Compound(final int capacity)
  {
    this.capacity = capacity;
    this.length = new int[capacity];
    this.offset = new int[capacity];
    this.flag = new int[capacity];
  }

  public void clear()
  {
    size = 0;
    term.setEmpty();
  }

  public int size()
  {
    return size;
  }

  public CharsAtt chars(final int tokens)
  {
    term.setLength(length[tokens - 1]);
    return term;
  }

  public CharsAtt chars()
  {
    if (size == 0) {
      term.setLength(0);
      return term;
    }
    term.setLength(length[size - 1]);
    return term;
  }

  /**
   * Get flag of a token
   * @param token
   * @return
   */
  public int flag(final int token)
  {
    if (token >= size) throw new ArrayIndexOutOfBoundsException("" + token + " >= size=" + size);
    return flag[token];
  }

  /**
   * Set the number of tokens
   */
  public void add(CharSequence token)
  {
    add(token, 0);
  }
  /**
   * Set the number of tokens
   */
  public void add(CharSequence token, final int tag)
  {
    final int cap = capacity;
    int len = token.length();
    // do nothing for empty term
    if (len == 0) return;
    if (size == 0) {
      offset[0] = 0;
      length[0] = len;
      flag[0] = tag;
      term.append(token);
      size++;
      return;
    }
    // shift
    if (size == cap) {
      int from = offset[1];
      // System.arrayCopy do not seems to create crossing problems
      term.copyBuffer(term.buffer(), from, length[cap - 1] - from);
      for (int i = 1; i < cap; i++) {
        offset[i - 1] = offset[i] - from;
        length[i - 1] = length[i] - from;
        flag[i - 1] = flag[i];
      }
      size--;
    }
    // restore length after explorations
    term.setLength(length[size - 1]);
    offset[size] = length[size - 1];
    char lastchar = term.charAt(length[size - 1] - 1);
    if (lastchar != '\'') {
      term.append(' ');
      offset[size]++;
    }
    length[size] = offset[size] + len;
    flag[size] = tag;
    term.append(token);
    size++;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("size=").append(size).append(' ');
    for (int i = 0; i < size; i++) {
      // sb.append('|').append(offset[i]).append(", ").append(length[i]);
      sb.append('|').append(term.subSequence(offset[i], length[i]));
    }
    sb.append('|');
    sb.append(" -").append(term).append("- ").append(term.length());
    return sb.toString();
  }

}
