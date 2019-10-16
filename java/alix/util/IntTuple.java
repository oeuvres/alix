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
package alix.util;
/**
 * A non mutable list of ints, designed to be a good key in an Hashmap.
 * @author glorieux-f
 *
 */
public class IntTuple implements Comparable<IntTuple>
{
  /** Internal data */
  protected final int[] data;
  /** Current size */
  protected final int size;
  /** Cache an hash code */
  protected int hash;
  /**
   * Constructor with an estimated size.
   * @param size
   */
  public IntTuple(final int size)
  {
    this.size = size;
    data = new int[size];
  }
  /**
   * Build a key from an int roller.
   * @param roller
   */
  public IntTuple(final IntRoller roller)
  {
    throw new UnsupportedOperationException("TODO implements");
  }
  @Override
  public int hashCode()
  {
    if (hash != 0) return hash;
    int res = 17;
    for (int i = 0; i < size; i++) {
      res = 31 * res + data[i];
    }
    return res;
  }
  @Override
  public boolean equals(final Object o)
  {
    if (o == null) return false;
    if (o == this) return true;
    if (o instanceof IntList) {
      IntList list = (IntList) o;
      if (list.size != size) return false;
      for (short i = 0; i < size; i++) {
        if (list.data[i] != data[i]) return false;
      }
      return true;
    }
    return false;
  }

  @Override
  public int compareTo(IntTuple o)
  {
    if (size != o.size) return Integer.compare(size, o.size);
    int lim = size; // avoid a content lookup
    for (int i = 0; i < lim; i++) {
      if (data[i] != o.data[i]) return Integer.compare(data[i], o.data[i]);
    }
    return 0;
  }

  
}
