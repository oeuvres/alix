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

import com.github.oeuvres.alix.common.Upos;
import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.util.Char;

/**
 * A final token filter before indexation, to plug after a lemmatizer filter,
 * providing most significant tokens for word cloud. Index lemma instead of
 * forms when available. Strip punctuation and numbers. Positions of striped
 * tokens are deleted. This allows simple computation of a token context (ex:
 * span queries, co-occurrences).
 */
public class FilterCloud extends TokenFilter
{
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** The position increment (inform it if positions are stripped) */
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    /** A linguistic category as a short number, see {@link TagFr} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** A normalized orthographic form */
    private final OrthAtt orthAtt = addAttribute(OrthAtt.class);
    /** A lemma when possible */
    private final LemAtt lemAtt = addAttribute(LemAtt.class);
    /** keep right position order */
    private int holes;


    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterCloud(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        // skipping positions will create holes, the count of tokens will be different
        // from the count of positions
        holes = 0;
        while (input.incrementToken()) {
            if (skip()) continue;
            if (accept()) {
                if (holes != 0) posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + holes);
                return true;
            }
            holes += posIncrAtt.getPositionIncrement();
        }
        return false;
    }
    
    /**
     * Token to skip, without the position, different noises.
     * @return
     */
    protected boolean skip()
    {
        final int flags = flagsAtt.getFlags();
        // known word from dictionary, keep it
        if (!lemAtt.isEmpty()) return false;
        // empty
        if (termAtt.isEmpty()) return true;
        // no position for XML between words M<sup>elle</sup>
        if (flags == XML.code) return true;
        // unknown short word
        if (termAtt.length() < 3) return true;
        // < >
        if (Char.isMath(termAtt.charAt(0))) return true;
        char charLast = termAtt.charAt(termAtt.length() - 1);
        // variable like A'
        if (charLast == '\'') return true;
        // variable like A.
        if (charLast == '.' && termAtt.length() == 2) return true;
        // variable like A4
        if (Char.isDigit(charLast) && !Char.isDigit(termAtt.charAt(termAtt.length() - 2))) return true;
        // default is no skip
        return false;
    }

    /**
     * Most of the tokens are not rejected but rewrited, except punctuation.
     * 
     * @return true if accepted
     */
    protected boolean accept()
    {
        final int flags = flagsAtt.getFlags();
        if (flags == TEST.code) {
            System.out.println(termAtt + " — " + orthAtt);
        }
        // record an empty token at puctuation position for the rails
        if (PUNCT.isPunct(flags)) {
            if (flags == PUNCTclause.code) {
            }
            else if (flags == PUNCTsent.code) {
            }
            else if (flags == PUNCTpara.code || flags == PUNCTsection.code) {
                // let it
            }
            else {
            }
            termAtt.setEmpty().append("");
            return true;
        }
        // unify numbers
        if (flags == DIGIT.code || flags == NUM.code) {
            termAtt.setEmpty().append("#");
            return true;
        }
        
        // do not keep flexion on substantives, no semantic gain
        if (!lemAtt.isEmpty()) termAtt.setEmpty().append(lemAtt);
        else if (!orthAtt.isEmpty()) termAtt.setEmpty().append(orthAtt);
        // no more suffix
        return true;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
    }

    @Override
    public void end() throws IOException
    {
        super.end();
    }

}
