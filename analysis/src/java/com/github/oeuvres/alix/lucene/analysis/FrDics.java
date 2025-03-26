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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.oeuvres.alix.fr.TagFr;
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
public class FrDics
{
    /** Column for a graphy like found in texts, required */
    public final static int GRAPH = 0;
    /** Column for a grammatical category, required if no ORTH */
    public final static int TAG = 1;
    /** Column for a lemma, optional */
    public final static int LEM = 2;
    /** Column for normalized form at indexation */
    public final static int NORM = 3;
    /** Avoid multiple loading of external resource */
    private static final Set<String> loaded = new HashSet<>();
    /** Logger */
    static Logger LOGGER = LoggerFactory.getLogger(FrDics.class.getName());
    /** Flag for compound, end of term */
    static final public int LEAF = 0x100;
    /** Flag for compound, to be continued */
    static final public int BRANCH = 0x200;
    /** 500 000 types French lexicon seems not too bad for memory */
    static final private HashMap<CharsAtt, LexEntry> WORDS = new HashMap<>((int) (500000 / 0.75));
    /** French names on which keep Capitalization */
    static final private HashMap<CharsAtt, LexEntry> NAMES = new HashMap<>((int) (50000 / 0.75));
    /** A tree to resolve compounds */
    static final HashMap<CharsAtt, Integer> TREELOC = new HashMap<>((int) (1500 / 0.75));
    /** Graphic normalization (replacement) */
    static final private Map<CharsAtt, CharsAtt> NORMALIZE = new HashMap<>((int) (100 / 0.75));
    /** Abbreviations with a final dot */
    static final private Set<CharsAtt> BREVIDOT = new HashSet<>((int) (200 / 0.75));
    /** current dictionnary loaded, for logging */
    static String res;
    
    
    /** Load dictionaries */
    static {
        // first word win
        String[] files = { 
            "locutions.csv", // compounds to decompose
            "caps.csv",      // normalization of initial capital without accent
            "orth.csv",      // normalisation of oe œ, and some other word
            "num.csv",       // normalisation of ordinals for centuries
            "brevidot.csv",  // abbreviations finishing by a dot
            "word.csv",      // the big dic, before some names
            "author.csv",    // well known authorities (writers)
            "name.csv",      // other proper names
            "forename.csv",  // foreName with gender
            "place.csv",     // world place name 
            "france.csv",    // places in France
            "commune.csv"    // french town, at the end
        };
        for (String f : files) {
            loadResource(f, f);
        }
    }
    
    /**
     * Avoid instantiation, use static method instead.
     */
    private FrDics()
    {
        
    }

    /**
     * Test if the requested chars are a known abbreviation ending by a dot.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return true if submitted form is an abbreviation, false otherwise.
     */
    public static boolean isBrevidot(CharsAttImpl att)
    {
        return BREVIDOT.contains(att);
    }

    /**
     * Verify that a dictionary is already loaded.
     * @param key a local identifying name for a dictionary
     * @return
     */
    public static boolean contains(final String key)
    {
        return loaded.contains(key);
    }

    /**
     * Insert a compound candidate in the compound tree
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
                key = new CharsAttImpl(form.array(), 0, i + 1);
            else if (c == ' ')
                key = new CharsAttImpl(form.array(), 0, i);
            else continue;
            Integer entry = tree.get(key);
            if (entry == null)
                tree.put(key, BRANCH);
            else
                tree.put(key, entry | BRANCH);
        }
        // end of word
        CharsAttImpl key = new CharsAttImpl(form.array(), 0, len);
        Integer entry = tree.get(key);
        if (entry == null)
            tree.put(key, LEAF);
        else
            tree.put(key, entry | LEAF);
    }

    /**
     * Load a jar resource as dictionary.
     * 
     * @param res resource path according to the class loader.
     */
    private static void loadResource(final String key, final String res)
    {
        FrDics.res = res;
        Reader reader = new InputStreamReader(TagFr.class.getResourceAsStream(res), StandardCharsets.UTF_8);
        load(key, reader, false);
    }
    
    /**
     * Load a file as a dictionary.
     * 
     * @param file file path.
     * @throws IOException file errors.
     */
    public static void load(final String key, final File file) throws IOException
    {
        FrDics.res = file.getAbsolutePath();
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        // default is replace
        load(key, reader, true);
    }

    /**
     * Insert a local csv (comma separated values) dictionary of 4 cols:
     * <ul>
     * <li>0. GRAPH. Required, graphical form used as a key (could be a lemma for
     * verbs in locutions like “avoir l’air”).</li>
     * <li>1. TAG. Required, morpho-syntaxic code.</li>
     * <li>2. LEM. Optional, lemmatization.</li>
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
            csv = new CSVReader(reader, 4);
            csv.readRow(); // skip first line
            Row row;
            while ((row = csv.readRow()) != null) {
                Chain graph = row.get(GRAPH);
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
                // do not handle multi word abbreviation like "av. J.-C."
                if (!hasSpace && graph.last() == '.') {
                    for (int length = 2; length <= graph.length() ; length++) {
                        if (graph.charAt(length - 1) != '.') continue;
                        CharsAttImpl key = new CharsAttImpl(graph, 0, length);
                        BREVIDOT.add(key);
                    }
                }
                // check if it is normalization
                Chain norm = row.get(NORM);
                if (!norm.isEmpty()) {
                    NORMALIZE.put(new CharsAttImpl(graph), new CharsAttImpl(norm));
                    continue;
                }

                
                // not replace, internal lexicons, first value win
                if (!replace) {
                    if (WORDS.containsKey(graph) || NAMES.containsKey(graph)) continue;
                }
                // replace, especially for user files
                else {
                    // deletion of a form starting by 0
                    if (graph.first() == '0') {
                        graph.firstDel();
                        WORDS.remove(graph);
                        NAMES.remove(graph);
                        continue;
                    }
                    // remove previous versions (ex : Russes => Russie)
                    WORDS.remove(graph);
                    NAMES.remove(graph);
                }
                // a lexical entry
                LexEntry entry = new LexEntry(graph, row.get(TAG), row.get(LEM));
                if (graph.isFirstUpper())
                    NAMES.put(new CharsAttImpl(graph), entry);
                else
                    WORDS.put(new CharsAttImpl(graph), entry);
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
     * Simple load of table equivalent
     * 
     * @param reader
     * @param map
     */
    private static void load(final String res, final HashMap<CharsAtt, CharsAtt> map)
    {
        FrDics.res = res;
        Reader reader = new InputStreamReader(TagFr.class.getResourceAsStream(res), StandardCharsets.UTF_8);
        CSVReader csv = null;
        try {
            csv = new CSVReader(reader, 2);
            csv.readRow(); // skip first line
            Row row;
            while ((row = csv.readRow()) != null) {
                Chain key = row.get(0);
                if (key.isEmpty() || key.charAt(0) == '#')
                    continue;
                Chain value = row.get(1);
                // if (value.isEmpty()) continue; // a value maybe empty
                map.put(new CharsAttImpl(key), new CharsAttImpl(value));
            }
            reader.close();
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
     * Get a dictionary entry from the name dictionary
     * with a reusable {@link CharTermAttribute} implementation.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available proper name entry for the submitted form, or null if not found.
     */
    public static LexEntry name(CharsAtt att)
    {
        return NAMES.get(att);
    }

    /**
     * Get normalized orthographic form for a real grapphical form in text.
     * 
     * @param att {@link CharTermAttribute} implementation, normalized.
     * @return true if a normalization has been done, false otherwise.
     */
    public static boolean norm(CharsAtt att)
    {
        CharsAtt val = NORMALIZE.get(att);
        if (val == null)
            return false;
        att.setEmpty().append(val);
        return true;
    }

    /**
     * Get a dictionary entry from the word dictionary
     * with a reusable {@link CharTermAttribute} implementation.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available common word entry for the submitted form, or null if not found.
     */
    public static LexEntry word(CharsAtt att)
    {
        return WORDS.get(att);
    }

    /**
     * An entry for a dictionary te get lemma from
     * an inflected form.
     */
    public static class LexEntry
    {
        /** Inflected form.  */
        final public CharsAttImpl graph;
        /** A lexical word type. */
        final public int tag;
        /** lemma form. */
        final public CharsAttImpl lem;

        /**
         * Full constructor with cells coming from a {@link CSVReader}
         * 
         * @param graph graphical form found in texts.
         * @param tag short name for a lexical type.
         * @param graph normalized orthographic form.
         * @param lem lemma form.
         */
        public LexEntry(final Chain graph, final Chain tag, final Chain lem) {
            if (graph.isEmpty() || tag.isEmpty()) {
                LOGGER.debug(res + " graph=" + graph + " tag=" + tag);
            }
            graph.replace('’', '\'');
            this.tag = TagFr.NULL.no(tag.toString());
            this.graph = new CharsAttImpl(graph);
            if (lem == null || lem.isEmpty()) {
                this.lem = null;
            }
            else {
                lem.replace('’', '\'');
                this.lem = new CharsAttImpl(lem);
            }
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(TagFr.NULL.name(this.tag));
            if (graph != null)
                sb.append(" graph=").append(graph);
            if (lem != null)
                sb.append(" lem=").append(lem);
            // if (branch) sb.append(" BRANCH");
            // if (leaf) sb.append(" LEAF");
            // sb.append("\n");
            return sb.toString();
        }
    }

}
