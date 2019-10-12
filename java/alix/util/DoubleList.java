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
package alix.util;

public class DoubleList
{
  /** Actual size */
  protected int size;
  /** Internal data */
  protected double[] data = new double[64];

  /**
   * Size of data.
   * 
   * @return
   */
  public int size()
  {
    return size;
  }

  /**
   * Get value at a position.
   * 
   * @param ord
   * @return
   */
  public double get(int pos)
  {
    // send an error if index out of bound ?
    return data[pos];
  }

  /**
   * Change value at a position
   * 
   * @param ord
   * @param value
   * @return
   */
  public DoubleList put(int pos, double value)
  {
    onWrite(pos);
    data[pos] = value;
    if (pos >= size)
      size = pos + 1;
    return this;
  }

  /**
   * Increment value at a position
   * 
   * @param ord
   * @return
   */
  public void inc(int pos)
  {
    onWrite(pos);
    data[pos]++;
  }

  /**
   * Call it before write
   * 
   * @param position
   * @return true if resized (? good ?)
   */
  protected boolean onWrite(final int pos)
  {
    if (pos < data.length)
      return false;
    final int oldLength = data.length;
    final double[] oldData = data;
    int capacity = Calcul.nextSquare(pos + 1);
    data = new double[capacity];
    System.arraycopy(oldData, 0, data, 0, oldLength);
    return true;
  }

}
