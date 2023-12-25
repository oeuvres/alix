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

import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.CharacterUtils.CharacterBuffer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.ML;

public class MetaTokenizer extends Tokenizer 
{
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  private int offset = 0, bufferIndex = 0, dataLen = 0;
  private static final int IO_BUFFER_SIZE = 4096;
  private final CharacterBuffer ioBuffer = CharacterUtils.newCharacterBuffer(IO_BUFFER_SIZE);
  private final boolean xml = true;
  /** A mutable String with hashCode() and compare() like String, used for testing values in Maps */
  private final Chain test = new Chain();
  @Override
  public boolean incrementToken() throws IOException
  {
    clearAttributes();
    int length = 0;
    int start = -1; // this variable is always initialized
    int end = -1;
    int endAdjust = -1;
    boolean intag = false;
    boolean inent = false;
    // char[] buffer = termAtt.buffer();
    while (true) {
      if (bufferIndex >= dataLen) {
        offset += dataLen;
        CharacterUtils.fill(ioBuffer, input); // read supplementary char aware with CharacterUtils
        if (ioBuffer.getLength() == 0) {
          dataLen = 0; // so next offset += dataLen won't decrement offset
          if (length > 0) {
            break;
          } else {
            correctOffset(offset);
            return false;
          }
        }
        dataLen = ioBuffer.getLength();
        bufferIndex = 0;
      }
      char c = ioBuffer.getBuffer()[bufferIndex];
      bufferIndex++;

      Chain test = this.test; // localize variable for efficiency


      // skip xml tags
      if(!xml); // do nothing 
      else if (c == '<') { // start of tag
        // a token has been started, send it
        if (length > 0) {
          bufferIndex--; // replay start of tag at next call to skip it
          endAdjust = 0;
          break;
        }
        intag = true;
        continue;
      }
      else if (intag) { // inside tag
        if (c == '>') intag = false;
        continue;
      }


      // resolve html entities
      if(!xml); // do nothing 
      else if (c == '&') {
        if (length == 0)  start = offset + bufferIndex - 1;
        inent = true;
        test.reset();
        test.append(c);
        continue;
      }
      else if (inent == true) {
        // end of entity
        if (c == ';') {
          test.append(c);
          inent = false;
          final char c1 = ML.forChar(test); // will not work well on supplentary chars
          // entity is not recognize, append it as is to the term
          // update length and get next char
          if (c1 == 0) {
            termAtt.append(test);
            length += test.length();
            continue;
          }
          length++;
          c = c1; // char known give it further
        }
        // not an ASCII letter or digit, false entity, maybe just &
        else if (!ML.isInEnt(c)) {
          termAtt.append(test);
          length += test.length();
          break;
        }
        else { // append a new char to entity
          test.append(c);
          continue;
        }
      }

      // soft hyphen, do not append to term
      if (c == 0xAD) continue;

      // for a search parser, keep wildcard
      if (c == '*');
      // not a token char
      else if (!Char.isToken(c) || c == '-' || c == '\'' || c == '’') {
        if (length > 0) break; // end of token, send it
        else continue; // go next space
      }
            
      // start of a token, record start offset
      if (length == 0) {
        start = offset + bufferIndex - 1;
      }
      // append char as lower case
      termAtt.append(Char.toLower(c));
      length++;
    }
    end = offset + bufferIndex + endAdjust;
    offsetAtt.setOffset(correctOffset(start), correctOffset(end));
    return true;
  }
  
  @Override
  public void reset() throws IOException {
    super.reset();
    bufferIndex = 0;
    offset = 0;
    dataLen = 0;
    ioBuffer.reset(); // make sure to reset the IO buffer!!
  }

}
