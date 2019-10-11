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

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.util.Occ;

public abstract class Test
{
  /** Next test */
  private Test next = null;
  /** Prev test */
  private Test prev = null;

  /** Factory, build a test with a string */
  public static Test create(String term)
  {
    if (term.equals("*")) {
      return new TestTrue();
    }
    if (term.equals("**")) {
      return new TestGap();
    }
    if (term.equals("##")) {
      return new TestStop();
    }
    boolean quotes = false;
    // quotes, maybe an orth or an exact tag
    if (term.charAt(0) == '"') {
      quotes = true;
      int endIndex = term.length();
      if (term.charAt(endIndex - 1) == '"')
        endIndex--;
      term = term.substring(1, endIndex);
    }
    // a known tag ?
    int tag;
    if ((tag = Tag.code(term)) != Tag.UNKNOWN) {
      if (quotes)
        return new TestTag(tag);
      if (Tag.group(tag) == tag)
        return new TestTagPrefix(tag);
      return new TestTag(tag);
    }
    // a known lemma ?
    else if (!quotes && term.equals(Lexik.lem(term))) {
      return new TestLem(term);
    }
    // default
    else {
      return new TestOrth(term);
    }
  }

  /** Set a next Test after */
  public Test next(Test test)
  {
    this.next = test;
    return this;
  }

  /** get next Test */
  public Test next()
  {
    return next;
  }

  /** Set a prev Test */
  public Test prev(Test test)
  {
    this.prev = test;
    return this;
  }

  /** get next Test */
  public Test prev()
  {
    return prev;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(label());
    if (next != null) {
      sb.append(" ");
      sb.append(next);
    }
    return sb.toString();
  }

  /** A string view for the Test */
  abstract public String label();

  /**
   * @param occ
   *          test present occurrence
   * @return null if test failed, the next Test if in a chain, a TestEnd
   */
  abstract public boolean test(Occ occ);

  /**
   * No reason to use in cli, for testing only
   */
  public static void main(String[] args)
  {
    System.out.println(Test.create("NAME"));
  }

}
