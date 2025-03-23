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
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

/**
 * A final token filter before indexation, to plug after a lemmatizer filter,
 * providing most significant tokens for word cloud. Index lemma instead of
 * forms when available. Strip punctuation and numbers. Positions of striped
 * tokens are deleted. This allows simple computation of a token context (ex:
 * span queries, co-occurrences).
 */
public class FilterHTML extends TokenFilter
{
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final CharsAttImpl test = new CharsAttImpl();
    /** The position increment (inform it if positions are stripped) */
    // private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    /** A linguistic category as a short number, see {@link Tag} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** A flag for non content element */
    private int skip;

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterHTML(TokenStream input) {
        super(input);
        skip = 0;
    }

    @SuppressWarnings("unlikely-arg-type")
    @Override
    public final boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {
            // update the char wrapper
            test.wrap(termAtt.buffer(), termAtt.length());
            if (skip > 0) {
                if (test.equals("</aside>") || test.equals("</nav>")) {
                    skip--;
                    if (skip < 0) skip = 0;
                }
                continue;
            }
            
            // not XML tag, return it with no change
            if (flagsAtt.getFlags() != Tag.XML.no) {
                return true;
            }
            if (
                    test.startsWith("<aside") || test.startsWith("<nav")) {
                skip++;
                continue;
            }
            // most positions of XML tags will be skipped without information
            if (test.equals("</p>") || test.equals("</li>") || test.equals("</td>")) {
                flagsAtt.setFlags(Tag.PUNpara.no);
                termAtt.setEmpty().append("¶");
                return true;
            }
            if (test.equals("</section>") || test.equals("</article>")) {
                flagsAtt.setFlags(Tag.PUNsection.no);
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
        skip = 0;
    }

    @Override
    public void end() throws IOException
    {
        super.end();
        skip = 0;
    }

}
