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
    private final CharsAttImpl orthAtt = (CharsAttImpl) addAttribute(OrthAtt.class);
    /** A lemma when possible */
    private final CharsAttImpl lemAtt = (CharsAttImpl) addAttribute(LemAtt.class);
    /** A stack of states */
    private AttributeQueue queue;
    /** A term used to concat a compound */
    private CharsAttImpl compound = new CharsAttImpl();
    /** past paticiples to not take as infinitives */
    public static final HashSet<CharsAttImpl> EXCEPTIONS = new HashSet<CharsAttImpl>();
    // parti pris, prise de conscience
    static {
        for (String w : new String[] {  }) {
            EXCEPTIONS.add(new CharsAttImpl(w));
        }
    }

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterLocution(TokenStream input) {
        super(input);
        // here, “this” has not all its attributes, AttributeQueue.copyTo() will bug
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
        if (queue == null) {
            queue = new AttributeQueue(10, this);
        }
        // System.out.println(queue);
        clearAttributes();
        // restart compound at each call
        compound.setEmpty();
        // flag up to exhaust queue before getting new token

        boolean verbSeen = false;

        // start with a token
        int queuePos = 0;
        boolean tokFirst = false;
        if (!queue.isEmpty()) {
            queue.peekFirst(this);
            queuePos++;
        }
        else {
            tokFirst = input.incrementToken();
            if(!tokFirst) {
                return false;
            }
        }
        int startLoc = offsetAtt.startOffset();
        
        // let’s start to find a locution
        do {
            final int tag = flagsAtt.getFlags();
            // if token is pun, end of branch, exit
            if (Tag.PUN.sameParent(tag) || tag == Tag.XML.flag || termAtt.length() == 0) {
                // after the loop, the queue logic before exit
                break;
            }
            // append a ' ' to last token (if any) for compound test
            if (!compound.isEmpty() && !compound.endsWith('\'')) { // append separator before the term
                compound.append(' ');
            }
            // choose version of form to append for test, according to its pos
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
            else if (verbSeen && orthAtt.equals("pas")) {
                compound.rtrim(); // suppres last ' '
            }
            // for other words, orth may have correct initial capital of sentence
            else if (orthAtt.length() != 0) {
                compound.append(orthAtt);
            }
            // Nations Unies ?
            else {
                compound.append(termAtt);
            }
            final Integer nodeType = FrDics.TREELOC.get(compound);

            // System.out.println("compound=" + compound + " type=" + nodeType + " isVerb=" + Tag.VERB.sameParent(tag) + " queue=" + queue);


            // dead end
            if (nodeType == null) {
                // the queue logic after the loop
                break;
            }

            // a locution found, set state of atts according to this locution
            if ((nodeType & FrDics.LEAF) > 0) {
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
                        orthAtt.setEmpty().append(entry.orth);
                    }
                    else {
                        orthAtt.setEmpty().append(compound);
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
                    orthAtt.setEmpty().append(compound);
                    lemAtt.setEmpty();
                }
                // set offset
                offsetAtt.setOffset(startLoc, offsetAtt.endOffset());
                
                // no more locution candidate starting with same prefix, send
                if ((nodeType & FrDics.BRANCH) == 0) {
                    queue.clear();
                    return true;
                }
                // try to go ahead ((chemin de fer) d’intérêt local)
                queue.clear();
                queue.addLast(this);
                queuePos = 0;
            }
            // here we should be in a branch
            if ((nodeType & FrDics.BRANCH) == 0) {
                throw new IOException("### not a branch ?" + queue);
            }
            // first token was new, add it to queue, 
            if (tokFirst) {
                queue.addLast(this);
                tokFirst = false;
            }
            // get another token from queue
            if (queuePos > 0 && queuePos < queue.size()) {
                queue.copyTo(this, queuePos);
                queuePos++;
            }
            // or get another token from stream
            else {
                boolean hasToken = input.incrementToken();
                // no more token to explore branch, exhaust queue
                if (!hasToken) {
                    queue.removeFirst(this);
                    return true;
                }
                queuePos = 0; // no more token to get from the queue, say it
                queue.addLast(this);
            }
            // continue, current token will be append to compound
            
        } while (true); // a compound bigger than queue should hurt and avoid infinite loop
        
        // do add add to queue here, every thing should have be done in branch
        if (queue.isEmpty()) { // common case, OK
            return true;
        }
        // we are in the queue
        else {
            queue.removeFirst(this);
        }
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
