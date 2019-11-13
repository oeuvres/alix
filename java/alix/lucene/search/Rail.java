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
package alix.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

/**
 * A flatten version of a term vector for a document field.
 */
public class Rail
{
  /** The list of tokens in prder of the document */
  final Token[] toks;
  /** Max count for the most frequent token */
  final int countMax;
  
  
  /**
   * Flatten a term vector as a list of tokens in document order.
   * @param tvek
   * @param field Keep trace of data origin.
   * @param docId Keep trace of data origin.
   * @throws NoSuchFieldException
   * @throws IOException
   */
  public Rail(Terms tvek, ByteRunAutomaton include, ByteRunAutomaton exclude) throws NoSuchFieldException, IOException
  {
    if (!tvek.hasFreqs() || !tvek.hasPositions() || !tvek.hasOffsets()) {
      throw new NoSuchFieldException("Missig offsets in terms Vector; see FieldType.setStoreTermVectorOffsets(true)");
    }
    int max = 0; // get max token count
    TermsEnum termit = tvek.iterator();
    ArrayList<Token> offsets = new ArrayList<Token>();
    PostingsEnum postings = null;
    while (termit.next() != null) {
      BytesRef ref = termit.term();
      if (exclude != null && exclude.run(ref.bytes, ref.offset, ref.length)) continue; 
      if (include != null && !include.run(ref.bytes, ref.offset, ref.length)) continue; 
      String form = termit.term().utf8ToString();
      postings = termit.postings(postings, PostingsEnum.OFFSETS);
      while(postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
        int pos = -1;
        int freq = postings.freq();
        if (freq > max) max = freq;
        for (int i = 0; i < freq; i++) {
          pos = postings.nextPosition();
          offsets.add(new Token(pos, postings.startOffset(), postings.endOffset(), form, freq));
        }
      }
    }
    Collections.sort(offsets); // sort offsets before hilite
    toks = offsets.toArray(new Token[0]);
    this.countMax = max;
  }
  
  /**
   * A record to sort term vectors occurrences
   */
  static class Token implements Comparable<Token>
  {
    final int pos;
    final int start;
    final int end;
    final String form;
    final int count;
    final int count2;
    public Token(final int pos, final int start, final int end)
    {
      this(pos, start, end, null, 0, 0);
    }
    public Token(final int pos, final int start, final int end, final String form)
    {
      this(pos, start, end, form, 0, 0);
    }
    public Token(final int pos, final int start, final int end, final String form, final int count)
    {
      this(pos, start, end, form, count, 0);
    }
    public Token(final int pos, final int start, final int end, final String form, final int count, final int count2)
    {
      this.pos = pos;
      this.start = start;
      this.end = end;
      this.form = form;
      this.count = count;
      this.count2 = count2;
    }
    @Override
    public int compareTo(Token tok2)
    {
      return Integer.compare(this.start, tok2.start);
    }
  }

}
