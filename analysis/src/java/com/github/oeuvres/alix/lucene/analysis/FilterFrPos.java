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


import java.util.Map;

import java.io.IOException;
import java.io.InputStream;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import com.github.oeuvres.alix.common.Tag;
import com.github.oeuvres.alix.fr.French;

import static com.github.oeuvres.alix.common.Flags.*;
import static com.github.oeuvres.alix.fr.TagFr.*;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

/**
 * Plug behind TokenLem, take a Trie dictionary, and try to compound locutions.
 */
public class FilterFrPos extends TokenFilter
{
    static {
        // let opennlp decide, he knows better
        // System.setProperty("opennlp.interner.class","opennlp.tools.util.jvm.JvmStringInterner");
    }
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** A stack of states */
    private AttDeque queue;
    /** Maximum size of a sentence to send to the tagger */
    final static int SENTMAX = 300;
    /** The pos tagger */
    static private POSModel posModel;
    static {
        // model must be static, tagger should be thread safe till 

        try (InputStream modelIn = French.class.getResourceAsStream(French.OPENNLP_POS.resource)) {
             posModel = new POSModel(modelIn);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    /** non thread safe tagger, one by instance of filter */
    private POSTaggerME tagger;
    /** tag  */
    Map<String, Tag> tagList = Map.ofEntries(
        Map.entry("ADJ", ADJ),
        Map.entry("ADP", PREP),
        Map.entry("ADP+DET", DETprep),
        Map.entry("ADP+PRON", PREPpro),
        Map.entry("ADV", ADV),
        Map.entry("AUX", VERBaux),
        Map.entry("CCONJ", CONJcoord),
        Map.entry("DET", DET),
        Map.entry("INTJ", EXCL),
        Map.entry("NOUN", SUB),
        Map.entry("NUM", NUM),
        Map.entry("PRON", PRO),
        Map.entry("PROPN", NAME),
        Map.entry("PUNCT", TOKEN), // pun is filtered upper, tagger bug
        Map.entry("SCONJ", CONJsub),
        Map.entry("SYM", TOKEN),
        Map.entry("VERB", VERB),
        Map.entry("X", TOKEN)
    );
    /** state of the queue */
    private boolean tagged = false;

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterFrPos(TokenStream input) {
        super(input);
        tagger = new POSTaggerME(posModel);
        // here, “this” has not all its attributes, AttributeQueue.copyTo() will bug
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        // needed here to have all atts in queue
        if (queue == null) {
            queue = new AttDeque(SENTMAX, this);
        }
        // empty the queue
        if (!queue.isEmpty()) {
            clearAttributes();
            queue.removeFirst(this);
            return true;
        }
        boolean toksLeft = true;
        // store states till pun
        while (queue.size() < SENTMAX) {
            clearAttributes(); // clear before next incrementToken
            toksLeft = input.incrementToken();
            if (!toksLeft) break;
            queue.addLast(this);
            final int flags = flagsAtt.getFlags();
            if (
                   flags == PUNsection.code
                || flags == PUNpara.code
                || flags == PUNsent.code
            ) break;
        }
        // should be finisehd here
        if (queue.size() == 0) {
            assert toksLeft == false: "Tokens left but queue empty 🤔";
            return toksLeft;
        }
        String[] sentence = new String[queue.size()];
        boolean first = true;
        for (int i = 0; i < queue.size(); i++) {
            FlagsAttribute flags = queue.get(i).getAttribute(FlagsAttribute.class);
            // those tags will not help tagger
            if (flags.getFlags() == PUNsection.code || flags.getFlags() == PUNpara.code) {
                sentence[i] = "";
            }
            else {
                CharTermAttribute term = queue.get(i).getAttribute(CharTermAttribute.class);
                // do not intern, maybe better for memory but not for speed
                sentence[i] = new String(term.buffer(), 0, term.length());
                // bug initial cap, Tu_NAME vas_VERB bien_ ?_PUN
                if (first) sentence[i] = sentence[i].toLowerCase();
                first = false;
            }
        }
        String[] tags = tagger.tag(sentence);
        for (int i = 0; i < queue.size(); i++) {
            FlagsAttribute flags = queue.get(i).getAttribute(FlagsAttribute.class);
            // keep previous tags, especially pun precision ; do not trust pun inferences of tagger
            if (flags.getFlags() != TOKEN.code) {
            }
            else {
                flags.setFlags(tagList.get(tags[i]).code());
            }
        }
        clearAttributes();
        queue.removeFirst(this);
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
