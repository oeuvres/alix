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
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

/**
 * Plug behind TokenLem, take a Trie dictionary, and try to compound locutions.
 */
public class FilterLocution extends TokenFilter
{
    /** Current char offset */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Current original term, do not cast here */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** A normalized orthographic form (ex : capitalization) */
    private final OrthAtt orthAtt = addAttribute(OrthAtt.class);
    /** A lemma when possible */
    private final CharsAttImpl lemAtt = (CharsAttImpl) addAttribute(CharTermAttribute.class);
    /** A stack of states */
    private AttributeQueue queue;
    /** A term used to concat a compound */
    private CharsAttImpl compound = new CharsAttImpl();
    /** past paticiples to not take as infinitives */
    public static final HashSet<CharsAttImpl> EXCEPTIONS = new HashSet<CharsAttImpl>();
    static {
        for (String w : new String[] { "pris", "prise'", "prises" }) {
            EXCEPTIONS.add(new CharsAttImpl(w));
        }
    }

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterLocution(TokenStream input) {
        super(input);
        queue = new AttributeQueue(10, this);
    }

    /**
     * Debug tool to see what is in stack of states.
     * 
     * @param stack a list.
     * @return a view for dev of all {@link State}.
     */
    public String toString(LinkedList<State> stack)
    {
        String out = "";
        State restore = captureState();
        boolean first = true;
        for (State s : stack) {
            if (first)
                first = false;
            else
                out += ", ";
            restoreState(s);
            out += termAtt;
        }
        restoreState(restore);
        return out;
    }

    @SuppressWarnings("unlikely-arg-type")
    @Override
    public final boolean incrementToken() throws IOException
    {
        CharsAttImpl orth = (CharsAttImpl) orthAtt;
        compound.setEmpty();
        int startOffset = offsetAtt.startOffset();
        boolean verbSeen = false;
        // a dead end has not conclude as a locution, exhaust states recorded in queue
        if (!queue.isEmpty()) {
            queue.removeFirst(this);
            return true;
        }

        // let’s start to explore the tree
        do {
            boolean hasToken = input.incrementToken();
            // no more token, and nothing to output, exit
            if (!hasToken && queue.isEmpty()) {
                return false;
            }
            // no more token, but still some terms in queue to send
            if (!hasToken) {
                queue.removeFirst(this);
                return true;
            }
            // if token is pun, end of branch, exit
            if (Tag.PUN.sameParent(flagsAtt.getFlags()) || termAtt.length() == 0) {
                // if queue is not empty, copy this state, restore first, and send
                if (!queue.isEmpty()) {
                    queue.addLast(this);
                    queue.removeFirst(this);
                }
                return true;
            }
            // append a ' ' to last token (if any) for compound test
            if (!compound.isEmpty() && !compound.endsWith('\'')) { // append separator before the term
                compound.append(' ');
            }
            // choose version of form to append for test, according to its pos
            int tag = flagsAtt.getFlags();
            // forms to keep as is
            if (orthAtt.length() != 0 && EXCEPTIONS.contains(orthAtt)) {
                compound.append(orthAtt);
            }
            else if (Tag.NUM.sameParent(tag)) {
                compound.append("NUM");
            }
            else if (Tag.NAME.sameParent(tag)) {
                compound.append(termAtt);
            }
            // verbs, compound key is the lemma
            else if (Tag.VERB.sameParent(tag) && lemAtt.length() != 0) {
                verbSeen = true;
                compound.append(lemAtt);
            }
            // "ne fait pas l’affaire"
            else if (verbSeen && orth.equals("pas")) {
                compound.rtrim(); // suppres last ' '
            }
            // for other words, orth may have correct initial capital of sentence
            else if (!Tag.SUB.sameParent(tag) && orth.length() != 0) {
                compound.append(orth);
            }
            // Nations Unies ?
            else {
                compound.append(termAtt);
            }

            final Integer nodeType = FrDics.TREELOC.get(compound);

            // dead end
            if (nodeType == null) {
                // if queue is not empty, copy this state, restore first, and send
                if (!queue.isEmpty()) {
                    queue.addLast(this);
                    queue.removeFirst(this);
                }
                return true;
            }
            // a locution found, set state of atts according to this locution
            else if ((nodeType & FrDics.LEAF) > 0) {
                queue.clear();
                // get its entry
                LexEntry entry = FrDics.WORDS.get(compound);
                if (entry == null) {
                    entry = FrDics.NAMES.get(compound);
                }
                // known entry, find its lem
                if (entry != null) {
                    flagsAtt.setFlags(entry.tag);
                    termAtt.setEmpty().append(compound);
                    if (entry.orth != null) {
                        orth.setEmpty().append(entry.orth);
                    }
                    else {
                        orth.setEmpty();
                    }
                    if (entry.lem != null) {
                        lemAtt.setEmpty().append(entry.lem);
                    }
                    else {
                        lemAtt.setEmpty();
                    }
                }
                // no lemma or tags for this locution
                else {
                    termAtt.setEmpty().append(compound);
                    orth.setEmpty().append(compound);
                    lemAtt.setEmpty();
                }
                // set offset
                offsetAtt.setOffset(startOffset, offsetAtt.endOffset());
                // no more locution candidate starting with same prefix
                if ((nodeType & FrDics.BRANCH) == 0) {
                    return true;
                }
                // store this locution in the queue, try to go ahead ((chemin de fer) d’intérêt local)
                queue.addLast(this);
            }
            // should be a part of a compound, store state in case dead end, for rewind 
            else if ((nodeType & FrDics.BRANCH) > 0) {
                queue.addLast(this);
            }
            else {
                throw new RuntimeException("Unknow value in TREELOC:" + nodeType);
            }
        } while (true); // a compound bigger than 10 should hurt queue
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
