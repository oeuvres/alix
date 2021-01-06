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
import alix.lucene.analysis.FrDics.LexEntry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;
import alix.util.Char;
import alix.util.Roll;

/**
 * Plug behind TokenLem
 * @author fred
 *
 */
public class LocutionFilter extends TokenFilter
{
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current original term, do not cast here, or effects could be inpredictable */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A normalized orthographic form (ex : capitalization) */
  private final CharsOrthAtt orthAtt = addAttribute(CharsOrthAtt.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** A stack of states  */
  private Roll<State> stack = new Roll<State>(10);
  /** A term used to concat a compound */
  private CharsAtt compound = new CharsAtt();
  /** Exit value */
  private boolean exit = true;
  /** Counter */
  private int count;

  public LocutionFilter(TokenStream input)
  {
    super(input);
  }

  public String toString(LinkedList<State> stack) {
    String out = "";
    State restore = captureState();
    boolean first = true;
    for(State s: stack) {
      if (first) first = false;
      else out += ", ";
      restoreState(s);
      out += termAtt;
    }
    restoreState(restore);
    return out;
  }
  
  @Override
  public boolean incrementToken() throws IOException
  {
    CharsAtt orth = (CharsAtt) orthAtt;
    boolean token = false;
    compound.setEmpty();
    Integer treeState;
    final int BRANCH = FrDics.BRANCH; // localize
    final int LEAF = FrDics.LEAF; // localize
    int loop = -1;
    int startOffset = offsetAtt.startOffset();
    boolean maybeVerb = false;
    int tag = flagsAtt.getFlags();
    do {
      loop++;
      // something in stack, loop in it
      if (stack.size() > loop) {
        restoreState(stack.get(loop));
      }
      else {
        exit = input.incrementToken();
        if (Tag.isPun(tag) || termAtt.length() == 0) {
          // if nothing in stack, and new token, go out with current state
          if (stack.isEmpty() && loop == 0) return exit;
          // if stack is not empty and a new token, add it to the stack
          if (token) stack.add(captureState());
          restoreState(stack.remove());
          if (stack.isEmpty()) return exit;
          else return true;
        }
        token = true; // avoid too much stack copy for simple words

      }
      if (loop == 0) startOffset = offsetAtt.startOffset();
      
      /*
      // punctuation do not start a compound, possible optimization
      boolean tagBreak = Tag.isPun(tag);
      if (tagBreak) return exit;
      */
      // build a compound candidate
      if (loop > 0 && !compound.endsWith('\'')) compound.append(' '); 
      // for verbs, the compound key is the lemma, for others takes an orthographic form
      if (Tag.isVerb(tag) && lemAtt.length() != 0) {
        maybeVerb = true;
        compound.append(lemAtt);
      }
      // ne fait pas l’affaire
      else if (maybeVerb && orth.equals("pas")) {
        compound.setLength(compound.length() - 1); // suppres last ' '
      }
      // Fanon (orth is here bad)
      else if (Char.isUpperCase(termAtt.charAt(0))) compound.append(termAtt);
      else if (orth.length() != 0) compound.append(orth);
      else compound.append(termAtt);
      
      treeState = FrDics.TREELOC.get(compound);
      if (treeState == null) {
        // if nothing in stack, and new token, go out with current state
        if (stack.isEmpty() && loop == 0) return exit;
        // if stack is not empty and a new token, add it to the stack
        if (token) stack.add(captureState());
        restoreState(stack.remove());
        if (stack.isEmpty()) return exit;
        else return true;
      }
      
      
      // it’s a compound
      if ((treeState & LEAF) > 0) {
        stack.clear();
        // get its entry 
        LexEntry entry = FrDics.WORDS.get(compound);
        if (entry == null) entry = FrDics.NAMES.get(compound);
        if (entry != null) {
          flagsAtt.setFlags(entry.tag);
          termAtt.setEmpty().append(compound);
          if (entry.orth != null) orth.setEmpty().append(entry.orth);
          else orth.setEmpty();
          if (entry.lem != null) lemAtt.setEmpty().append(entry.lem);
          else lemAtt.setEmpty();
        }
        else {
          termAtt.setEmpty().append(compound);
          orth.setEmpty().append(compound);
          lemAtt.setEmpty();
        }
        
        offsetAtt.setOffset(startOffset, offsetAtt.endOffset());
        // no more compound with this prefix, we are happy
        if ((treeState & BRANCH) == 0) return exit;
        // compound may continue, lookahead should continue, store this step
        stack.add(captureState());
      }
      // should be a part of a compound, store state if it’s a new token
      else {
        if (token) stack.add(captureState());
      }

    } while(loop < 10); // a compound bigger than 10, there’s a problem, should not arrive
    if (!stack.isEmpty()) {
      restoreState(stack.remove());
      if (stack.isEmpty()) return exit;
      else return true;
    }
    return exit; // ?? pb à la fin
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
