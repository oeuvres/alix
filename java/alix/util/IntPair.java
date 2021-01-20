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

/**
 * A mutable pair of ints. Works well as a key for a HashMap (hascode()
 * implemented), comparable is good for perfs in buckets. After test,
 * it is more efficient than concating ints in longs in an HashMap.
 * 
 *
 * @author glorieux-f
 */
public class IntPair implements Comparable<IntPair>
{
  /** Internal data */
  protected int x;
  /** Internal data */
  protected int y;
  /** Precalculate hash */
  private int hash;

  public IntPair()
  {
  }

  public IntPair(IntPair pair)
  {
    this.x = pair.x;
    this.y = pair.y;
  }

  public IntPair(final int x, int y)
  {
    this.x = x;
    this.y = y;
  }

  public void set(final int x, final int y)
  {
    this.x = x;
    this.y = y;
    hash = 0;
  }

  public void set(IntPair pair)
  {
    this.x = pair.x;
    this.y = pair.y;
    hash = 0;
  }

  public int[] toArray()
  {
    return new int[] { x, y };
  }

  public int x()
  {
    return x;
  }

  public int y()
  {
    return y;
  }

  public void x(final int x)
  {
    this.x = x;
    hash = 0;
  }

  public void y(final int y)
  {
    this.y = y;
    hash = 0;
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null) return false;
    if (o == this) return true;
    if (o instanceof IntPair) {
      IntPair pair = (IntPair) o;
      return (x == pair.x && y == pair.y);
    }
    if (o instanceof IntSeries) {
      IntSeries series = (IntSeries) o;
      if (series.length() != 2) return false;
      if (x != series.data[0]) return false;
      if (y != series.data[1]) return false;
      return true;
    }
    if (o instanceof IntRoll) {
      IntRoll roll = (IntRoll) o;
      if (roll.size != 2) return false;
      if (x != roll.get(0)) return false;
      if (y != roll.get(1)) return false;
      return true;
    }
    return false;
  }

  @Override
  public int hashCode()
  {
    if (hash != 0) return hash;
    // hash = ( y << 16 ) ^ x; // 0% collision, but less dispersion
    hash = ((x + y) * (x + y + 1) / 2) + y;
    // hash = (31 * 17 + x) * 31 + y; // 97% collision
    return hash;
  }

  @Override
  public int compareTo(IntPair o)
  {
    int val = x;
    int oval = o.x;
    if (val < oval) return -1;
    else if (val > oval) return 1;
    val = y;
    oval = o.y;
    return (val < oval ? -1 : (val == oval ? 0 : 1));
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append("(").append(x).append(", ").append(y).append(")");
    return sb.toString();
  }

}
