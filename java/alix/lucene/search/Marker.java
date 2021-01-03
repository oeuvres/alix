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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.lucene.util.automaton.Operations;

/**
 * A light hiliter using a Lucene analyzer and a compiled automaton,
 * designed for short texts (ex: show found words when searching in titles).
 * Supports wildcard search.
 */
public class Marker
{
  /** The search String */
  final String q;
  /** The analyzer used for search string and text to parse */
  final Analyzer analyzer;
  /** Caching token stream ? */
  /** Automaton */
  final Automaton automaton;
  /** */
  final CharacterRunAutomaton tester;
  
  public Marker(Analyzer analyzer, String q) throws IOException
  {
    this.q = q;
    this.analyzer = analyzer;
    // loop on words according the Analyzer to build automatoc
    TokenStream stream = analyzer.tokenStream("hilite", q);
    CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
    ArrayList<Automaton> cogs = new ArrayList<Automaton>();
    stream.reset();
    while (stream.incrementToken()) {
      if (termAtt.length() < 1) continue;
      cogs.add(automaton(termAtt.buffer(), 0, termAtt.length()));
    }
    stream.end();
    stream.close();
    automaton = Operations.union(cogs);
    tester = new CharacterRunAutomaton(automaton);
  }
  
  /**
   * Build a char automaton (not for smileys and other 3 bytes)
   * @param chars
   * @param offset
   * @param length
   * @return
   */
  public static Automaton automaton(char[] chars, int offset, int length) {
    ArrayList<Automaton> cogs = new ArrayList<Automaton>();
    for (int i = offset; i < length; i++) {
      char c = chars[i];
      switch(c) {
        case '*': 
          cogs.add(Automata.makeAnyString());
          break;
        case '?':
          cogs.add(Automata.makeAnyChar());
          break;
        case '\\': // escape ?
          // last char, nothing to escape
          if (i == (length + 1)) break;
          i++;
          c = chars[i];
        default:
          cogs.add(Automata.makeChar(c));
      }
    }
    return Operations.concatenate(cogs);
  }

  
  public String mark(String text) throws IOException
  {
    // reusable string reader ?
    StringBuilder sb = new StringBuilder();
    TokenStream stream = analyzer.tokenStream("hilite", text);
    // get the CharTermAttribute from the TokenStream
    CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
    OffsetAttribute offsetAtt = stream.addAttribute(OffsetAttribute.class);
    int off = 0;
    stream.reset();
    // print all tokens until stream is exhausted
    while (stream.incrementToken()) {
      if(!tester.run(termAtt.buffer(), 0, termAtt.length())) continue;
      // should be a desired tem
      final int start = offsetAtt.startOffset();
      final int end = offsetAtt.endOffset();
      sb.append(text.substring(off, start));
      sb.append("<mark>");
      sb.append(text.substring(start, end));
      sb.append("</mark>");
      off = end;
    }
    sb.append(text.substring(off));
    stream.end();
    stream.close();
    return sb.toString();
  }

  


}
