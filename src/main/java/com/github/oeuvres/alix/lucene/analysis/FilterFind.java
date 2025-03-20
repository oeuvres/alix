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
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

/**
 * A token Filter to plug after a Lemmatizer. Add lemma to forms on same
 * position, good for find, bad for stats.
 */
public class FilterFind extends TokenFilter
{
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** A normalized orthographic form (ex : capitalization) */
    private final OrthAtt orthAtt = addAttribute(OrthAtt.class);
    /** A lemma when possible */
    private final LemAtt lemAtt = addAttribute(LemAtt.class);
    /** Last token was Punctuation */
    /** A lemma when possible */
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    /** Flag to record lemma */
    boolean lem;
    /** keep right position order */
    private int skippedPositions;

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterFind(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        CharTermAttribute term = this.termAtt;
        // append lemma on same position and offset
        if (lem) {
            posIncAtt.setPositionIncrement(0);
            // lower casing names
            term.setEmpty().append(lemAtt.toLower());
            lem = false;
            return true;
        }
        skippedPositions = 0;
        while (input.incrementToken()) {
            // no position for XML between words
            if (flagsAtt.getFlags() == Tag.XML.no()) {
                continue;
            }
            if (accept()) {
                // if an interesting lemma, inform next call to index it at same pos
                if (this.lemAtt.length() != 0) {
                    lem = true;
                }
                if (skippedPositions != 0) {
                    posIncAtt.setPositionIncrement(posIncAtt.getPositionIncrement() + skippedPositions);
                }
                return true;
            }
            skippedPositions += posIncAtt.getPositionIncrement();
        }
        return false;
    }

    /**
     * Most of the tokens are not rejected but rewrited, except punctuation.
     * 
     * @return true if accepted
     */
    protected boolean accept()
    {
        final int tag = flagsAtt.getFlags();
        // jump punctuation position
        if (Tag.PUN.sameParent(tag)) {
            return false;
        }
        // append normalize form if exists
        if (!orthAtt.isEmpty()) {
            termAtt.setEmpty().append(orthAtt);
            return true;
        }
        // other cases ?
        return true;
    }
}
