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

import java.util.ArrayList;

import alix.util.Occ;

/**
 * 
 * Do not works well for now, no backtracking of things like that (A B, A C)
 * will not find "A C" TODO: a query compilator, transforming (A B, D B, A C, D
 * C) => (A (B, C), D (B, C))
 * 
 * @author glorieux-f
 *
 */
public class TestOr extends Test
{
  /**  */
  ArrayList<Test> list = new ArrayList<Test>();

  public TestOr() {
  }

  public void add(Test test)
  {
    list.add(test);
  }

  @Override
  public boolean test(Occ occ)
  {
    for (Test test : list) {
      // if one test is OK
      if (test.test(occ))
        return true;
    }
    return false;
  }

  @Override
  public String label()
  {
    StringBuffer sb = new StringBuffer();
    boolean first = true;
    sb.append("( ");
    for (Test test : list) {
      if (first)
        first = false;
      else
        sb.append(", ");
      sb.append(test.toString());
    }
    sb.append(" )");
    return sb.toString();
  }

}
