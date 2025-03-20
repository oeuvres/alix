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
import java.util.HashSet;
import java.util.LinkedList;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.FrDics.LexEntry;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAttImpl;

/**
 * Plug behind TokenLem, take a Trie dictionary, and try to compound locutions.
 */
public class FilterPos extends TokenFilter
{
    static {
        System.setProperty("opennlp.interner.class","opennlp.tools.util.jvm.JvmStringInterner");
    }
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Current char offset */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** A normalized orthographic form (ex : capitalization) */
    private final OrthAtt orthAtt = addAttribute(OrthAtt.class);
    /** A lemma when possible */
    private final LemAtt lemAtt = addAttribute(LemAtt.class);
    /** A stack of states */
    private AttDeque queue;

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterPos(TokenStream input) {
        super(input);
        // here, “this” has not all its attributes, AttributeQueue.copyTo() will bug
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        // needed here to have all atts in queue
        if (queue == null) {
            queue = new AttDeque(200, this);
        }
        // store states till pun
        // when pun, send sentence to posTagger, set pos in queue
        // empty the queue
        
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
