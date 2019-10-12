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

/**
 * A mutable list of ints with useful metadata, for example to calculate
 * average. This object is not protected, for fast acess to fields, be careful
 * to enjoy speed. Not suitable as a key for a hash (mutable).
 *
 * @author glorieux-f
 */
public class IntSeries extends IntList
{
  /** Row a count event */
  public int count;
  /** Maybe used to keep some event memory */
  public int last = -1;
  /** A code, maybe used for a collection of stack */
  final public int code;
  /** Row a name, useful for collection */
  final public String label;
  /** A class */
  final public int cat;
  /** Min value */
  public int min;
  /** Max value */
  public int max;
  /** Median value */
  public int[] decile = new int[11];
  /** Full sum, for average */
  public long sum;
  /** Average */
  public double avg;
  /** standard deviation */
  public double devstd;

  /**
   * Constructor with no metadata.
   */
  public IntSeries() {
    super();
    label = null;
    code = -1;
    cat = -1;
  }

  /**
   * Constructore with label.
   * 
   * @param label
   */
  public IntSeries(final String label) {
    super();
    this.label = label;
    code = -1;
    cat = -1;
  }

  /**
   * Constructor with a code.
   * 
   * @param code
   */
  public IntSeries(final int code) {
    this.label = null;
    this.code = code;
    cat = -1;
  }

  /**
   * Constructor with a code and a category.
   * 
   * @param code
   * @param cat
   */
  public IntSeries(final int code, final int cat) {
    this.label = null;
    this.code = code;
    this.cat = cat;
  }

  /**
   * Constructor with a label and a category.
   * 
   * @param label
   * @param cat
   */
  public IntSeries(final String label, final int cat) {
    this.label = label;
    this.code = -1;
    this.cat = cat;
  }

  /**
   * Constructor with a label, a code, and a category.
   * 
   * @param label
   * @param code
   * @param cat
   */
  public IntSeries(final String label, final int code, final int cat) {
    this.label = label;
    this.code = code;
    this.cat = cat;
  }

  /**
   * Cache statistic values.
   */
  public void cache()
  {
    int size = this.size;
    if (size == 0) {
      min = 0;
      max = 0;
      sum = 0;
      avg = 0;
      devstd = 0;
      return;
    }
    int min = data[0];
    int max = data[0];
    long sum = data[0];
    int val;
    if (size > 1) {
      for (int i = 1; i < size; i++) {
        val = data[i];
        min = Math.min(min, val);
        max = Math.max(max, val);
        sum += val;
      }
    }
    this.min = min;
    this.max = max;
    this.sum = sum;
    double avg = (double) sum / (double) size;
    this.avg = avg;
    double dev = 0;
    for (int i = 0; i < size; i++) {
      long val2 = data[i];
      dev += (avg - val2) * (avg - val2);
    }
    dev = Math.sqrt(dev / size);
    this.devstd = dev;
    // median
    int[] dest = toArray();

    double part = dest.length / 10.0;
    for (int i = 0; i < 10; i++) {
      double point = i * part;
      decile[i] = dest[(int) point];
      // else decil[i] = (dest[(int)floor] + dest[(int)floor-1])/2; // why average ?
    }
    decile[10] = dest[size - 1];
  }

  /**
   * Get a decile
   * 
   * @param n
   * @return
   */
  public int decile(int n)
  {
    return decile[n];
  }

}
