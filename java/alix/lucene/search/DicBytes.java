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
package alix.lucene.search;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.lucene.analysis.tokenattributes.CharsAtt;

/**
 * A dictionary of terms with frequencies, for lucene.
 * 
 * @author fred
 *
 */
public class DicBytes implements Iterable<Integer>
{
  /** Name of dictionary */
  public final String name;
  /** Number of doc */
  public int docs;
  /** Number of occurrences */
  public long occs;
  /** Store and populate the terms */
  private final BytesRefHash hash = new BytesRefHash();
  /** Frequencies in the hash id order */
  private long[] counts = new long[32];
  /** Array of terms sorted by count */
  private Entry[] sorted;
  /** Cache size after sorting */
  private int size;

  public DicBytes(final String name)
  {
    this.name = name;
  }

  /**
   * Populate the list of terms, and add the value.
   * 
   * @param bytes
   * @param more
   */
  public void add(BytesRef bytes, long more)
  {
    sorted = null;
    int id = hash.add(bytes);
    // value already given
    if (id < 0) id = -id - 1;
    counts = ArrayUtil.grow(counts, id + 1);
    counts[id] += more;
  }

  /**
   * Get count for a term.
   * 
   * @param bytes
   */
  public long count(final BytesRef bytes)
  {
    final int id = hash.find(bytes);
    if (id < 0) return -1;
    return counts[id];
  }

  /**
   * Get count by String
   * 
   * @param s
   */
  public long count(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = hash.find(bytes);
    return counts[id];
  }

  /**
   * Number of terms in the list.
   * 
   * @return
   */
  public int size()
  {
    return hash.size();
  }

  /**
   * Sort the entries in count order, to use beforervoid
   */
  public void sort()
  {
    final int size = hash.size();
    this.size = size;
    sorted = new Entry[size];
    for (int id = 0; id < size; id++) {
      sorted[id] = new Entry(id, counts[id]);
    }
    Arrays.sort(sorted);
  }

  /**
   * Entry used for sorting by count.
   */
  private class Entry implements Comparable<Entry>
  {
    private final int id;
    private final long count;
    private final BytesRef term = new BytesRef();

    public Entry(final int id, final long count)
    {
      this.id = id;
      this.count = count;
      hash.get(id, term);
    }

    @Override
    public int compareTo(Entry o)
    {
      final long x = count;
      final long y = o.count;
      if (x > y) return -1;
      else if (x < y) return 1;
      return term.compareTo(o.term);
    }
  }

  @Override
  public Cursor iterator()
  {
    if (sorted == null) sort();
    return new Cursor();
  }

  /**
   * A private cursor in the list of terms, sorted by count.
   */
  public class Cursor implements Iterator<Integer>
  {
    private int cursor = -1;
    /** Reusable bytes ref */
    BytesRef bytes = new BytesRef();

    /**
     * Forward cursor
     * 
     * @return
     */
    @Override
    public Integer next()
    {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      cursor++;
      return cursor;
    }

    @Override
    public void remove()
    {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasNext()
    {
      return this.cursor < size - 1;
    }

    /**
     * Get current term as a string
     * 
     * @return
     */
    public String term()
    {
      final int id = sorted[cursor].id;
      return hash.get(id, bytes).utf8ToString();
    }

    /**
     * Get current term with reusable bytes
     * 
     * @param bytes
     * @return
     */
    public BytesRef term(BytesRef bytes)
    {
      final int id = sorted[cursor].id;
      return hash.get(id, bytes);
    }

    public CharsAtt term(CharsAtt term)
    {
      final int id = sorted[cursor].id;
      hash.get(id, bytes);

      // ensure size of the char array
      int length = bytes.length;
      char[] chars = term.resizeBuffer(length);
      final int len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, length, chars);
      term.setLength(len);
      return term;
    }

    /**
     * Get current count
     * 
     * @return
     */
    public long count()
    {
      return sorted[cursor].count;
    }

    /**
     * Reset the cursor.
     */
    public void reset()
    {
      cursor = -1;
    }

  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    int max = 100;
    Cursor cursor = this.iterator();
    string.append(name).append(", docs=").append(docs).append(" occs=").append(occs).append("\n");
    while (cursor.hasNext()) {
      cursor.next();
      string.append(cursor.term()).append(": ").append(cursor.count()).append("\n");
      if (max-- == 0) {
        string.append("...\n");
        break;
      }
    }
    return string.toString();
  }

}
