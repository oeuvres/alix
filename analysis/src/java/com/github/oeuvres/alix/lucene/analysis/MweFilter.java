/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;


/**
 * Replaces contiguous multi-word expressions (MWEs) by a single token using a compiled {@code MweLexicon}.
 *
 * <p>Replace-mode: constituents are not emitted.</p>
 *
 * <p>Assumptions:</p>
 * <ul>
 *   <li>Upstream pipeline has already produced a stable int token id per token (typically lemma id).</li>
 *   <li>MWEs are contiguous only (no gaps).</li>
 *   <li>Input token stream is linear (no token graphs: {@code posInc==0}).</li>
 * </ul>
 *
 * <p>Implementation note:</p>
 * <ul>
 *   <li>Uses {@link TokenStateQueue} to buffer lookahead tokens allocation-free (no {@code State} objects).</li>
 *   <li>Computes the longest match starting at the current head token on every call.</li>
 * </ul>
 */
public final class MweFilter extends TokenFilter {

    private TokenStateQueue queue;
  private final MweLexicon lex;
  private final int maxTokens;

  // Standard Lucene attrs
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private final OffsetAttribute offAtt = addAttribute(OffsetAttribute.class);
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
  private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);

  // Your attrs (examples; use your actual ones)
  private final PosAttribute posAtt = addAttribute(PosAttribute.class);
  // private final LemmaIdAttribute lemmaIdAtt = addAttribute(LemmaIdAttribute.class);
  // private final PosIdAttribute posIdAtt = addAttribute(PosIdAttribute.class);

  // Carry position increments from skipped tokens inside a matched MWE to the next emitted token.
  private int carryPosInc = 0;

  // Scratch for emitting the canonical output term (sized once).
  private final char[] outScratch;

  public MweFilter(TokenStream input, MweLexicon lex) {
    super(input);
    this.lex = lex;
    this.maxTokens = Math.max(1, lex.maxLen()); // consider renaming to lex.maxPatternTokens()
    this.outScratch = new char[Math.max(16, lex.maxOutputLen())];
  }

  @Override
  public void reset() throws IOException {
    super.reset();
    carryPosInc = 0;

    // Instantiate here so the attribute set is complete (safer than constructor-time for some chains).
    if (queue == null || queue.capacity() != maxTokens) {
      queue = new TokenStateQueue(maxTokens, maxTokens, TokenStateQueue.OverflowPolicy.THROW, this);
    } else {
      queue.clear();
    }
  }

  @Override
  public boolean incrementToken() throws IOException {

    // 1) Ensure at least one token is buffered.
    if (queue.isEmpty()) {
      if (!readAndEnqueueOne()) return false;
    }

    // 2) Fill lookahead up to maxTokens (bounded).
    while (queue.size() < maxTokens) {
      if (!readAndEnqueueOne()) break;
    }

    // 3) Longest match from the head of the queue.
    int bestEntry = -1;
    int bestLen = 0;

    int st = lex.root();
    final int limit = Math.min(queue.size(), maxTokens);

    for (int i = 0; i < limit; i++) {
      final AttributeSource s = queue.get(i);
      // final int tokId = s.getAttribute(LemmaIdAttribute.class).getLemmaId();

      // st = lex.step(st, tokId);
      if (st < 0) break;

      final int entryId = lex.acceptEntry(st);
      if (entryId >= 0) {
        bestEntry = entryId;
        bestLen = i + 1;
      }
    }

    // 4) Emit compound if multi-token match, otherwise emit head token as-is.
    if (bestLen >= 2) {
      emitCompound(bestEntry, bestLen);
      return true;
    } else {
      emitSingle();
      return true;
    }
  }

  /**
   * Reads one token from input and appends its snapshot to the queue.
   * Returns false at end-of-stream.
   *
   * <p>Rejects token graphs (posInc==0) as per filter assumptions.</p>
   */
  private boolean readAndEnqueueOne() throws IOException {
    if (!input.incrementToken()) return false;

    if (posIncAtt.getPositionIncrement() == 0) {
      throw new IllegalArgumentException("MweFilter cannot consume token graphs (posInc==0).");
    }

    // Capture current token into the queue (queue was built from this filter as template).
    queue.addLast();
    return true;
  }

  /**
   * Emits the head token unchanged and removes it from the queue.
   * Applies any pending carry position increment once.
   */
  private void emitSingle() {
    // restore + pop
    queue.removeFirst(this);

    if (carryPosInc != 0) {
      posIncAtt.setPositionIncrement(posIncAtt.getPositionIncrement() + carryPosInc);
      carryPosInc = 0;
    }
  }

  /**
   * Emits a compound token for the match at the head and removes the matched tokens from the queue.
   * Applies carryPosInc to the compound token and accumulates skipped posIncs for the next emission.
   */
  private void emitCompound(final int entryId, final int lenTokens) {

    // Compute match span and skipped position increments BEFORE mutating the queue.
    final int endOffset = queue.get(lenTokens - 1).getAttribute(OffsetAttribute.class).endOffset();

    int skippedPosInc = 0;
    for (int i = 1; i < lenTokens; i++) {
      skippedPosInc += queue.get(i)
          .getAttribute(PositionIncrementAttribute.class)
          .getPositionIncrement();
    }

    // Restore+pop the first token: base attributes for the compound token.
    queue.removeFirst(this);

    // Apply pending carry to the compound token (from previous match).
    if (carryPosInc != 0) {
      posIncAtt.setPositionIncrement(posIncAtt.getPositionIncrement() + carryPosInc);
      carryPosInc = 0;
    }

    // Remove the remaining constituents (they will not be emitted).
    for (int i = 1; i < lenTokens; i++) {
      queue.removeFirst();
    }

    // Carry the skipped increments to the next emitted token.
    carryPosInc += skippedPosInc;

    // Replace term text with canonical output form from lexicon.
    final int outLen = lex.copyOutput(entryId, outScratch);
    termAtt.copyBuffer(outScratch, 0, outLen);

    // Span offsets across the whole MWE.
    offAtt.setOffset(offAtt.startOffset(), endOffset);

    // Token spans lenTokens positions.
    posLenAtt.setPositionLength(lenTokens);

    // Assign POS + optional lemma id for the MWE token.
    // posIdAtt.setPosId(lex.pos(entryId));
    final int mweLemma = lex.lemmaId(entryId);
    // if (mweLemma >= 0) lemmaIdAtt.setLemmaId(mweLemma);
  }
}

