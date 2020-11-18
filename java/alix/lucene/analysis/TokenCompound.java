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
package alix.lucene.analysis;

import java.io.IOException;
import java.util.LinkedList;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

/**
 * Plug behind TokenLem
 * @author fred
 *
 */
public class TokenCompound extends TokenFilter
{
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current original term */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A normalized orthographic form (ex : capitalization) */
  private final CharsOrthAtt orthAtt = addAttribute(CharsOrthAtt.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** A stack of sates  */
  private LinkedList<State> stack = new LinkedList<State>();
  /** A term used to concat a compound */
  private CharsAtt comlem = new CharsAtt();
  /** A term used to concat a compound */
  private CharsAtt comorth = new CharsAtt();

  public TokenCompound(TokenStream input)
  {
    super(input);
  }

  @SuppressWarnings("unlikely-arg-type")
  @Override
  public boolean incrementToken() throws IOException
  {
    // send back forward lookup
    if (!stack.isEmpty()) {
      restoreState(stack.removeLast());
      return true;
    }
    boolean ret = input.incrementToken();
    // punctuation do not start a ompound
    int tag = flagsAtt.getFlags();
    boolean tagBreak = Tag.isPun(tag);
    if (tagBreak) return ret; // leave fast
    // may first token start a compound ?
    Integer trieO = null;
    if (lemAtt.length() != 0) trieO = FrDics.COMPOUND.get(lemAtt);
    else if (orthAtt.length() != 0) trieO = FrDics.COMPOUND.get(orthAtt);
    else trieO = FrDics.COMPOUND.get(termAtt);
    // no the start of compound, bye
    if(trieO == null) return ret;
    
    // go ahead to search for a compound
    comlem.setEmpty();
    comorth.setEmpty();
    if (lemAtt.length() != 0) comlem.append(lemAtt);
    else if (orthAtt.length() != 0) comlem.append(orthAtt);
    else comlem.append(termAtt);
    if (orthAtt.length() != 0) comorth.append(orthAtt);
    else comorth.append(termAtt);
    final int startOffset = offsetAtt.startOffset();
    stack.addFirst(captureState()); // keep this token
    
    while (true) {
      // append space if last is not apos
      if (comlem.lastChar() != '\'') {
        comlem.append(' ');
        comorth.append(' ');
      }
      // get next token, and keep end event
      ret = input.incrementToken();
      tag = flagsAtt.getFlags();
      // end of compound by tag
      tagBreak = Tag.isPun(tag);
      // token is not a tag breaker
      if (!tagBreak) {
        if (lemAtt.length() != 0) comlem.append(lemAtt);
        else if(orthAtt.length() != 0) comlem.append(orthAtt);
        else comlem.append(termAtt);
        if(orthAtt.length() != 0) comorth.append(orthAtt);
        else comorth.append(termAtt);
        System.out.println(comlem);
        // is this chain known from compound dictionary ?
        trieO = FrDics.COMPOUND.get(comlem);
        /*
        System.out.println(comlem + "-"+trieO+" "
          +FrDics.COMPOUND.get(new CharsAtt(comlem)));
        */
      }
      // end of a look ahead
      if (trieO == null) {
        // store present state with no change
        stack.addFirst(captureState());
        // restore the first recorded state, and go away
        restoreState(stack.removeLast());
        return true; // let continue to empty the stack
      }

      int trieflags = trieO;
      // it’s a compound
      if ((trieflags & FrDics.LEAF) > 0) {
        stack.clear();
        termAtt.setEmpty().append(comorth);
        orthAtt.setEmpty().append(comorth);
        lemAtt.setEmpty().append(comlem);
        flagsAtt.setFlags(trieflags & 0xFF); // set tag (without the trie flags)
        offsetAtt.setOffset(startOffset, offsetAtt.endOffset());
        // no more compound with this prefix, we are happy
        if ((trieflags & FrDics.BRANCH) == 0) return true;
        // compound may continue, lookahead should continue, store this step
        stack.addFirst(captureState());
      }
      // should be a part of a compound, store state if it’s a no way
      else {
        stack.addFirst(captureState());
      }
    }
  }
  @Override
  public void reset() throws IOException {
    super.reset();
  }

  @Override
  public void end() throws IOException {
    super.end();
  }
}
