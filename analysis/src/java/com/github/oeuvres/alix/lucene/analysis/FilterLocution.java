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
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.FrDics.LexEntry;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

/**
 * Plug behind TokenLem, take a Trie dictionary, and try to compound locutions.
 */
public class FilterLocution extends TokenFilter
{
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
    /** A term used to concat a compound */
    private CharsAttImpl compound = new CharsAttImpl();

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterLocution(TokenStream input) {
        super(input);
        // here, “this” has not all its attributes, AttributeQueue.copyTo() will bug
    }

    @SuppressWarnings("unlikely-arg-type")
    @Override
    public final boolean incrementToken() throws IOException
    {
        // needed here to have all atts in queue
        if (queue == null) {
            queue = new AttDeque(10, this);
        }
        clearAttributes(); // clear before next incrementToken
        // restart compound at each call
        compound.setEmpty();
        
        // cast 
        
        
        // flag up to exhaust queue before getting new token

        boolean verbSeen = false;

        // start with a token
        int queuePos = 0;
        boolean tokFirst = false;
        if (!queue.isEmpty()) { // exhaust queue
            queue.peekFirst(this);
            queuePos++;
        }
        else { // or get new token
            tokFirst = input.incrementToken();
            // no more token, exit and say it
            if(!tokFirst) return false;
        }
        // record start of a possible locution candidate
        int startLoc = offsetAtt.startOffset();
        
        // let’s start to find a locution
        do {
            final int tag = flagsAtt.getFlags();
            // if token is pun, end of branch, exit
            if (TagFr.PUN.sameParent(tag) || tag == TagFr.XML.no || termAtt.length() == 0) {
                // after the loop, the queue logic before exit
                break;
            }
            // append a ' ' to last token (if any) for compound test
            if (!compound.isEmpty() && !compound.endsWith('\'')) { // append separator before the term
                compound.append(' ');
            }
            // choose version of form to append for test, according to its pos
            // forms to keep as is
            /*
            if (orthAtt.length() != 0 && EXCEPTIONS.contains(orthAtt)) {
                compound.append(orthAtt);
            }
            */
            if (TagFr.NUM.sameParent(tag)) {
                compound.append("#");
            }
            else if (TagFr.NAME.sameParent(tag)) {
                compound.append(termAtt);
            }
            // verbs, compound key is the lemma
            else if (TagFr.VERB.sameParent(tag) && lemAtt.length() != 0) {
                verbSeen = true;
                compound.append(lemAtt);
            }
            // "ne fait pas l’affaire"
            else if (verbSeen && orthAtt.equals("pas")) {
                compound.rtrim(); // suppres last ' '
            }
            // if original term ends with an apos, use it, D’accord
            else if (termAtt.charAt(termAtt.length() - 1) == '\'') {
                compound.append(termAtt, 0, termAtt.length());
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

            // dead end
            if (nodeType == null) {
                // the queue logic after the loop
                // queue.removeFirst();
                break;
            }

            // a locution found, set state of atts according to this locution
            if ((nodeType & FrDics.LEAF) > 0) {
                // get its entry
                FrDics.norm(compound); // xx e -> 20e
                LexEntry entry = FrDics.word(compound);
                if (entry == null) {
                    entry = FrDics.name(compound);
                }
                // known entry, find its lem
                if (entry != null) {
                    flagsAtt.setFlags(entry.tag);
                    termAtt.setEmpty().append(compound);
                    if (entry.graph != null) {
                        orthAtt.setEmpty().append(entry.graph);
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
                // it’s OK
                // queue.addLast(this);
                queuePos = 0;
            }
            if ((nodeType & FrDics.BRANCH) != 0) {
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
                    clearAttributes(); // clear before lematize, because of orth
                    boolean hasToken = input.incrementToken();
                    // no more token to explore branch, exhaust queue
                    if (!hasToken) {
                        queue.removeFirst(this);
                        return true;
                    }
                    queuePos = 0; // no more token to get from the queue, say it
                    queue.addLast(this);
                }
            }

            
        } while (true); // a compound bigger than queue should hurt and avoid infinite loop
        
        // do not add to queue here, every thing should have be done in branch
        if (queue.isEmpty()) { // common case, OK
            return true;
        }
        else { // we are in the queue
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
