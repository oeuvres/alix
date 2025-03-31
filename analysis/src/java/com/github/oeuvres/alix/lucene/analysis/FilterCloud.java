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

import static com.github.oeuvres.alix.common.Flags.*;
import static com.github.oeuvres.alix.fr.TagFr.*;

import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

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
    private int skippedPositions;
    /** Convert flags as tag to append to term */
    static String[] suffix = new String[256];
    static {
        suffix[VERB.code] = "_VERB"; // 305875
        suffix[SUB.code] = ""; // 110522
        suffix[ADJ.code] = "_ADJ"; // 67833
        suffix[VERBger.code] = "_VERB"; // 8207
        suffix[ADV.code] = "_ADV"; // 2336
        suffix[VERBppas.code] = "_VERB"; // 1107
        suffix[VERBexpr.code] = "_VERB"; // 270
        suffix[NUM.code] = ""; // 254
        suffix[EXCL.code] = ""; // 166
        suffix[VERBmod.code] = "_VERB"; // 91
        suffix[VERBaux.code] = "_AUX"; // 89
        suffix[PREP.code] = "_MG"; // 71
        suffix[PROpers.code] = "_MG"; // 51
        suffix[ADVscen.code] = "_MG"; // 33
        suffix[DETindef.code] = "_MG"; // 31
        suffix[PROindef.code] = "_MG"; // 28
        suffix[PROdem.code] = "_MG"; // 27
        suffix[ADVasp.code] = "_MG"; // 24
        suffix[ADVdeg.code] = "_MG"; // 23
        suffix[PROrel.code] = "_MG"; // 18
        suffix[PROquest.code] = "_MG"; // 16
        suffix[CONJsub.code] = "_MG"; // 16
        suffix[DETposs.code] = "_MG"; // 15
        suffix[ADVconj.code] = "_MG"; // 15
        suffix[DETart.code] = "_MG"; // 11
        suffix[DETdem.code] = "_MG"; // 10
        suffix[CONJcoord.code] = "_MG"; // 10
        suffix[ADVneg.code] = "_MG"; // 9
        suffix[ADVquest.code] = "_MG"; // 4
        suffix[DETprep.code] = "_MG"; // 4
    }

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
        skippedPositions = 0;
        while (input.incrementToken()) {
            // no position for XML between words
            if (flagsAtt.getFlags() == XML.code) {
                continue;
            }
            if (accept()) {
                if (skippedPositions != 0) {
                    posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + skippedPositions);
                }
                return true;
            }
            skippedPositions += posIncrAtt.getPositionIncrement();
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
        final int flags = flagsAtt.getFlags();
        if (flags == TEST.code) {
            System.out.println(termAtt + " — " + orthAtt);
        }
        // record an empty token at puctuation position for the rails
        if (PUN.isPun(flags)) {
            if (flags == PUNclause.code) {
            }
            else if (flags == PUNsent.code) {
            }
            else if (flags == PUNpara.code || flags == PUNsection.code) {
                // let it
            }
            else {
                // termAtt.setEmpty().append("");
            }
            return true;
        }
        // unify numbers
        if (flags == DIGIT.code || flags == NUM.code) {
            termAtt.setEmpty().append("#");
            return true;
        }
        
        // keep flexion of substantives ? Nothing to append to term
        if (flags == SUB.code) {
            if (orthAtt.length() != 0) {
                termAtt.setEmpty().append(orthAtt);
            }
            return true;
        }
        if (!lemAtt.isEmpty()) termAtt.setEmpty().append(lemAtt);
        else if (!orthAtt.isEmpty()) termAtt.setEmpty().append(orthAtt);
        termAtt.append(suffix[flags]);
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
