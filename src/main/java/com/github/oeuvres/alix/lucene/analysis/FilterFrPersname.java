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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.Roll;

/**
 * Plug behind a linguistic tagger, will concat unknown names from dictionaries
 * like Victor Hugo, V. Hugo, Jean de La Salle… A dictionary of proper names
 * allow to provide canonical versions for known strings (J.-J. Rousseau →
 * Rousseau, Jean-Jacques)
 */
public class FilterFrPersname extends TokenFilter
{
    /** Particles in names */
    public static final HashSet<CharsAttImpl> PARTICLES = new HashSet<CharsAttImpl>();
    static {
        for (String w : new String[] { "d'", "D'", "de", "De", "du", "Du", "l'", "L'", "le", "Le", "la", "La", "von",
                "Von" })
            PARTICLES.add(new CharsAttImpl(w));
    }
    /** Current char offset */
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Current term */
    private final CharsAttImpl termAtt = (CharsAttImpl) addAttribute(CharTermAttribute.class);
    /** A normalized orthographic form */
    private final CharsAttImpl orthAtt = (CharsAttImpl) addAttribute(OrthAtt.class);
    /** A lemma, needed to restore states */
    private final CharsAttImpl lemAtt = (CharsAttImpl) addAttribute(LemAtt.class);
    /** An efficient stack of states */
    private Roll<State> stack = new Roll<State>(8);
    /** Chars used to concat names like in text */
    private CharsAttImpl concTerm = new CharsAttImpl();
    /** Chars used to concat names with some normalization, like M. > monsieur */
    private CharsAttImpl concOrth = new CharsAttImpl();
    /** Chars used to concat a candidate lemma for person: Mr A. Nom > Nom */
    private CharsAttImpl concLem = new CharsAttImpl();
    /** Exit value */
    private boolean exit = false;

    public FilterFrPersname(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        // go ahead as record tokens to replay
        if (!stack.isEmpty()) {
            restoreState(stack.remove());
            return true;
        }
        exit = input.incrementToken();
        if (!exit)
            return false;

        final int flags = flagsAtt.getFlags();
        int tagEnd = flags; // ensure best tag for the name
        // is a name starting ?
        boolean nameFound = false;
        // if a foreName, it is a person
        if (flags == Tag.NAMEpersf.flag || flags == Tag.NAMEpersm.flag) {
            tagEnd = Tag.NAMEpers.flag;
            nameFound = true;
        }
        // found as a name (capitalization resolved)
        else if (Tag.NAME.sameParent(flags) && Char.isUpperCase(termAtt.charAt(0))) {
            tagEnd = flags;
            nameFound = true;
        }
        // possible candidates
        else if (flags == Tag.SUBpers.flag) { // Madame, Maître…
            tagEnd = Tag.NAMEpers.flag;
            stack.add(captureState()); // store state in case of rewind (Monsieur Madeleine YES, Madame va bien ? NO)
        } else if (flags == Tag.SUBplace.flag) { // Rue, faubourg…
            tagEnd = Tag.NAMEplace.flag;
            stack.add(captureState()); // allow possibility to rewind
        } else
            return true; // no names, go out

        concTerm.setEmpty();
        concOrth.setEmpty();
        concLem.setEmpty();
        // mark() is used in case of rewind [Europe] de l’]atome
        concTerm.copy(termAtt).mark(); // record size
        if (orthAtt.isEmpty())
            concOrth.copy(termAtt).mark();
        else if (orthAtt.equals("monsieur"))
            concOrth.setLength(0).append("M.").mark();
        else if (orthAtt.equals("madame"))
            concOrth.setLength(0).append("Mme").mark();
        else
            concOrth.copy(orthAtt).mark(); // a previous filter may have set something good, mlle > mademoiselle

        if (flagsAtt.getFlags() == Tag.SUBpers.flag)
            ; // monsieur, madame, not the key
        else if (!lemAtt.isEmpty())
            concLem.copy(lemAtt).mark();
        else
            concLem.copy(concOrth).mark();

        // record offsets
        final int startOffset = offsetAtt.startOffset();
        int endOffset = offsetAtt.endOffset();

        // test compound names : NAME (particle|NAME)* NAME
        // TODO: Louis XIV

        while ((exit = input.incrementToken())) {
            // always capture the go ahead token, stack will be empty if a name found
            stack.add(captureState());
            final int flags2 = flagsAtt.getFlags();
            // a particle, be careful to [Europe de l']atome
            if (PARTICLES.contains(termAtt)) {
                concTerm.append(" " + termAtt);
                concOrth.append(" " + termAtt);
                if (concLem.isEmpty())
                    concLem.append(termAtt); // Madame de Maintenon => "de Maintenon"
                else
                    concLem.append(" " + termAtt);
                continue;
            }
            // a part of name, append it
            // char.isUpperCase(termAtt.charAt(0)) // unsafe ex : verses L’amour de ma mère
            // // Je dirais au grand César
            // Louis le Grand ?
            if (Char.isUpperCase(termAtt.charAt(0))) { // Tag.NAME.sameParent(flags2)
                // Set final flag according to future events
                if (flags2 == Tag.NAMEplace.flag && tagEnd != Tag.NAMEpers.flag)
                    tagEnd = Tag.NAMEplace.flag; // Le [comte de Toulouse] is a person, not a place
                else if (flags2 == Tag.NAMEpers.flag)
                    tagEnd = Tag.NAMEpers.flag;
                else if (flags2 == Tag.NAMEpersf.flag)
                    tagEnd = Tag.NAMEpers.flag;
                else if (flags2 == Tag.NAMEpersm.flag)
                    tagEnd = Tag.NAMEpers.flag;
                // separator
                String sep = " ";
                if (termAtt.lastChar() == '\'')
                    sep = "";
                concTerm.append(sep + termAtt);
                if (!orthAtt.isEmpty())
                    concOrth.append(sep + orthAtt);
                else
                    concOrth.append(sep + termAtt);

                if (concLem.isEmpty() && (flags2 == Tag.NAMEpersm.flag || flags2 == Tag.NAMEpersf.flag))
                    ; // de Suzon
                else {
                    if (concLem.isEmpty())
                        sep = "";// lem may be empty here
                    if (!lemAtt.isEmpty())
                        concLem.append(sep + lemAtt);
                    else
                        concLem.append(sep + orthAtt);
                }
                stack.clear(); // we can empty the stack here, sure there is something to resend
                endOffset = offsetAtt.endOffset(); // record endOffset for last Name
                // record the size of terme here if we have to rewind (ex: Europe de l’atome)
                concTerm.mark();
                concOrth.mark();
                concLem.mark();
                nameFound = true;
                continue;
            }
            break;
        }
        // no mame found in the go ahead, replay stack
        if (!nameFound) { // rewind stack
            restoreState(stack.remove()); // restore first token recorded
            return true;
        }
        // first token to send, a name, remember rewind(): [Europe] de l’]atome
        flagsAtt.setFlags(tagEnd);
        termAtt.setEmpty().append(concTerm.rewind());
        orthAtt.setEmpty().append(concOrth.rewind());
        if (concLem.isEmpty())
            lemAtt.setEmpty().append(concOrth.rewind());
        else
            lemAtt.setEmpty().append(concLem.rewind());
        offsetAtt.setOffset(startOffset, endOffset);
        return true;
    }
}
