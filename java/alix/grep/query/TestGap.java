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
package alix.grep.query;

import alix.util.Occ;

/**
 * Zero or n words, except sentence punctuation
 * 
 * @author user
 *
 */
public class TestGap extends Test
{
  /** Default size of gap */
  public final int DEFAULT = 5;
  /** Initial size of gap, maybe used for a reset */
  public final int initial;
  /** Max size of gap */
  private int gap;

  /** Default constructor, size of gap is DEFAULT */
  public TestGap() {
    this.initial = this.gap = DEFAULT;
  }

  /**
   * Constructor with parameter
   * 
   * @param gap
   */
  public TestGap(int gap) {
    this.initial = this.gap = gap;
  }

  /** @return the current gap size */
  public int gap()
  {
    return gap;
  }

  /**
   * @param gap,
   *          set gap to a new value, may be used in a kind of query
   */
  public void gap(int gap)
  {
    this.gap = gap;
  }

  /**
   * Decrement of gap is controled by user
   * 
   * @return
   */
  public int dec()
  {
    return --gap;
  }

  @Override
  public boolean test(Occ occ)
  {
    return (gap > 0);
  }

  @Override
  public String label()
  {
    return "**";
  }

}
