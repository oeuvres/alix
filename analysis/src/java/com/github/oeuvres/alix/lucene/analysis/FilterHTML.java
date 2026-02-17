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
import java.util.Set;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import static com.github.oeuvres.alix.common.Upos.*;
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
    /** A linguistic category as a short number, see {@link TagFr} */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** A flag for non content element */
    private int skip;
    /** Elements to skip */
    static final CharArraySet SKIP = new CharArraySet(10, false);
    static {
        SKIP.add("aside");
        SKIP.add("nav");
        SKIP.add("note");
        SKIP.add("teiHeader");
    }
    // TODO formula
    /** Elements para like */
    static final CharArraySet PARA = new CharArraySet(20, false);
    static {
        PARA.add("cell");
        PARA.add("h1");
        PARA.add("h2");
        PARA.add("h3");
        PARA.add("h4");
        PARA.add("h5");
        PARA.add("h6");
        PARA.add("item"); 
        PARA.add("label");
        PARA.add("li"); 
        PARA.add("p"); 
        PARA.add("tr"); 
    }
    /** Elements section like */
    static final CharArraySet SECTION = new CharArraySet(10, false);
    static {
        SECTION.add("article");
        SECTION.add("section");
    }

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterHTML(TokenStream input) {
        super(input);
        skip = 0;
    }
    
    static private int tagEnd(char[] chars)
    {
        for (int i = 0, len = chars.length; i < len; i++) {
            char c = chars[i];
            if (c == '>' || c == ' ') {
                return i;
            }
            // <br/> </span>
            if (i > 1 && c == '/') {
                return i;
            }
        }
        return -1;
    }


    @SuppressWarnings("unlikely-arg-type")
    @Override
    public final boolean incrementToken() throws IOException
    {
        while (input.incrementToken()) {
            // update the char wrapper with present term
            
            test.wrap(termAtt.buffer(), termAtt.length());
            final int flags = flagsAtt.getFlags();
            final boolean xml = (flags == XML.code);
            boolean open = false;
            boolean close = false;
            if (xml) {
                close =  test.startsWith("</");
                if (!close) open = test.startsWith('<');
            }
            int tagOff = -1;
            int tagLen = -1;
            if (open) {
                tagLen = tagEnd(termAtt.buffer());
                if (tagLen > 1) {
                    tagOff = 1;
                    tagLen = tagLen - tagOff;
                }
                else {
                    tagLen = -1;
                }
            }
            else if (close) {
                tagLen = tagEnd(termAtt.buffer());
                if (tagLen > 2) {
                    tagOff = 2;
                    tagLen = tagLen - tagOff;
                }
                else {
                    tagLen = -1;
                }
            }
            if (skip > 0) {
                // not XML tag, skip
                if (!xml) {
                    continue;
                }
                // opening tag in skip, one level more of skip
                else if (open && SKIP.contains(termAtt.buffer(), tagOff, tagLen)) {
                    skip++;
                    continue;
                }
                else if (close && SKIP.contains(termAtt.buffer(), tagOff, tagLen)) {
                    skip--;
                    if (skip < 0) skip = 0; // secure
                    continue;
                }
                continue;
            }
            
            // not XML tag, return it with no change
            if (!xml) {
                return true;
            }
            else if (open && SKIP.contains(termAtt.buffer(), tagOff, tagLen)) {
                skip++;
                continue;
            }
            else if (close &&  PARA.contains(termAtt.buffer(), tagOff, tagLen)) {
                flagsAtt.setFlags(PUNCTpara.code);
                termAtt.setEmpty().append("¶");
                return true;
            }
            else if (close &&  SECTION.contains(termAtt.buffer(), tagOff, tagLen)) {
                flagsAtt.setFlags(PUNCTsection.code);
                termAtt.setEmpty().append("§");
                return true;
            }
            // other tags, do something ?
            else {
                
            }
        }
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
