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
import java.util.HashSet;
import java.util.LinkedList;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.AttributeSource.State;

import alix.fr.Tag;
import alix.lucene.analysis.FrDics.LexEntry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;
import alix.util.Char;
import alix.util.Roll;

/**
 * Plug behind a linguistic tagger, will concat unknown names from dictionaries like 
 * Victor Hugo, V. Hugo, Jean de La Salle…
 * A dictionary of proper names allow to provide canonical versions for
 * known strings (J.-J. Rousseau => Rousseau, Jean-Jacques)
 * 
 * @author fred
 *
 */
public class FrPersnameFilter extends TokenFilter
{
  /** Particles in names  */
  public static final HashSet<CharsAtt> PARTICLES = new HashSet<CharsAtt>();
  static {
    for (String w : new String[] { "d'", "D'", "de", "De", "du", "Du", "l'", "L'", "le", "Le", "la", "La", "von", "Von" })
      PARTICLES.add(new CharsAtt(w));
  }
  /** Titles */
  public static final HashSet<CharsAtt> TITLES = new HashSet<CharsAtt>();
  static {
    for (String w : new String[] {
      "abbé", "Abbé", "Baron", "baron", "capitaine", "commodore", "comte", "Comte", "dame", "docteur", "Docteur", "frère", "frères", "juge", 
      "Lord", "lord", "Lieutenant", "lieutenant", 
      "Madame", "madame", "maitre", "maître", "Maître", "Maitre", "Major", "major", "Miss", "miss", "mistress", "Mistress", 
      "père", "padre", "Saint", "saint", "Sainte", "sainte", "sir",
      "baie", "cap", "fosse", "ile", "île", "ilot", "îlot", "mont", "plateau", "Pointe", "pointe", "villa",
    })
      TITLES.add(new CharsAtt(w));
  }
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A normalized orthographic form */
  private final CharsOrthAtt orthAtt = addAttribute(CharsOrthAtt.class);
  /** A lemma, needed to restore states */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** An efficient stack of states  */
  private Roll<State> stack = new Roll<State>(8);
  /** A term used to concat names */
  private CharsAtt name = new CharsAtt();
  /** Exit value */
  private boolean exit = false;


  public FrPersnameFilter(TokenStream input)
  {
    super(input);
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (!stack.isEmpty()) {
      restoreState(stack.remove());
      return true;
    }
    exit = input.incrementToken();
    if (!exit) return false;
    // is it start of a name?
    CharTermAttribute term = termAtt;
    FlagsAttribute flags = flagsAtt;
    final int tag = flags.getFlags();
    
    
    if (Tag.NAME.sameParent(tag) && Char.isUpperCase(term.charAt(0))); // append names, but not titles
    // else if (TITLES.contains(term)); // Saint, Maître…
    else return true;
    

    // store state like it is in case of rewind
    stack.add(captureState());
    
     // if (!orth.isEmpty()) name.copy(orth); // a previous filter may have set something good ?
    name.copy(term);

    OffsetAttribute offset = offsetAtt;
    // record offsets
    final int startOffset = offsetAtt.startOffset();
    int endOffset = offsetAtt.endOffset();
    
    // test compound names : NAME (particle|NAME)* NAME
    int lastlen = name.length();
    boolean simpleName = true;
    int loop = -1;
    while ((exit = input.incrementToken())) {
      loop++;
      // a particle, be careful to [Europe de l']atome
      if (PARTICLES.contains(term)) {
        stack.add(captureState());
        name.append(' ').append(term);
        continue;
      }
      // a candidate name, append it
      else if (Char.isUpperCase(term.charAt(0))) {
        if (name.charAt(name.length()-1) != '\'') name.append(' ');
        // a previous filter may have set an alt value
        // but be careful to Frantz Fanon
        // if (!orth.isEmpty() && Char.isUpperCase(orth.charAt(0))) name.append(orth); 
        name.append(term);
        lastlen = name.length();
        stack.clear(); // we can empty the stack here, sure there is something to resend
        endOffset = offsetAtt.endOffset(); // record endOffset for last Name
        simpleName = false; // no more simple name
        continue;
      }
      break;
    }
    // if end of stream, do not capture a bad state
    if (exit) stack.add(captureState());
    // a simple name, restore its state, let empty the stack
    if (simpleName) {
      restoreState(stack.remove());
      return true;
    }
    // at least one compound name to send
    name.setLength(lastlen);
    flagsAtt.setFlags(Tag.NAME.flag);
    term.setEmpty().append(name);
    orthAtt.setEmpty();
    lemAtt.setEmpty(); // the actual stop token may have set a lemma not relevant for names
    offsetAtt.setOffset(startOffset, endOffset);
    return true;
  }
}
