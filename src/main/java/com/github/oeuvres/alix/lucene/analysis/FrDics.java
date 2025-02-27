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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.util.WordsAutomatonBuilder;
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
    /** Logger */
    static Logger LOGGER = Logger.getLogger(FrDics.class.getName());
    /** Flag for compound, end of term */
    static final public int LEAF = 0x100;
    /** Flag for compound, to be continued */
    static final public int BRANCH = 0x200;
    /** French stopwords as hash to filter attributes */
    static final public HashSet<CharsAttImpl> STOP = new HashSet<CharsAttImpl>((int) (1000 / 0.75));
    /** French stopwords as binary automaton */
    public static ByteRunAutomaton STOP_BYTES;
    /** 130 000 types French lexicon seems not too bad for memory */
    static final public HashMap<CharsAttImpl, LexEntry> WORDS = new HashMap<CharsAttImpl, LexEntry>((int) (150000 / 0.75));
    /** French names on which keep Capitalization */
    static final public HashMap<CharsAttImpl, LexEntry> NAMES = new HashMap<CharsAttImpl, LexEntry>((int) (50000 / 0.75));
    /** A tree to resolve compounds */
    static final public HashMap<CharsAttImpl, Integer> TREELOC = new HashMap<CharsAttImpl, Integer>((int) (1500 / 0.75));
    /** Graphic normalization (replacement) */
    static final public HashMap<CharsAttImpl, CharsAttImpl> NORM = new HashMap<CharsAttImpl, CharsAttImpl>((int) (100 / 0.75));
    /** Abbreviations with a final dot */
    static final public HashMap<CharsAttImpl, CharsAttImpl> BREVIDOT = new HashMap<CharsAttImpl, CharsAttImpl>((int) (100 / 0.75));
    /** current dictionnary loaded, for logging */
    static String res;
    /** Load dictionaries */
    static {
        CSVReader csv = null;
        Reader reader;
        try {
            ArrayList<String> list = new ArrayList<String>();
            // unmodifiable map with jdk10 Map.copyOf is not faster
            // add common col separator, the csv parser is not very robust
            STOP.add(new CharsAttImpl(";"));
            list.add(";");
            STOP.add(new CharsAttImpl(","));
            list.add(",");
            STOP.add(new CharsAttImpl("\t"));
            list.add("\t");
            res = "stop.csv";
            reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
            csv = new CSVReader(reader, 1);
            csv.readRow(); // pass first line
            Row row;
            while ((row = csv.readRow()) != null) {
                Chain cell0 = row.get(0);
                if (cell0.isEmpty() || cell0.charAt(0) == '#')
                    continue;
                STOP.add(new CharsAttImpl(cell0));
                list.add(cell0.toString());
            }
            Automaton automaton = WordsAutomatonBuilder.buildFronStrings(list);
            STOP_BYTES = new ByteRunAutomaton(automaton);

            res = "word.csv";
            reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
            csv = new CSVReader(reader, 6);
            csv.readRow(); // pass first line
            while ((row = csv.readRow()) != null) {
                Chain orth = row.get(0);
                if (orth.isEmpty() || orth.charAt(0) == '#')
                    continue;
                // keep first key
                if (WORDS.containsKey(orth))
                    continue;
                CharsAttImpl key = new CharsAttImpl(orth);
                WORDS.put(key, new LexEntry(row.get(0), row.get(1), null, row.get(2)));
            }
            // nouns, put persons after places (Molière is also a village, but not very
            // common)
            String[] files = { "commune.csv", "france.csv", "forename.csv", "place.csv", "author.csv", "name.csv" };
            for (String f : files) {
                res = f;
                InputStream is = Tag.class.getResourceAsStream(res);
                if (is == null)
                    throw new FileNotFoundException("Unfound resource " + res);
                reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                csv = new CSVReader(reader, 4); // some names may have a kind of lemma
                csv.readRow();
                while ((row = csv.readRow()) != null) {
                    Chain graph = row.get(0);
                    if (graph.isEmpty() || graph.charAt(0) == '#')
                        continue;
                    LexEntry entry = new LexEntry(row.get(0), row.get(1), row.get(2), null);
                    NAMES.put(new CharsAttImpl(graph), entry);
                    if (graph.contains(' ')) {
                        compound(graph, TREELOC);
                    }
                }
                csv.close();
            }
        }
        // output errors at start
        catch (Exception e) {
            if (csv == null) {
                System.out.println("Dictionary parse error in file " + res);
            } else {
                System.out.println("Dictionary parse error in file " + res + " line " + csv.line());
            }
            e.printStackTrace();
        }
        try {
            load("caps.csv", NORM);
            load("orth.csv", NORM);
            load("brevidot.csv", BREVIDOT);
            locutions("locutions.csv");
            load("num.csv");
        } catch (Exception e) {
            e.printStackTrace();
        }
        /*
         * File zejar = new
         * File(FrDics.class.getProtectionDomain().getCodeSource().getLocation().getPath
         * ()); File localdic = new File(zejar.getParentFile(), "alix-cloud.csv"); if
         * (localdic.exists()) load(localdic, LOCAL);
         */
    }

    /**
     * Avoid instantiation, use static method instead.
     */
    private FrDics()
    {
        
    }
    /**
     * Load a jar resource as dictionary.
     * 
     * @param res resource path according to the class loader.
     */
    private static void load(final String res)
    {
        FrDics.res = res;
        Reader reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
        load(reader);
    }

    /**
     * Load a file as a dictionary.
     * 
     * @param file file path.
     * @throws IOException file errors.
     */
    public static void load(final File file) throws IOException
    {
        FrDics.res = file.getAbsolutePath();
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        load(reader);
    }

    /**
     * Insert a local csv (comma separated values) dictionary of 4 cols:
     * <ul>
     * <li>0. GRAPH. Required, graphical form used as a key (could be a lemma for
     * verbs in locutions like “avoir l’air”).</li>
     * <li>1. TAG. Required, morpho-syntaxic code.</li>
     * <li>2. ORTH. Optional, form normalization.</li>
     * <li>3. LEM. Optional, lemmatization.</li>
     * </ul>
     * 
     * @param reader for file or jar.
     */
    static public void load(final Reader reader)
    {
        CSVReader csv = null;
        try {
            csv = new CSVReader(reader, 4);
            csv.readRow(); // skip first line
            Row row;
            while ((row = csv.readRow()) != null) {
                Chain graph = row.get(0);
                if (graph.isEmpty() || graph.charAt(0) == '#')
                    continue;
                // entry to remove
                if (graph.first() == '-') {
                    graph.firstDel();
                    WORDS.remove(graph);
                    NAMES.remove(graph);
                    continue;
                }
                // remove other versions (ex : Russes => Russie)
                WORDS.remove(graph);
                NAMES.remove(graph);
                CharsAttImpl key = new CharsAttImpl(graph);
                LexEntry entry = new LexEntry(row.get(0), row.get(1), row.get(2), row.get(3));
                if (graph.isFirstUpper())
                    NAMES.put(key, entry);
                else
                    WORDS.put(key, entry);
                if (graph.contains(' '))
                    compound(graph, TREELOC);
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

    private static void load(final String res, final HashMap<CharsAttImpl, CharsAttImpl> map) throws FileNotFoundException
    {
        InputStream stream = Tag.class.getResourceAsStream(res);
        if (stream == null) {
            throw new FileNotFoundException("Resource not found: " + Tag.class.getPackageName() + "/" + res);
        }
        Reader reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
        load(reader, map);
    }

    /**
     * Simple load of table equivalent
     * 
     * @param reader
     * @param map
     */
    private static void load(final Reader reader, final HashMap<CharsAttImpl, CharsAttImpl> map)
    {
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

    private static void locutions(final String res)
    {
        Reader reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
        locutions(reader);
    }

    /**
     * Insert a csv table known to be a series of multi token locutions.
     * <li>0. GRAPH. Required, graphical form used as a key (could be a lemma for
     * verbs like “avoir l’air”).
     * <li>1. TAG. Required, morpho-syntaxic code
     * <li>2. ORTH. Optional, form normalization
     * 
     * @param reader
     * @param map
     */
    private static void locutions(final Reader reader)
    {
        CSVReader csv = null;
        try {
            csv = new CSVReader(reader, 4);
            csv.readRow(); // skip first line
            Row row;
            while ((row = csv.readRow()) != null) {
                Chain graph = row.get(0);
                if (graph.isEmpty() || graph.charAt(0) == '#')
                    continue;
                // load the form in the compound tree if it is multi token (badly sometimes not)
                if (graph.contains(' ') || graph.contains('’') || graph.contains('\''))
                    compound(graph, TREELOC);
                // load the word in the global dic (last win)
                CharsAttImpl key = new CharsAttImpl(graph);
                Chain orth = row.get(2);
                LexEntry entry = new LexEntry(row.get(0), row.get(1), orth, row.get(3));
                // entry may be known by normalized key only
                if (Tag.NAME.sameParent(entry.tag)) {
                    NAMES.put(key, entry);
                    if (orth != null && !NAMES.containsKey(orth))
                        NAMES.put(new CharsAttImpl(orth), entry);
                } else {
                    WORDS.put(key, entry);
                    if (orth != null && !WORDS.containsKey(orth))
                        WORDS.put(new CharsAttImpl(orth), entry);
                }
            }
            reader.close();
        } catch (Exception e) {
            System.out.print("Dictionary parse error in " + reader);
            if (csv != null)
                System.out.println(" line " + csv.line());
            else
                System.out.println();
            e.printStackTrace();
        }
    }

    /**
     * Insert a compound candidate in the compound tree
     * 
     * @param form a form to insert
     * @param tree the tree to insert in.
     */
    protected static void compound(Chain form, HashMap<CharsAttImpl, Integer> tree)
    {
        int len = form.length();
        for (int i = 0; i < len; i++) {
            char c = form.charAt(i);
            if (c != '\'' && c != '’' && c != ' ')
                continue;
            if (c == '’')
                form.setCharAt(i, '\'');
            CharsAttImpl key;
            if (c == '\'' || c == '’')
                key = new CharsAttImpl(form.array(), 0, i + 1);
            else if (c == ' ')
                key = new CharsAttImpl(form.array(), 0, i);
            else
                continue;
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
     * Get a dictionary entry from the word dictionary
     * with a reusable {@link CharTermAttribute} implementation.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available common word entry for the submitted form, or null if not found.
     */
    public static LexEntry word(CharsAttImpl att)
    {
        return WORDS.get(att);
    }

    /**
     * Get a dictionary entry from the name dictionary
     * with a reusable {@link CharTermAttribute} implementation.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return available proper name entry for the submitted form, or null if not found.
     */
    public static LexEntry name(CharsAttImpl att)
    {
        return NAMES.get(att);
    }

    /**
     * Test if the requested chars are in the stop word dictionary.
     * 
     * @param ref lucene bytes.
     * @return true if submitted form is a stop word, false otherwise.
     */
    public static boolean isStop(BytesRef ref)
    {
        return STOP_BYTES.run(ref.bytes, ref.offset, ref.length);
    }

    /**
     * Test if the requested chars are in the stop word dictionary.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return true if submitted form is a stop word, false otherwise.
     */
    public static boolean isStop(CharsAttImpl att)
    {
        return STOP.contains(att);
    }

    /**
     * Test if the requested chars are a known abbreviation ending by a dot.
     * 
     * @param att {@link CharTermAttribute} implementation.
     * @return true if submitted form is an abbreviation, false otherwise.
     */
    public static boolean brevidot(CharsAttImpl att)
    {
        CharsAttImpl val = BREVIDOT.get(att);
        if (val == null)
            return false;
        if (!val.isEmpty())
            att.copy(val);
        return true;
    }

    /**
     * Get normalized orthographic form for a real grapphical form in text.
     * 
     * @param att {@link CharTermAttribute} implementation, normalized.
     * @return true if a normalization has been done, false otherwise.
     */
    public static boolean norm(CharsAttImpl att)
    {
        CharsAttImpl val = NORM.get(att);
        if (val == null)
            return false;
        att.setEmpty().append(val);
        return true;
    }

    /**
     * An entry for a dictionary te get lemma from
     * an inflected form.
     */
    public static class LexEntry
    {
        /** A lexical word type. */
        final public int tag;
        /** Inflected form.  */
        final public CharsAttImpl orth;
        /** lemma form. */
        final public CharsAttImpl lem;

        /**
         * Full constructor with cells coming from a {@link CSVReader}
         * 
         * @param graph graphical form found in texts.
         * @param tag short name for a lexical type.
         * @param orth normalized orthographic form.
         * @param lem lemma form.
         */
        public LexEntry(final Chain graph, final Chain tag, final Chain orth, final Chain lem) {
            if (graph.isEmpty() || tag.isEmpty()) {
                LOGGER.log(Level.FINEST, res + " graph=" + graph + " tag=" + tag);
            }
            this.tag = Tag.flag(tag);
            if (orth == null || orth.isEmpty())
                this.orth = null;
            else
                this.orth = new CharsAttImpl(orth);
            if (lem == null || lem.isEmpty())
                this.lem = null;
            else
                this.lem = new CharsAttImpl(lem);
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(Tag.name(this.tag));
            if (orth != null)
                sb.append(" orth=").append(orth);
            if (lem != null)
                sb.append(" lem=").append(lem);
            // if (branch) sb.append(" BRANCH");
            // if (leaf) sb.append(" LEAF");
            // sb.append("\n");
            return sb.toString();
        }
    }

}
