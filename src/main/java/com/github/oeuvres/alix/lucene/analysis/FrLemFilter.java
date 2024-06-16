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
package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.FrDics.LexEntry;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsLemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsOrthAtt;
import com.github.oeuvres.alix.maths.Calcul;
import com.github.oeuvres.alix.util.Char;

/**
 * A lucene token filter adding other channels to the token stream
 * <ul>
 *   <li>an orthographic form (normalized) in a {@link CharsOrthAtt}</li>
 *   <li>a lemma in a {@link CharsLemAtt}</li>
 *   <li>a pos as a lucene int flag {@link FlagsAttribute} (according to the semantic of {@link Tag}</li>
 * </ul>
 * <p>
 * The efficiency of the dictionaries lookup and chars manipulation
 * rely on a custom implementation of a lucene term attribute {@link CharsOrthAtt}.
 * </p>
 * <p>
 * The original {@link CharTermAttribute} provide by the step before is not
 * modified, allowing further filters to choose which token to index,
 * see for example {@link CloudFilter}. 
 * </p>
 * <p>
 * The found lemma+pos is dictionary based. No disambiguation is tried,
 * so that errors are completely deterministic. The dictionary provide
 * the most frequent lemma+pos for a graphic form. This approach has
 * proved its robustness, especially with infrequent texts from past
 * centuries, on which training is not profitable.
 * </p>
 * <p>
 * Logic could be extended to other languages with same linguistic resources,
 * there are here rules on capitals to infer proper names, specific to English or French
 * (not compatible with German for example).
 * </p>
 */
public final class FrLemFilter extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharsAtt termAtt = (CharsAtt)addAttribute(CharTermAttribute.class);
  /** A linguistic category as an int, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsAtt lemAtt = (CharsAtt)addAttribute(CharsLemAtt.class);
  /** A normalized orthographic form, so that original term is not modified at this step */
  private final CharsAtt orthAtt = (CharsAtt)addAttribute(CharsOrthAtt.class);
  /** Last token was Punctuation */
  private boolean waspun = true; // first word considered as if it follows a dot
  /** Store state */
  private State save;
  /** Reusable char sequence for some tests and transformations */
  private final CharsAtt copy = new CharsAtt();


  
  /**
   * Default constructor
   */
  public FrLemFilter(TokenStream input)
  {
    super(input);
  }

  @Override
  public final boolean incrementToken() throws IOException
  {
    // was last token a sentence punctuation ?
    boolean waspun = this.waspun;
    this.waspun = false;
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }
    // end of stream
    if (!input.incrementToken()) return false;
    orthAtt.copy(termAtt); // start with original term
    int flags = flagsAtt.getFlags();
    // pass through zero-length search
    if (orthAtt.length() == 0) return true;
    if (flags == Tag.PUNdiv.flag || flags == Tag.PUNsent.flag) {
      this.waspun = true;
      return true;
    }
    // Get first char
    char c1 = orthAtt.charAt(0);
    // Not a word
    if (!Char.isToken(c1)) return true;
    
    
    LexEntry entry;
    // First letter of token is upper case, is it a name ? Is it an upper case header ?
    // Do no touch to abbreviations
    if (Char.isUpperCase(c1)) {
      
      // roman number already detected
      if (flagsAtt.getFlags() == Tag.NUM.flag) return true;
      int len = orthAtt.length();
      if (orthAtt.lastChar() == '.') len--;
      int n = Calcul.roman2int(orthAtt.buffer(), 0, len);
      if (n > 0) {
        flagsAtt.setFlags(Tag.NUM.flag);
        lemAtt.append(""+n);
        return true;
      }
      // Do not touch to recognized ABBR, like O.N.
      // if (flagsAtt.getFlags() != Tag.ABBR) orth.capitalize(); // GRANDE-BRETAGNE -> Grande-Bretagne
      FrDics.norm(orthAtt); // normalise : Etat -> État
      copy.copy(orthAtt);
      // c1 = orth.charAt(0); // keep initial cap, maybe useful
      entry = FrDics.name(orthAtt); // known name ?
      if (entry != null) {
        flagsAtt.setFlags(entry.tag);
        // maybe a normalized form for the name
        if (entry.orth != null) orthAtt.copy(entry.orth);
        if (entry.lem != null) lemAtt.copy(entry.lem);
        return true;
      }
      // Charles-François-Bienvenu, Va-t’en, Allez-vous
      int pos = orthAtt.indexOf('-');
      if (pos > 0) {
        int length = orthAtt.length();
        orthAtt.setLength(pos);
        entry = FrDics.name(orthAtt);
        orthAtt.setLength(length);
        if (entry != null) {
          flagsAtt.setFlags(entry.tag);
          return true;
        }
      }
      entry = FrDics.word(orthAtt.toLower()); // known word ?
      if (entry != null) { // known word
        // if not after a pun, maybe a capitalized concept État, or a name La Fontaine, 
        // or a title — Le Siècle, La Plume, La Nouvelle Revue, etc. 
        // restore initial cap
        if (!waspun) termAtt.buffer()[0] = c1;
        flagsAtt.setFlags(entry.tag);
        if (entry.lem != null) lemAtt.setEmpty().append(entry.lem);
        if (entry.orth != null) {
          orthAtt.copy(entry.orth);
        }
        return true;
      }
      else { // unknown word, infer it's a NAME
        flagsAtt.setFlags(Tag.NAME.flag);
        orthAtt.copy(copy);
        return true;
      }
    }
    else {
      FrDics.norm(orthAtt); // normalise oeil -> œil
      entry = FrDics.word(orthAtt);
      if (entry == null) return true;
      // known word
      flagsAtt.setFlags(entry.tag);
      if (entry.lem != null) lemAtt.append(entry.lem);
      if (entry.orth != null) {
        orthAtt.copy(entry.orth);
      }
    }
    return true;
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    save = null;
    waspun = true;
  }
}
