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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.lucene.analysis.Lexicon;
import com.github.oeuvres.alix.lucene.analysis.Lexicon.LexEntry;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.CSVReader;
import com.github.oeuvres.alix.util.CSVReader.Row;

/**
 * Preloaded word List for lucene indexation in {@link HashMap}. Efficiency
 * strongly rely on a custom implementation of chars token attribute
 * {@link CharsAttImpl}, with a cached hash code {@link CharsAttImpl#hashCode()} and
 * comparison {@link CharsAttImpl#compareTo(CharsAttImpl)}.
 */
@SuppressWarnings("unlikely-arg-type")
public class FrLexicon extends Lexicon
{
    /** Column for a graphy like found in texts, required */
    public final static int COL_GRAPH = 0;
    /** Column for a grammatical category, required if no ORTH */
    public final static int COL_TAG = 1;
    /** Column for a lemma, optional */
    public final static int COL_LEM = 2;
    /** Column for normalized form at indexation */
    public final static int COL_NORM = 3;
    /** Flag for compound, end of term */
    static final public int LEAF = 0x100;
    /** Flag for compound, to be continued */
    static final public int BRANCH = 0x200;
    /** 500 000 types French lexicon seems not too bad for memory */
    static final CharArrayMap<LexEntry> WORDS = new CharArrayMap<>(500000, false);
    /** French names on which keep Capitalization */
    final static CharArrayMap<LexEntry> NAMES = new CharArrayMap<>(5000, false);
    /** A tree to resolve compounds */
    static final HashMap<CharsAtt, Integer> TREELOC = new HashMap<>((int) (1500 / 0.75));
    /** Graphic normalization (replacement) */
    static final private CharArrayMap<String> NORMALIZE = new CharArrayMap<>(5000, false);
    /** Abbreviations with a final dot */
    static final private CharArraySet BREVIDOT = new CharArraySet(200, false);
    /** Stopwords */
    static final private CharArraySet STOP = new CharArraySet(1000, false);
    /** Convert tags from dictionary to tags obtained by tagger 
        entry("ADJ", ADJ),
        entry("ADP", PREP),
        entry("ADP+DET", DETprep),
        entry("ADP+PRON", PREPpro),
        entry("ADV", ADV),
        entry("AUX", VERBaux),
        entry("CCONJ", CONJcoord),
        entry("DET", DET),
        entry("INTJ", EXCL),
        entry("NOUN", SUB),
        entry("NUM", NUM),
        entry("PRON", PRO),
        entry("PROPN", NAME),
        entry("PUNCT", PUN),
        entry("SCONJ", CONJsub),
        entry("SYM", TOKEN),
        entry("VERB", VERB),
        entry("X", TOKEN)
     */
    // Mapping from lexical resources tags to the pos tagger tags
    static Map<Chain, String> tagList = Map.ofEntries(
        Map.entry(new Chain("VERB"), "VERB"), // 305193
        Map.entry(new Chain("SUB"), "SUB"), // 110474
        Map.entry(new Chain("ADJ"), "ADJ"), // 67833
        Map.entry(new Chain("VERBger"), "VERB"), // 8207
        Map.entry(new Chain("ADV"), "ADV"), // 2331
        Map.entry(new Chain("VERBppas"), "VERB"), // 1107
        Map.entry(new Chain("VERBaux2"), "VERB"), // 639
        Map.entry(new Chain("VERBexpr"), "VERB"), // 270
        Map.entry(new Chain("NUM"), "NUM"), // 254
        Map.entry(new Chain("EXCL"), "EXCL"), // 166
        Map.entry(new Chain("VERBaux"), "VERBaux"), // 132
        Map.entry(new Chain("VERBmod"), "VERB"), // 91
        Map.entry(new Chain("PREP"), "PREP"), // 73
        Map.entry(new Chain("PROpers"), "PRO"), // 59
        Map.entry(new Chain("DETindef"), "DET"), // 34
        Map.entry(new Chain("ADVscen"), "ADV"), // 32
        Map.entry(new Chain("DETposs"), "DET"), // 31
        Map.entry(new Chain("PROindef"), "PRO"), // 26
        Map.entry(new Chain("ADVdeg"), "ADV"), // 23
        Map.entry(new Chain("ADVasp"), "ADV"), // 22
        Map.entry(new Chain("SUBplace"), "SUB"), // 22
        Map.entry(new Chain("PROdem"), "PRO"), // 21
        Map.entry(new Chain("ADVconj"), "ADV"), // 20
        Map.entry(new Chain("CONJsub"), "CONJsub"), // 16
        Map.entry(new Chain("DETart"), "DET"), // 11
        Map.entry(new Chain("ADVneg"), "ADV"), // 11
        Map.entry(new Chain("CONJcoord"), "CONJcoord"), // 10
        Map.entry(new Chain("DETprep"), "DETprep"), // 4
        Map.entry(new Chain("DETdem"), "DETdem"), // 4
        Map.entry(new Chain("ADVquest"), "ADV"), // 4
        Map.entry(new Chain("NAME"), "NAME"),
        Map.entry(new Chain("NAMEpers"), "NAME"),
        Map.entry(new Chain("NAMEpersm"), "NAME"),
        Map.entry(new Chain("NAMEpersf"), "NAME"),
        Map.entry(new Chain("NAMEplace"), "NAME"),
        Map.entry(new Chain("NAMEorg"), "NAME"),
        Map.entry(new Chain("NAMEevent"), "NAME"),
        Map.entry(new Chain("NAMEauthor"), "NAME"),
        Map.entry(new Chain("NAMEfict"), "NAME"),
        Map.entry(new Chain("NAMEtitle"), "NAME"),
        Map.entry(new Chain("NAMEpeople"), "NAME"),
        Map.entry(new Chain("NAMEgod"), "NAMEtag")
    );
    
    /**
     * Avoid instantiation, use static method instead.
     */
    FrLexicon()
    {
        // ???
        // loadStop("com/github/oeuvres/alix/fr/stop.csv");
        // Load dictionaries
        String[] files = { 
            "com/github/oeuvres/alix/fr/locutions.csv", // compounds to decompose
            "com/github/oeuvres/alix/fr/caps.csv",      // normalization of initial capital without accent
            "com/github/oeuvres/alix/fr/orth.csv",      // normalisation of oe œ, and some other word
            "com/github/oeuvres/alix/fr/num.csv",       // normalisation of ordinals for centuries
            "com/github/oeuvres/alix/fr/brevidot.csv",  // abbreviations finishing by a dot
            "com/github/oeuvres/alix/fr/word.csv",      // the big dic, before some names
            "com/github/oeuvres/alix/fr/author.csv",    // well known authorities (writers)
            "com/github/oeuvres/alix/fr/name.csv",      // other proper names
            "com/github/oeuvres/alix/fr/forename.csv",  // foreName with gender
            "com/github/oeuvres/alix/fr/place.csv",     // world place name 
            "com/github/oeuvres/alix/fr/france.csv",    // places in France
            "com/github/oeuvres/alix/fr/commune.csv"    // french town, at the end
        };
        for (String f : files) {
            // LOGGER.debug(f);
            loadResource(f, f);
        }
    }

    /**
     * Insert a compound candidate in the compound tree.
     * Is language dependent.
     * 
     * @param form a form to insert
     * @param tree the tree to insert in.
     */
    protected static void decompose(Chain form, HashMap<CharsAtt, Integer> tree)
    {
        int len = form.length();
        for (int i = 0; i < len; i++) {
            char c = form.charAt(i);
            if (c == '’') {
                c = '\'';
                form.setCharAt(i, c);
            }
            CharsAttImpl key;
            if (c == '\'')
                key = new CharsAttImpl(form.buffer(), 0, i + 1);
            else if (c == ' ')
                key = new CharsAttImpl(form.buffer(), 0, i);
            else continue;
            Integer entry = tree.get(key);
            if (entry == null)
                tree.put(key, BRANCH);
            else
                tree.put(key, entry | BRANCH);
        }
        // end of word
        CharsAttImpl key = new CharsAttImpl(form.buffer(), 0, len);
        Integer entry = tree.get(key);
        if (entry == null)
            tree.put(key, LEAF);
        else
            tree.put(key, entry | LEAF);
    }

    /**
     * Test if the requested chars are a known abbreviation ending by a dot.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return true if submitted form is an abbreviation, false otherwise.
     */
    public static boolean isBrevidot(CharTermAttribute att)
    {
        return BREVIDOT.contains(att.buffer(), 0, att.length());
    }
    


    /**
     * Test if the requested chars are a known stop word.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return true if submitted form is a know stop word, false otherwise.
     */
    /*
    public static boolean isStop(CharTermAttribute att)
    {
        return STOP.contains(att);
    }
    */


    /**
     * Insert a local csv (comma separated values) dictionary of 4 cols:
     * <ul>
     * <li>0. COL_GRAPH. Required, graphical form used as a key (could be a lemma for
     * verbs in locutions like “avoir l’air”).</li>
     * <li>1. COL_TAG. Required, morpho-syntaxic code.</li>
     * <li>2. COL_LEM. Optional, lemmatization.</li>
     * <li>4. ORTH. Optional, form normalization.</li>
     * </ul>
     * 
     * @param reader for file or jar.
     */
    synchronized static public void load(final String name, final Reader reader, boolean replace)
    {
        if (loaded.contains(name)) {
            System.out.println(name + " already loaded");
            return;
        }
        loaded.add(name);
        CSVReader csv = null;
        try {
            csv = new CSVReader(reader, 4, ',');
            csv.readRow(); // skip first line
            Row row;
            while ((row = csv.readRow()) != null) {
                Chain graph = row.get(COL_GRAPH);
                if (graph.isEmpty() || graph.charAt(0) == '#')
                    continue;
                // normalize apos
                graph.replace('’', '\'');
                // populate the tree of locutions, even with normalisation, to find it
                // do not put j’ or d’ in TREELOC
                
                boolean hasSpace = graph.contains(' ');
                if (hasSpace || (graph.contains('\'') && !graph.endsWith("'")) ) {
                    decompose(graph, TREELOC);
                }
                // known abbreviation with at least one final dot, add the compounds
                // do not handle here multi word abbreviation like "av. J.-C."
                Chain norm = row.get(COL_NORM);
                if (!hasSpace && graph.last() == '.') {
                    BREVIDOT.add(graph.toCharArray());
                }
                // check if it is normalization
                if (!norm.isEmpty()) {
                    NORMALIZE.put(graph.toCharArray(), norm.toString());
                    continue;
                }
                putRecord(graph, row.get(COL_TAG), row.get(COL_LEM), replace);
            }
            csv.close();
        } catch (Exception e) {
            System.out.println("Dictionary parse error in file " + reader);
            if (csv != null)
                System.out.println(" line " + csv.line());
            else
                System.out.println();
            e.printStackTrace();
        }
    }

    /**
     * Load a jar resource as dictionary.
     * 
     * @param res resource path according to the class loader.
     */
    private static void loadResource(final String key, final String res)
    {
        Lexicon.res = res;
        Reader reader = new InputStreamReader(Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(res), StandardCharsets.UTF_8);
        load(key, reader, false);
    }


    /**
     * Get a name entry from of a dictionary
     * with a char[] array.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available proper name entry for the submitted form, or null if not found.
     */
    public static LexEntry name(final CharTermAttribute att)
    {
        return NAMES.get(att.buffer(), 0, att.length());
    }
    
    /**
     * Get a name dictionary entry from a char array.
     *
     * @param chars  readonly chars to test
     * @param offset start offser
     * @param length amount of chars to test
     * @return available proper name entry for the submitted form, or null if not found.
     */
    public static LexEntry name(final char[] chars, final int offset, final int length)
    {
        return NAMES.get(chars, offset, length);
    }

    /**
     * Normalize orthographic form for a real graphical form in text.
     * 
     * @param att {@link CharTermAttribute} implementation, normalized.
     * @return true if a normalization has been done, false otherwise.
     */
    public static boolean norm(CharTermAttribute att)
    {
        String val = NORMALIZE.get(att.buffer(), 0, att.length());
        if (val == null)
            return false;
        att.setEmpty().append(val);
        return true;
    }
    
    /**
     * Normalize orthographic form for a real graphical form in text.
     * 
     * @param chain
     * @return
     */
    public static boolean norm(Chain chain)
    {
        String val = NORMALIZE.get(chain.buffer(), chain.offset(), chain.length());
        if (val == null)
            return false;
        chain.setEmpty().append(val);
        return true;
    }
    
    /**
     * Get normalized orthographic form for a real grapphical form in text.
     * 
     * @param test {@link CharAtt} implementation, normalized.
     * @return true if a normalization has been done, false otherwise.
     */
    /*
    public static boolean norm(final CharsAtt test, final CharTermAttribute dst)
    {
        CharsAtt val = NORMALIZE.get(test);
        if (val == null)
            return false;
        dst.setEmpty().append(val);
        return true;
    }
    */

    private static void putRecord(Chain graph, Chain tag, Chain lem, boolean replace)
    {
        String tagSuff = tagList.get(tag);
    
        // UnsupportedOperationException() with CharArrayMap
        /*
        if (graph.first() == '0') {
            graph.firstDel();
            NAMES.remove(graph);
            WORDS.remove(graph);
            WORDS.remove(graph.append("_").append(tagSuff));
            return;
        }
        */
        lem.replace('’', '\'');
        graph.replace('’', '\'');
        // is a name, TODO safer ? 
        if (graph.isFirstUpper()) {
            if (NAMES.containsKey(graph.buffer(), graph.offset(), graph.length()) && !replace) return;
            LexEntry entry = new LexEntry(graph, tag, lem);
            NAMES.put(graph.toCharArray(), entry);
        }
        // is a word, add an entry for GRAPH_TAG
        else {
            LexEntry entry = new LexEntry(graph, tag, lem);
            if (tagSuff != null) {
                final int oldLen = graph.length();
                graph.append("_").append(tagSuff);
                if (!WORDS.containsKey(graph.buffer(), graph.offset(), graph.length()) || replace) {
                    WORDS.put(graph.toCharArray(), entry);
                }
                graph.setLength(oldLen);
            }
            if (!WORDS.containsKey(graph.buffer(), graph.offset(), graph.length()) || replace) {
                WORDS.put(graph.toCharArray(), entry);
            }
        }
    }
    
    /**
     * Get a dictionary entry from the name dictionary
     * from a char[] array ttribute.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available proper name entry for the submitted form, or null if not found.
     */
    public static LexEntry word(final CharTermAttribute att)
    {
        return WORDS.get(att.buffer(), 0, att.length());
    }
    
    /**
     * Get a word dictionary entry with a char array.
     *
     * @param chars  readonly chars to test
     * @param offset start offser
     * @param length amount of chars to test
     * @return available proper name entry for the submitted form, or null if not found.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available common word entry for the submitted form, or null if not found.
     */
    public static LexEntry word(final char[] chars, final int offset, final int len)
    {
        return WORDS.get(chars, offset, len);
    }


}
