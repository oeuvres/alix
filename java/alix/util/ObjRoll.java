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
package alix.util;

import java.util.Arrays;

/**
 * A centered sliding window of ints.
 */
public class ObjRoll<T> extends Roll
{
  /** Data of the sliding window */
  private Object[] data;
  /** Push counter, may help to know if slider is full */
  private int pushCounter;

  /**
   * Constructor, init data
   */
  public ObjRoll(final int left, final int right) {
    super(left, right);
    data = new Object[size];
  }

  /**
   * Return a value for a position, positive or negative, relative to center
   * 
   * @param pos
   * @return the primary value
   */
  @SuppressWarnings("unchecked")
  public T get(final int pos)
  {
    return (T)data[pointer(pos)];
  }

  /**
   * Return first value
   * 
   * @return
   */
  @SuppressWarnings("unchecked")
  public T first()
  {
    return (T)data[pointer(right)];
  }

  /**
   * Return last value
   * 
   * @return
   */
  @SuppressWarnings("unchecked")
  public T last()
  {
    return (T)data[pointer(left)];
  }

  /**
   * Set value at position
   * 
   * @param pos
   * @param value
   * @return the primary value
   */
  public ObjRoll<T> set(final int pos, final T value)
  {
    int index = pointer(pos);
    // int old = data[ index ];
    data[index] = value;
    return this;
  }

  /**
   * Add a value at front
   */
  public ObjRoll<T> push(final T value)
  {
    // int ret = data[ pointer( -left ) ];
    center = pointer(+1);
    data[pointer(right)] = value;
    pushCounter++;
    return this;
  }

   /**
   * Fill with a value
   * 
   * @return
   */
  public ObjRoll<T> fill(final T value)
  {
    Arrays.fill(data, value);
    return this;
  }

  /**
   * Return the count of pushs
   * 
   * @return
   */
  public int pushCount()
  {
    return pushCounter;
  }


  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for (int i = left; i <= right; i++) {
      if (i == 0)
        sb.append(" <");
      sb.append(get(i));
      if (i == 0)
        sb.append("> ");
      else if (i == right)
        ;
      else if (i == -1)
        ;
      else
        sb.append(" ");
    }
    return sb.toString();
  }


}
