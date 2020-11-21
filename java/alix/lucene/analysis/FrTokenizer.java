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
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.AttributeImpl;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.Calcul;
import alix.util.Char;
import alix.util.ML;

/**
 * <p>
 * A Lucene tokenizer for XML/html (and French).
 * Has been tested with thousands of documents in html or XML/TEI.
 * Goal is to keep all tags to store the document, 
 * and get exact offsets of text tokens,
 * allowing hiliting of words, without perturbation of tags.
 * </p>
 * The content inside “skip tags” is not indexed.
 * <ul>
 *  <li>&lt;style&gt;</li>
 *  <li>&lt;script&gt;</li>
 *  <li>&lt;teiHeader&gt;</li>
 * </ul>
 * The tokenizer also recognize commands sent as processing instructions
 * (for section of documents wanted for display but not for index).
 * <ul>
 *  <li>&lt;?index_off?&gt;: stop to send tokens from this point</li>
 *  <li>&lt;?index_on?&gt; restart token stream.</li>
 * </ul>
 * Some tags are interpreted as structural events.
 * This is useful for sentence separation (a title may not finish with a dot)
 * <ul>
 *  <li>&lt;p&gt;</li>
 *  <li>&lt;h1&gt;, &lt;h2&gt;, &lt;h3&gt;, &lt;h4&gt;, &lt;h5&gt;, &lt;h6&gt;</li>
 * </ul>
 * <p>
 * The token separation follow Latin punctuation and should be compatible 
 * with most European languages (but is not extensively tested).
 * </p>
 * <p>
 * The resolution of elision (') and hyphen (-) is language specific (French).
 * </p>
 * <p>
 * </p>
 */
public class FrTokenizer extends Tokenizer
{
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term, as an array of chars */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** For string testings */
  CharsAtt test = new CharsAtt();
  /** For string storing */
  CharsAtt copy = new CharsAtt();
  /** Store state */
  private State save;
  /** Source buffer of chars, delegate to Lucene experts */
  private final CharacterBuffer bufSrc = CharacterUtils.newCharacterBuffer(4096);
  /** Pointer in buffer */
  private int bufIndex = 0;
  /** Length of the buffer */
  private int bufLen = 0;
  /** Max length for a token */
  int maxTokenLen = 256;
  /** Input offset */
  private int offset = 0;
  /** Final input offset */
  private int finalOffset = 0;
  /**
   * French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel
   */
  public static final HashSet<CharsAtt> HYPHEN_POST = new HashSet<CharsAtt>();
  static {
    for (String w : new String[] { "ce", "ci", "elle", "elles", "en", "eux", "il", "ils", "je", "la", "là", "le", "les",
        "leur", "lui", "me", "moi", "nous", "on", "t", "te", "toi", "tu", "vous", "y" })
      HYPHEN_POST.add(new CharsAtt(w));
  }

  /** Parse as XML */
  boolean xml = true;
  /** tags to send as token events and translate */
  public static final HashMap<CharsAtt, CharsAtt> TAGS = new HashMap<CharsAtt, CharsAtt>();
  static {
    TAGS.put(new CharsAtt("p"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("h1"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("h2"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("h3"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("h4"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("h5"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("h6"), new CharsAtt("<p>"));
    TAGS.put(new CharsAtt("section"), new CharsAtt("<section>"));
    TAGS.put(new CharsAtt("/section"), new CharsAtt("</section>"));
  }
  /** tag content to skip */
  public static final HashMap<CharsAtt, CharsAtt> SKIP = new HashMap<CharsAtt, CharsAtt>();
  static {
    SKIP.put(new CharsAtt("teiHeader"), new CharsAtt("/teiHeader"));
    SKIP.put(new CharsAtt("head"), new CharsAtt("/head"));
    SKIP.put(new CharsAtt("script"), new CharsAtt("/script"));
    SKIP.put(new CharsAtt("style"), new CharsAtt("/style"));
    SKIP.put(new CharsAtt("?index_off?"), new CharsAtt("?index_on?"));
  }
  /** Store closing tag to skip */
  private CharsAtt skip = null;

  public FrTokenizer()
  {
    this(true);
  }

  /**
   * Handle xml tags ?
   * 
   * @param xml
   */
  public FrTokenizer(boolean xml)
  {
    super(new AlixAttributeFactory(AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY));
    this.xml = xml;
  }

  @Override
  public final boolean incrementToken() throws IOException
  {
    // The
    clearAttributes();
    // send term event
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }

    int startOffset = -1; // this variable is always initialized
    int ltOffset = -1;
    int hyphOffset = -1; // keep offset of last hyphen
    CharsAtt term = (CharsAtt) this.termAtt;
    CharsAtt test = this.test;
    FlagsAttribute flags = flagsAtt;
    char[] buffer = bufSrc.getBuffer();
    boolean intag = false;
    boolean tagname = false;
    boolean xmlent = false;
    char lastChar = 0;
    int offLast = 0; // convenient flag for offset in some cases
    char c = 0; // current char
    while (true) {
      final int length = term.length();
      // grab more chars
      if (bufIndex >= bufLen) {
        offset += bufLen;
        // use default lucene code to read chars from source
        CharacterUtils.fill(bufSrc, input); // read supplementary char aware with CharacterUtils
        bufLen = bufSrc.getLength();
        bufIndex = 0;
        // end of buffer
        if (bufLen != 0) ;
        else if (length > 0) { // a term to send
          break;
        }
        else { // finish !
          finalOffset = correctOffset(offset);
          return false;
        }
      }

      // got a char, let's work
      c = buffer[bufIndex];
      bufIndex++;

      // a very light XML parser
      if (!xml) ;
      else if (c == '<') { // start tag
        // a word was started, send it, ex Word<note>2</note>
        if (length != 0) {
          bufIndex--;
          // will exclude the 
          // offLast = offset - ltOffset;
          break;
        }
        // keep memory of start index of this tag
        ltOffset = offset + bufIndex - 1;
        intag = true;
        tagname = true;
        test.setEmpty();
        continue;
      }
      else if (intag) { // inside tag
        if (tagname) { // start to record tagname
          if (!test.isEmpty() && (c == ' ' || c == '>' || (c == '/'))) tagname = false;
          else test.append(c);
        }
        if (c == '>') {
          intag = false;
          // end of skip element
          if (skip != null && skip.equals(test)) {
            skip = null;
            continue;
          }
          // inside skip element
          else if (skip != null) {
            continue;
          }
          // start of a skip element
          else if (SKIP.containsKey(test)) {
            skip = SKIP.get(test);
            continue;
          }
          CharsAtt el = TAGS.get(test); // test the tagname
          test.setEmpty();
          if (el == null) { // unknown tag
            continue; 
          }
          // Known tag to send
          if (length != 0) { // A word has been started
            // save state with the word
            State state = captureState();
            // save state with xml tag for next
            offsetAtt.setOffset(correctOffset(ltOffset), correctOffset(offset + bufIndex));
            termAtt.setEmpty().append(el);
            flags.setFlags(Tag.PUNdiv);
            save = captureState();
            // send the word
            restoreState(state);
            break;
          }
          // A tag has to be sent
          startOffset = ltOffset;
          term.append(el);
          flags.setFlags(Tag.PUNdiv);
          break;
        }
        continue;
      }
      // inside a tag to skip, go throw
      else if (skip != null) {
        continue;
      }
      else if (c == '&') {
        if (length == 0) startOffset = offset + bufIndex - 1;
        xmlent = true;
        test.setEmpty();
        test.append(c);
        continue;
      }
      else if (xmlent == true) {
        test.append(c);
        if (c != ';') continue;
        // end of entity
        xmlent = false;
        c = ML.forChar(test); // will not work well on supplentary chars
        if (c != 0) term.append(c);
        else term.append(test);
        test.setEmpty();
        continue;
      }

      // decimals
      if (Char.isDigit(lastChar) && (c == '.' || c == ',')) {
        term.append(c);
        lastChar = c; // for 6. 7.
        continue;
      }

      // Clause punctuation, send a punctuation event to separate tokens
      if (Char.isPUNcl(c)) {
        // send term before
        if (length != 0) {
          bufIndex--;
          break;
        }
        term.append(c);
        flags.setFlags(Tag.PUNcl);
        startOffset = offset + bufIndex - 1;
        break;
      }

      // Possible sentence delimiters
      if (c == '.' || c == '…' || c == '?' || c == '!' || c == '«' || c == '—' || c == ':') {
        
        // token starting by a sentence punctuation
        if (length == 0) {
          flags.setFlags(Tag.PUNsent);
          startOffset = offset + bufIndex - 1;
          term.append(c);
          lastChar = c; // give for ... ???
          continue;
        }
        // for ... ???
        if (flags.getFlags() == Tag.PUNsent) {
          continue;
        }
        // test if it's an abreviation with a dot
        if (c == '.') {
          // int roman;
          term.append('.');
          // M., etc.
          if (FrDics.brevidot(term)) {
            continue; // keep dot
          }
          // Fin de phrase.
          else if (Char.isLowerCase(lastChar)) {
            term.setLength(term.length() - 1);
          }
          // 1.5
          else if (!Char.isUpperCase(lastChar)) {
            continue;
          }
          // XVIII.
          else if ((Calcul.roman2int(term.buffer(), 0, term.length()-1)) > 0) {
            flagsAtt.setFlags(Tag.NUM);
          }
          // RODOGUNE. dot is a punctuation
          else if (term.length() > 2 && Char.isUpperCase(term.charAt(0)) && Char.isUpperCase(term.charAt(1))) {
            term.setLength(term.length() - 1);
          }
          // U.{S.A.} 
          else if (term.length() < 3) {
            continue;
          }
          // U.S.{A.} 
          else if (term.charAt(term.length() - 3) == '.') {
            continue;
          }
          // default, is punctuation
          else {
            term.setLength(term.length() - 1);
          }
        }
        // seems a punctuation
        bufIndex--;
        break;
      }

      // store the position of an hyphen, and check if there is not one
      if (c == '-' && length != 0) {
        hyphOffset = offset + bufIndex;
        test.setEmpty();
      }
      // it's a token char, + is for queries
      if (Char.isToken(c) || c == '+') {
        // start of token, record startOffset
        if (length == 0) {
          if (Char.isDigit(c)) flagsAtt.setFlags(Tag.NUM);
          startOffset = offset + bufIndex - 1;
        }

        
        if (c == (char) 0xAD) continue; // soft hyphen, do not append to term
        if (c == '’') c = '\''; // normalize apos
        term.append(c);
        
        // Is hyphen breakable?
        if (hyphOffset > 0 && c != '-') test.append(c);
        // Is apos breakable?
        if (c == '\'') {
          CharsAtt val = FrDics.ELISION.get(term);
          if (val != null) {
            val.copyTo(term);
            break;
          }
        }
        // something get wrong in loops or it is not a text with space, for example 
        if (length >= maxTokenLen) break; // a too big token stop
      }
      // a non token char, a word to send
      else if (length > 0) {
        offLast = 1; // do not take this char in offset delimitation
        break;
      }
      lastChar = c;
    }
    // send term event
    int endOffset = offset + bufIndex - offLast;
    
    /* Buggy when ends like 15 juin 1938. (ending dot)
    // something like 1. 2.
    if ((c == '.' || c == ')' || c == '°') && flags.getFlags() == Tag.NUM) {
      term.setEmpty().append('#');
      endOffset++;
      bufIndex++; // bad fix, big problem with last char
      flags.setFlags(Tag.PUNsent);
    }
    */
    // splitable hyphen ? split on souviens-toi, murmura-t-elle, but not
    // Joinville-le-Pont,
    if (hyphOffset > 0 && HYPHEN_POST.contains(test)) {
      // swap terms to store state of word after hyphen
      // Laisse-moi ! Réveille-le. eploi-t-il ?
      int len = term.length() - test.length() - 1;
      term.setLength(len); 
      if (term.endsWith("-t")) term.setLength(len-2); // french specific, euphonic t
      copy.copy(term);
      term.copy(test);
      offsetAtt.setOffset(correctOffset(hyphOffset), correctOffset(endOffset));
      save = captureState();
      // send the word before hyphen
      term.copy(copy);
      endOffset = hyphOffset - 1;
    }
    assert startOffset != -1;
    startOffset = correctOffset(startOffset);
    finalOffset = correctOffset(endOffset);
    offsetAtt.setOffset(startOffset, finalOffset);
    return true;

  }

  @Override
  public final void end() throws IOException
  {
    super.end();
    // set final offset
    offsetAtt.setOffset(finalOffset, finalOffset);
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    bufIndex = 0;
    offset = 0;
    bufLen = 0;
    finalOffset = 0;
    bufSrc.reset(); // make sure to reset the IO buffer!!
  }

  /**
   * An attribute factory
   * 
   * @author fred
   *
   */
  private static final class AlixAttributeFactory extends AttributeFactory
  {
    private final AttributeFactory delegate;

    public AlixAttributeFactory(AttributeFactory delegate)
    {
      this.delegate = delegate;
    }

    @Override
    public AttributeImpl createAttributeInstance(Class<? extends Attribute> attClass)
    {
      if (attClass == CharTermAttribute.class) return new CharsAtt();
      return delegate.createAttributeInstance(attClass);
    }
  }

}
