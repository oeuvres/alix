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
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PosAttribute posAtt = addAttribute(PosAttribute.class);
    private final ProbAttribute probAtt = addAttribute(ProbAttribute.class);
    /** A stack of states */
    private TokenStateQueue queue;
    /** Maximum size of a sentence to send to the tagger */
    final static int SENTMAX = 300;

    /** non thread safe tagger, one by instance of filter */
    private POSTaggerME tagger;


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
        if (!queue.isEmpty()) {
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

        // 3) Build sentence[] for the tagger + detect if we have any lexical TOKEN
        final String[] sentence = new String[n];

        for (int i = 0; i < n; i++) {
            final PosAttribute p = queue.get(i).getAttribute(PosAttribute.class);
            final int pos = p.getPos();

            if (pos == PUNCTsection.code || pos == PUNCTpara.code) {
                sentence[i] = ".";
                continue;
            }

            final CharTermAttribute t = queue.get(i).getAttribute(CharTermAttribute.class);
            String s = t.toString();

            /*
            if (pos == TOKEN.code) {
                // Your “first word” workaround: apply only once, on the first lexical token
                if (firstLex && !s.isEmpty() && Character.isUpperCase(s.charAt(0))) {
                    s = s.toLowerCase(Locale.ROOT);
                }
                firstLex = false;
                needsTagging = true;
            }
            */

            sentence[i] = s;
        }

        // 4) Tag + write back into PosAttribute (only where upstream said TOKEN)
        final String[] tags = tagger.tag(sentence);
        double[] probs = tagger.probs();

        for (int i = 0; i < n; i++) {
            final ProbAttribute prob = queue.get(i).getAttribute(ProbAttribute.class);
            prob.setProb(probs[i]);
            final PosAttribute pos = queue.get(i).getAttribute(PosAttribute.class);
            final int origPos = pos.getPos();
            // Upper filter provide more precise punctuation than the tagger, keep it.
            if (Upos.isPunct(origPos)) continue;
            Upos upos = Upos.get(tags[i].replace('+', '_'));
            if (upos == null) {
                // for testing only
                // System.out.println(tags[i]);
            }
            else {
                pos.setPos(upos.code());
            }
            // else keep TOKEN (or choose a fallback)
        }

        // 5) Emit first token of the now-tagged queue
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
