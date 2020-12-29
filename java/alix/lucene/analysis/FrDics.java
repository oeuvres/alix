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
package alix.lucene.analysis;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.util.WordsAutomatonBuilder;
import alix.util.Chain;
import alix.util.CsvReader;
import alix.util.CsvReader.Row;


/**
 * Preloaded word List for lucene indexation in {@link HashMap}.
 * Efficiency strongly rely on a custom 
 * implementation of chars token attribute {@link CharsAtt},
 * with a cached hash code {@link CharsAtt#hashCode()} and
 * comparison {@link CharsAtt#compareTo(CharsAtt)}.
 */
@SuppressWarnings("unlikely-arg-type")
public class FrDics
{
  /** Flag for compound, end of term */
  static final public int LEAF = 0x100;
  /** Flag for compound, to be continued */
  static final public int BRANCH = 0x200;
  /** French stopwords as hash to filter attributes */
  static final public HashSet<CharsAtt> STOP = new HashSet<CharsAtt>((int) (1000 / 0.75));
  /** French stopwords as binary automaton */
  public static ByteRunAutomaton STOP_BYTES;
  /** 130 000 types French lexicon seems not too bad for memory */
  static final public HashMap<CharsAtt, LexEntry> WORDS = new HashMap<CharsAtt, LexEntry>((int) (150000 / 0.75));
  /** French names on which keep Capitalization */
  static final public HashMap<CharsAtt, LexEntry> NAMES = new HashMap<CharsAtt, LexEntry>((int) (50000 / 0.75));
  /** A tree to resolve compounds */
  static final public HashMap<CharsAtt, Integer> TREELOC = new HashMap<CharsAtt, Integer>((int) (1500 / 0.75));
  /** Graphic normalization (replacement) */
  static final public HashMap<CharsAtt, CharsAtt> NORM = new HashMap<CharsAtt, CharsAtt>((int) (100 / 0.75));
  /** Final local replacement */
  // public final static HashMap<CharsAtt, CharsAtt> LOCAL = new HashMap<CharsAtt, CharsAtt>((int) (100 / 0.75));
  /** Elisions, for tokenization and normalization */
  static final public HashMap<CharsAtt, CharsAtt> ELISION = new HashMap<CharsAtt, CharsAtt>((int) (30 / 0.75));
  /** Abbreviations with a final dot */
  static final public HashMap<CharsAtt, CharsAtt> BREVIDOT = new HashMap<CharsAtt, CharsAtt>((int) (100 / 0.75));
  /** Load dictionaries */
  static {
    String res = null;
    CsvReader csv = null;
    Reader reader;
    try {
      // unmodifiable map with jdk10 Map.copyOf is not faster
      STOP.add(new CharsAtt(";"));
      res = "stop.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 1);
      csv.readRow(); // pass first line
      ArrayList<String> list = new ArrayList<String>();
      Row row;
      while ((row = csv.readRow()) != null) {
        Chain cell0 = row.get(0);
        if (cell0.isEmpty() || cell0.charAt(0) == '#') continue;
        STOP.add(new CharsAtt(cell0));
        list.add(cell0.toString());
      }
      Automaton automaton = WordsAutomatonBuilder.buildFronStrings(list);
      STOP_BYTES = new ByteRunAutomaton(automaton);

      
      res = "word.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 6);
      csv.readRow(); // pass first line
      while ((row = csv.readRow()) != null) {
        Chain orth = row.get(0);
        if (orth.isEmpty() || orth.charAt(0) == '#') continue;
        // keep first key
        if (WORDS.containsKey(orth)) continue;
        CharsAtt key = new CharsAtt(orth);
        WORDS.put(key, new LexEntry(row.get(1), null, row.get(2)));
      }
      // nouns, put persons after places (Molière is also a village, but not very common)
      String[] files = {"commune.csv", "france.csv", "forename.csv", "place.csv", "author.csv", "name.csv"};
      for (String f : files) {
        res = f;
        InputStream is = Tag.class.getResourceAsStream(res);
        if (is == null) throw new FileNotFoundException("Unfound resource "+ res);
        reader = new InputStreamReader(is, StandardCharsets.UTF_8);
        csv = new CsvReader(reader, 3);
        csv.readRow();
        while ((row = csv.readRow()) != null) {
          Chain graph = row.get(0);
          if (graph.isEmpty() || graph.charAt(0) == '#') continue;
          LexEntry entry = new LexEntry(row.get(1), row.get(2), null);
          NAMES.put(new CharsAtt(graph), entry);
          if (graph.contains(' ')) compound(graph, TREELOC);
        }
        csv.close();
      }
    }
    // output errors at start
    catch (Exception e) {
      System.out.println("Dictionary parse error in file "+res+" line "+csv.line());
      e.printStackTrace();
    }
    load("caps.csv", NORM);
    load("orth.csv", NORM);
    load("ellision.csv", ELISION);
    load("brevidot.csv", BREVIDOT);
    locutions("locutions.csv");
    /*
    File zejar = new File(FrDics.class.getProtectionDomain().getCodeSource().getLocation().getPath());
    File localdic = new File(zejar.getParentFile(), "alix-cloud.csv");
    if (localdic.exists()) load(localdic, LOCAL);
    */
  }

  /** 
   * Insert a local csv dictionary of 4 cols
   * <li>0. GRAPH. Required, graphical form used as a key (could be a lemma for verbs like “avoir l’air”).
   * <li>1. TAG. Required, morpho-syntaxic code
   * <li>2. ORTH. Optional, form normalization
   * <li>3. LEM. Optional, local prefered lemmatization
   * @throws IOException 
   * @throws ParseException 
   */
  static public void load(final File file) throws IOException
  {
    CsvReader csv = null;
    csv = new CsvReader(file, 4);
    csv.readRow(); // skip first line
    Row row;
    while ((row = csv.readRow()) != null) {
      Chain graph = row.get(0);
      if (graph.isEmpty() || graph.charAt(0) == '#') continue;
      // entry to remove
      if (graph.first() == '-') {
        graph.firstDel();
        WORDS.remove(graph);
        NAMES.remove(graph);
        continue;
      }
      // don’t mind if exists, will be replaced
      CharsAtt key = new CharsAtt(graph);
      LexEntry entry = new LexEntry(row.get(1), row.get(2), row.get(3));
      if (graph.isFirstUpper()) NAMES.put(key, entry);
      else WORDS.put(key, entry);
      if (graph.contains(' ')) compound(graph, TREELOC);
    }
    csv.close();
  }

  private static void load(final String res, final HashMap<CharsAtt, CharsAtt> map)
  {
    Reader reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
    load(reader, map);
  }

  
  private static void load(final Reader reader, final HashMap<CharsAtt, CharsAtt> map)
  {
    CsvReader csv = null;
    try {
      csv = new CsvReader(reader, 2);
      csv.readRow(); // skip first line
      Row row;
      while ((row = csv.readRow()) != null) {
        Chain key = row.get(0);
        if (key.isEmpty() || key.charAt(0) == '#') continue;
        Chain value = row.get(1);
        // if (value.isEmpty()) continue; // a value maybe empty
        map.put(new CharsAtt(key), new CharsAtt(value));
      }
      reader.close();
    }
    catch (Exception e) {
      System.out.println("Dictionary parse error in file "+reader);
      if (csv != null) System.out.println(" line "+csv.line());
      else System.out.println();
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
   * <li>0. GRAPH. Required, graphical form used as a key (could be a lemma for verbs like “avoir l’air”).
   * <li>1. TAG. Required, morpho-syntaxic code
   * <li>2. ORTH. Optional, form normalization
   * 
   * @param reader
   * @param map
   */
  private static void locutions(final Reader reader)
  {
    CsvReader csv = null;
    try {
      csv = new CsvReader(reader, 3);
      csv.readRow(); // skip first line
      Row row;
      while ((row = csv.readRow()) != null) {
        Chain graph = row.get(0);
        if (graph.isEmpty() || graph.charAt(0) == '#') continue;
        // load the form in the compound tree
        compound(graph, TREELOC);
        // load the word in the global dic (last win)
        int tag = Tag.code(row.get(1));
        CharsAtt key = new CharsAtt(graph);
        Chain orth = row.get(2);
        LexEntry entry = new LexEntry(tag, orth, null);
        // entry may be known by normalized key only
        if (Tag.isName(tag)) {
          NAMES.put(key, entry);
          if (orth != null && !NAMES.containsKey(orth)) NAMES.put(new CharsAtt(orth), entry);
        }
        else {
          WORDS.put(key, entry);
          if (orth != null && !WORDS.containsKey(orth)) WORDS.put(new CharsAtt(orth), entry);
        }
      }
      reader.close();
    }
    catch (Exception e) {
      System.out.print("Dictionary parse error in "+reader);
      if (csv != null) System.out.println(" line "+csv.line());
      else System.out.println();
      e.printStackTrace();
    }
  }
  
  /**
   * Insert a compound candidate in the compound tree
   * @param graph
   */
  protected static void compound(Chain graph, HashMap<CharsAtt, Integer> tree) 
  {
    int len = graph.length();
    for(int i = 0; i < len; i++) {
      char c = graph.charAt(i);
      if (c != '\'' && c != '’' && c != ' ') continue;
      if (c == '’') graph.setCharAt(i, '\'');
      CharsAtt key;
      if (c == '\'' || c == '’') key = new CharsAtt(graph.array(), 0, i+1);
      else if (c == ' ') key = new CharsAtt(graph.array(), 0, i);
      else continue;
      Integer entry = tree.get(key);
      if (entry == null) tree.put(key, BRANCH);
      else tree.put(key, entry | BRANCH);
    }
    // end of word
    CharsAtt key = new CharsAtt(graph.array(), 0, len);
    Integer entry = tree.get(key);
    if (entry == null) tree.put(key, LEAF);
    else tree.put(key, entry | LEAF);
  }
  
  public static LexEntry word(CharsAtt att)
  {
    return WORDS.get(att);
  }
  /**
   * Not efficient for a lot of queries.
   * @param form
   * @return
   */
  public static LexEntry word(String form)
  {
    return WORDS.get(new CharsAtt(form));
  }
  public static LexEntry name(CharsAtt att)
  {
    return NAMES.get(att);
  }
  /**
   * Not efficient for a lot of queries.
   * @param form
   * @return
   */
  public static LexEntry name(String form)
  {
    return NAMES.get(new CharsAtt(form));
  }
  public static boolean isStop(BytesRef ref)
  {
    return STOP_BYTES.run(ref.bytes, ref.offset, ref.length);
  }
  
  public static boolean isStop(CharsAtt att)
  {
    return STOP.contains(att);
  }

  public static boolean brevidot(CharsAtt att)
  {
    CharsAtt val = BREVIDOT.get(att);
    if (val == null) return false;
    if (!val.isEmpty()) att.copy(val);
    return true;
  }

  public static boolean norm(CharsAtt att)
  {
    CharsAtt val = NORM.get(att);
    if (val == null) return false;
    att.setEmpty().append(val);
    return true;
  }


  public static class LexEntry
  {
    final public int tag;
    final public CharsAtt orth;
    final public CharsAtt lem;


    public LexEntry(final Chain tag)
    {
      this.tag = Tag.code(tag);
      orth = null;
      lem = null;
    }

    public LexEntry(final Chain tag, final Chain orth, final Chain lem) 
    {
      this.tag = Tag.code(tag);
      if (orth == null || orth.isEmpty()) this.orth = null;
      else this.orth = new CharsAtt(orth);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new CharsAtt(lem);
    }

    public LexEntry(final int tag, final Chain orth, final Chain lem)
    {
      this.tag = tag;
      if (orth == null || orth.isEmpty()) this.orth = null;
      else this.orth = new CharsAtt(orth);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new CharsAtt(lem);
    }

    
    /*
    public LexEntry(final Chain tag, final Chain lem, final Chain lemfreq) throws ParseException
    {
      this(tag, lem);
      if (!lemfreq.isEmpty()) try {
        this.lemfreq = Float.parseFloat(lemfreq.toString());
      }
      catch (NumberFormatException e) {
      }
    }
    */

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      sb.append(Tag.label(this.tag));
      if (orth != null) sb.append(" orth=").append(orth);
      if (lem != null) sb.append(" lem=").append(lem);
      // if (branch) sb.append(" BRANCH");
      // if (leaf) sb.append(" LEAF");
      // sb.append("\n");
      return sb.toString();
    }
  }

}
