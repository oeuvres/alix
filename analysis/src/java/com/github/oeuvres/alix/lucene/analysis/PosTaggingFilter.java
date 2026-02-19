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
import org.apache.lucene.util.ArrayUtil;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

/**
 * 
 */
public class PosTaggingFilter extends TokenFilter
{
    static {
        // let opennlp decide, he knows better
        // System.setProperty("opennlp.interner.class","opennlp.tools.util.jvm.JvmStringInterner");
    }
    /** The term provided by the Tokenizer */
    @SuppressWarnings("unused")
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    @SuppressWarnings("unused")
    private final ProbAttribute probAtt = addAttribute(ProbAttribute.class);
    /** A stack of states */
    private TokenStateQueue queue;
    /** Maximum size of a sentence to send to the tagger */
    final static int SENTMAX = 300;

    /** non thread safe tagger, one by instance of filter */
    private POSTaggerME tagger;
    /** queue indices of tokens passed to OpenNLP */
    private int[] token4tag = new int[0];


    /**
     * Default constructor.
     * 
     * @param input previous filter.
     */
    public PosTaggingFilter(TokenStream input, POSModel posModel)
    {
        super(input);
        tagger = new POSTaggerME(posModel);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {

        // 0) Drain queued tokens first
        if (queue != null && !queue.isEmpty()) {
            clearAttributes();
            queue.removeFirst(this);
            return true;
        }


        // 2) Fill queue until boundary or SENTMAX or EOF
        while (queue.size() < SENTMAX) {
            clearAttributes();
            if (!input.incrementToken())
                break;

            queue.addLast(this);

            final int pos = posAtt.getPos(); // structural classification from upstream
            if (pos == PUNCTsection.code || pos == PUNCTpara.code || pos == PUNCTsent.code) {
                break;
            }
        }

        final int n = queue.size();
        if (n == 0)
            return false;

        // 3) Build token4tag[] once (queue index -> included in tag sentence)
        int m = 0;
        // ensure capacity of map token->tag
        token4tag = ArrayUtil.grow(token4tag, n + 1);
        for (int i = 0; i < n; i++) {
            final PosAttribute p = queue.get(i).getAttribute(PosAttribute.class);
            if (p == null) continue; // defensive

            final int pos = p.getPos();

            // Skip structural XML tags entirely (do not emit empty strings to OpenNLP)
            if (pos == XML.code) continue;

            // Sentence/para/section boundaries: keep as punctuation token for the tagger
            if (pos == PUNCTsection.code || pos == PUNCTpara.code || pos == PUNCTsent.code) {
                token4tag[m++] = i;
                continue;
            }

            // Skip empty terms for OpenNLP
            final CharTermAttribute t = queue.get(i).getAttribute(CharTermAttribute.class);
            if (t == null || t.length() == 0) continue;
            token4tag[m++] = i;
        }

        // If nothing is taggable, just drain queue as-is
        if (m == 0) {
            clearAttributes();
            queue.removeFirst(this);
            return true;
        }
        
        // 4) Allocate exactly the OpenNLP sentence array (single String[] per sentence)
        final String[] sentence = new String[m];
        for (int j = 0; j < m; j++) {
            final int i = token4tag[j];
            final PosAttribute p = queue.get(i).getAttribute(PosAttribute.class);
            final int pos = p.getPos();

            if (pos == PUNCTsection.code || pos == PUNCTpara.code || pos == PUNCTsent.code) {
                sentence[j] = ".";
            } else {
                // safe because we filtered empty terms above
                sentence[j] = queue.get(i).getAttribute(CharTermAttribute.class).toString();
            }
        }

        

        // 5) Tag + write back using token4tag[] (no re-testing of “taggable” predicate)
        final String[] tags = tagger.tag(sentence);
        final double[] probs = tagger.probs();

        for (int j = 0; j < m; j++) {
            final int i = token4tag[j];

            final ProbAttribute prob = queue.get(i).getAttribute(ProbAttribute.class);
            if (prob != null) prob.setProb(probs[j]);

            final PosAttribute posAttr = queue.get(i).getAttribute(PosAttribute.class);
            if (posAttr == null) continue;

            final int origPos = posAttr.getPos();

            // Keep upstream punctuation unchanged (including boundaries we injected as ".")
            if (Upos.isPunct(origPos)) continue;

            final Upos upos = Upos.get(tags[j].replace('+', '_'));
            if (upos != null) posAttr.setPos(upos.code());
        }

        // 6) Emit first token of the now-tagged queue
        clearAttributes();
        queue.removeFirst(this);
        return true;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        if (queue == null)
            queue = new TokenStateQueue(SENTMAX, this);
        else
            queue.clear();
    }

    @Override
    public void end() throws IOException
    {

        super.end();
    }
}
