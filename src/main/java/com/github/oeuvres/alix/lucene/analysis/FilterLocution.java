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
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsLemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsOrthAtt;
import com.github.oeuvres.alix.util.Roll;

/**
 * Plug behind TokenLem, take a Trie dictionary, and try to compound locutions.
 */
public class FilterLocution extends TokenFilter
{
    /** Current char offset */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /**
     * Current original term, do not cast here, or effects could be inpredictable
     */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** A normalized orthographic form (ex : capitalization) */
    private final CharsOrthAtt orthAtt = addAttribute(CharsOrthAtt.class);
    /** A lemma when possible */
    private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
    /** A stack of states */
    private Roll<State> stack = new Roll<State>(10);
    /** A term used to concat a compound */
    private CharsAtt compound = new CharsAtt();
    /** past paticiples to not take as infinitives */
    public static final HashSet<CharsAtt> ORTH = new HashSet<CharsAtt>();
    static {
        for (String w : new String[] { "pris", "prise'", "prises" })
            ORTH.add(new CharsAtt(w));
    }

    public FilterLocution(TokenStream input) {
        super(input);
    }

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
        CharsAtt orth = (CharsAtt) orthAtt;
        boolean token = false;
        compound.setEmpty();
        Integer treeState;
        final int BRANCH = FrDics.BRANCH; // localize
        final int LEAF = FrDics.LEAF; // localize
        int loop = -1;
        int startOffset = offsetAtt.startOffset();
        boolean maybeVerb = false;
        do {
            loop++;
            // something in stack, 2 cases
            // 1. good to output
            // 2. restart a loop
            if (stack.size() > loop) {
                restoreState(stack.get(loop));
                if (Tag.PUN.sameParent(flagsAtt.getFlags()) || termAtt.length() == 0) {
                    if (stack.isEmpty())
                        return true;
                }
            } else {
                boolean more = input.incrementToken();
                if (!more) { // stream is exhausted, exhaust the stack,
                    if (stack.isEmpty())
                        return false; // nothing more to find
                    restoreState(stack.remove());
                    return true;
                }
                // is a branch stop, exhaust stack
                if (Tag.PUN.sameParent(flagsAtt.getFlags()) || termAtt.length() == 0) {
                    // if nothing in stack, go out with current state
                    if (stack.isEmpty())
                        return true;
                    // if stack is not empty, restore first, add this state to the stack
                    if (!stack.isEmpty())
                        stack.add(captureState());
                    restoreState(stack.remove());
                    return true;
                }
                token = true; // ???
            }
            // first token of a compound candidate, remember start offset
            if (loop == 0)
                startOffset = offsetAtt.startOffset();
            // not the first token prepare compounding
            if (loop > 0 && !compound.endsWith('\''))
                compound.append(' ');

            int tag = flagsAtt.getFlags();
            // for adjectives to no confuse with verbs
            if (orthAtt.length() != 0 && ORTH.contains(orthAtt)) {
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
                maybeVerb = true;
                compound.append(lemAtt);
            }
            // "ne fait pas l’affaire"
            else if (maybeVerb && orth.equals("pas")) {
                compound.setLength(compound.length() - 1); // suppres last ' '
            }
            // for other words, orth may have correct initial capital of sentence
            else if (!Tag.SUB.sameParent(tag) && orth.length() != 0) {
                compound.append(orth);
            }
            // Nations Unies ?
            else {
                compound.append(termAtt);
            }

            treeState = FrDics.TREELOC.get(compound);

            if (treeState == null) {
                // here, we could try to fix "parti pris" ("pris" seen as verb "prendre") ?
                // if nothing in stack, and new token, go out with current state
                if (stack.isEmpty() && loop == 0)
                    return true;
                // if stack is not empty and a new token, add it to the stack
                if (token)
                    stack.add(captureState());
                restoreState(stack.remove());
                return true;
            }

            // it’s a compound
            if ((treeState & LEAF) > 0) {
                stack.clear();
                // get its entry
                LexEntry entry = FrDics.WORDS.get(compound);
                if (entry == null)
                    entry = FrDics.NAMES.get(compound);
                if (entry != null) {
                    flagsAtt.setFlags(entry.tag);
                    termAtt.setEmpty().append(compound);
                    if (entry.orth != null)
                        orth.setEmpty().append(entry.orth);
                    else
                        orth.setEmpty();
                    if (entry.lem != null)
                        lemAtt.setEmpty().append(entry.lem);
                    else
                        lemAtt.setEmpty();
                } else {
                    termAtt.setEmpty().append(compound);
                    orth.setEmpty().append(compound);
                    lemAtt.setEmpty();
                }

                offsetAtt.setOffset(startOffset, offsetAtt.endOffset());
                // no more compound with this prefix, we are happy
                if ((treeState & BRANCH) == 0)
                    return true;
                // compound may continue, lookahead should continue, store this step
                stack.add(captureState());
            }
            // should be a part of a compound, store state if it’s a new token
            else {
                if (token)
                    stack.add(captureState());
            }

        } while (loop < 10); // a compound bigger than 10, there’s a problem, should not arrive
        if (!stack.isEmpty()) {
            restoreState(stack.remove());
            return true;
        }
        return true; // ?? pb à la fin
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
