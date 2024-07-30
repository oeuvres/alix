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
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

/**
 * A final token filter before indexation, to plug after a lemmatizer filter,
 * providing most significant tokens for word cloud. Index lemma instead of
 * forms when available. Strip punctuation and numbers. Positions of striped
 * tokens are deleted. This allows simple computation of a token context (ex:
 * span queries, co-occurrences).
 */
public class FilterXML extends TokenFilter
{
    /** XML flag */
    final static int XML = Tag.XML.flag;
    final static int PARA = Tag.PUNpara.flag;
    final static int SECTION = Tag.PUNsection.flag;
    /** The term provided by the Tokenizer */
    private final CharsAttImpl termAtt = (CharsAttImpl) addAttribute(CharTermAttribute.class);
    /** The position increment (inform it if positions are stripped) */
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    /** A linguistic category as a short number, see {@link Tag} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterXML(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {
            // not XML tag, return it with no change
            if (flagsAtt.getFlags() != XML) {
                return true;
            }
            // most positions of XML tags will be skipped without information
            if (termAtt.equals("</p>") || termAtt.equals("</li>") || termAtt.equals("</td>")) {
                flagsAtt.setFlags(PARA);
                termAtt.setEmpty().append("¶");
                return true;
            }
            if (termAtt.equals("</section>") || termAtt.equals("</article>")) {
                flagsAtt.setFlags(SECTION);
                termAtt.setEmpty().append("§");
                return true;
            }
        }
        /*
         * while (input.incrementToken()) { if (accept()) return true; }
         */
        return false;
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
