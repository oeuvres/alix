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
package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import com.github.oeuvres.alix.common.Upos;
import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.lucene.analysis.FilterCloud;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.util.Calcul;
import com.github.oeuvres.alix.util.Char;

/**
 * A lucene token filter adding other channels to the token stream
 * <ul>
 * <li>an orthographic form (normalized) in a {@link OrthAtt}</li>
 * <li>a lemma in a {@link LemAtt}</li>
 * <li>a pos as a lucene int flag {@link FlagsAttribute} (according to the
 * semantic of {@link TagFr}</li>
 * </ul>
 * <p>
 * The efficiency of the dictionaries lookup and chars manipulation rely on a
 * custom implementation of a lucene term attribute {@link OrthAtt}.
 * </p>
 * <p>
 * The original {@link CharTermAttribute} provide by the step before is not
 * modified, allowing further filters to choose which token to index, see for
 * example {@link FilterCloud}.
 * </p>
 * <p>
 * The found lemma+pos is dictionary based. No disambiguation is tried, so that
 * errors are completely deterministic. The dictionary provide the most frequent
 * lemma+pos for a graphic form. This approach has proved its robustness,
 * especially with infrequent texts from past centuries, on which training is
 * not profitable.
 * </p>
 * <p>
 * Logic could be extended to other languages with same linguistic resources,
 * there are here rules on capitals to infer proper names, specific to English
 * or French (not compatible with German for example).
 * </p>
 */
public final class FilterLemmatize extends TokenFilter
{
    /** The lexicon from which read lemmas */
    private final LemmaDic lexicon;
    /** The term provided by the Tokenizer */
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    /** A normalized orthographic form (ex : capitalization) */
    private final OrthAtt orthAtt = addAttribute(OrthAtt.class);
    /** A lemma when possible */
    private final LemAtt lemAtt = addAttribute(LemAtt.class);
    /** Current Flags */
    private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
    /** Last token was Punctuation */
    private boolean sent = true;
    /** Store state */
    private State save;
    
    public FilterLemmatize(TokenStream input)
    {
        super(input);
        throw new Error("TODO");
    }

    /**
     * Default constructor.
     * @param input previous filter.
     */
    public FilterLemmatize(TokenStream input, LemmaDic lexicon) {
        super(input);
        this.lexicon = lexicon;
    }

    @Override
    public final boolean incrementToken() throws IOException
    {
        // Reusable char sequence for some tests and transformations
        final CharsAttImpl testAtt = new CharsAttImpl();
        if (save != null) {
            restoreState(save);
            save = null;
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        
        lemAtt.setEmpty();
        int flags = flagsAtt.getFlags();
        if (flags == XML.code) { // tags maybe used upper
            return true;
        }
        // store sent event, skiping XML
        // was last token a sentence punctuation ?
        if (flags == PUNCTsection.code ||flags == PUNCTpara.code || flags == PUNCTsent.code) {
            // record a sent event
            this.sent = true;
            return true;
        }
        final boolean sentWas = sent;
        this.sent = false; // change it now
        // 0 length event ?
        if (termAtt.length() == 0)
            return true;
        // Get first char
        char c1 = termAtt.charAt(0);
        // Not a word
        if (!Char.isToken(c1)) return true;
        
        // upper process may have normalize some things
        if (orthAtt.length() == 0) {
            orthAtt.copy(termAtt); // start with original term
        }
        
        /*
        // normalise oeil -> œil, Etat -> État, naître -> naitre
        lexicon.norm((CharsAttImpl)orthAtt);

        // First letter of token is upper case, is it a name ? Is it an upper case
        // header ?
        // Do not touch to abbreviations
        if (Char.isUpperCase(c1)) {

            // roman number already detected
            if (flagsAtt.getFlags() == DIGIT.code) return true;
            int len = orthAtt.length();
            if (orthAtt.lastChar() == '.') len--;
            int n = Calcul.roman2int(orthAtt.buffer(), 0, len);
            // Roman number for more than one char, pb M<sup>elle</sup>
            if (len > 1 && n > 0) {
                flagsAtt.setFlags(DIGIT.code);
                lemAtt.setEmpty().append("" + n);
                return true;
            }
            // Copy orthAtt if restore is needed
            testAtt.copy(orthAtt);
            lexicon.norm(testAtt); // try normalisation before test in dic
            LexEntry entryName = lexicon.name(testAtt); // known name ? USSR ?
            if (entryName == null) {
                testAtt.capitalize();
                lexicon.norm(testAtt); // try normalisation before test
                entryName = lexicon.name(testAtt); // known name ?
                // normalized for exist
                if (entryName != null) {
                    orthAtt.setLength(0).append(testAtt);
                }
            }
            if (entryName != null) {
                // trust dictionary
                flagsAtt.setFlags(entryName.tag);
                // could be normalization
                if (entryName.lem != null) orthAtt.copyBuffer(entryName.lem, 0, entryName.lem.length);
                return true;
            }
            // Charles-François-Bienvenu, Va-t’en, Allez-vous
            int pos = orthAtt.indexOf('-');
            if (pos > 0) {
                final int length = testAtt.length();
                testAtt.setLength(pos);
                entryName = lexicon.name(testAtt);
                orthAtt.setLength(length); // restore length
                if (entryName != null) {
                    // trust dictionary
                    flagsAtt.setFlags(entryName.tag);
                    return true;
                }
            }
            // Is it a common word to lowerCase ?
            // be careful to dialogs, — <hi>Parce que c’est fermé.</hi>
            LexEntry entryWord = null;
            String tag = TagFr.name(flags);
            testAtt.toLower(); // test if lower is known
            if (tag != null) {
                final int testLength = testAtt.length();
                entryWord = lexicon.word(testAtt.append("_").append(tag));
                testAtt.setLength(testLength); // restore test length
            }
            if (entryWord == null) {
                entryWord = lexicon.word(testAtt);
            }
            if (entryWord != null) { // known word
                orthAtt.toLower();
                // norm here after right casing
                lexicon.norm(orthAtt);
                flagsAtt.setFlags(entryWord.tag); // trust dictionary
                if (entryWord.lem != null) {
                    lemAtt.copyBuffer(entryWord.lem, 0, entryWord.lem.length);
                }
                // say it is known by dico
                else {
                    lemAtt.copyBuffer(entryWord.inflection, 0, entryWord.inflection.length);
                }
                return true;
            }
            // unknown word, infer it's a NAME, force Tagger errors, maybe a book Title
            flagsAtt.setFlags(NAME.code);
            // Do not normalize caps, NAME -> Name, but URSS -> Urss, IIIA, Iiia
            // orthAtt.capitalize();
            return true;
        } 
        else {
            LexEntry entryWord = null;
            testAtt.copy(orthAtt);
            String tag = TagFr.name(flags);
            if (tag != null) {
                final int testLength = testAtt.length();
                entryWord = lexicon.word(testAtt.append("_").append(tag));
                testAtt.setLength(testLength); // restore test length
            }
            if (entryWord == null) {
                entryWord = lexicon.word(testAtt);
            }
            if (entryWord == null) {
                return true;
            }
            // known word
            flagsAtt.setFlags(entryWord.tag); // trust dictionary
            if (entryWord.lem != null) {
                lemAtt.copyBuffer(entryWord.lem, 0, entryWord.lem.length);
            }
            // say it is known by dico
            else {
                lemAtt.copyBuffer(entryWord.inflection, 0, entryWord.inflection.length);
            }

        }
        */
        return true;
    }
    

    @Override
    public void reset() throws IOException
    {
        super.reset();
        save = null;
        sent = true;
    }
}
