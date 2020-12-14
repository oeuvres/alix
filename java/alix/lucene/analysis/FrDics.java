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


import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
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
  /** French stopwords as hash to filter attributes */
  public final static HashSet<CharsAtt> STOP = new HashSet<CharsAtt>((int) (1000 / 0.75));
  /** French stopwords as binary automaton */
  public static ByteRunAutomaton STOP_BYTES;
  /** 130 000 types French lexicon seems not too bad for memory */
  public final static HashMap<CharsAtt, LexEntry> WORD = new HashMap<CharsAtt, LexEntry>((int) (150000 / 0.75));
  /** French names on which keep Capitalization */
  public final static HashMap<CharsAtt, NameEntry> NAME = new HashMap<CharsAtt, NameEntry>((int) (50000 / 0.75));
  /** Graphic normalization (replacement) */
  public final static HashMap<CharsAtt, CharsAtt> NORM = new HashMap<CharsAtt, CharsAtt>((int) (100 / 0.75));
  /** Elisions, for tokenization and normalization */
  public final static HashMap<CharsAtt, CharsAtt> ELISION = new HashMap<CharsAtt, CharsAtt>((int) (30 / 0.75));
  /** Abbreviations with a final dot */
  public final static HashMap<CharsAtt, CharsAtt> BREVIDOT = new HashMap<CharsAtt, CharsAtt>((int) (100 / 0.75));
  /** First word of a compound */
  public final static HashMap<CharsAtt, Integer> COMPOUND = new HashMap<CharsAtt, Integer>();
  /** In a compound tree, say it’s not finished */
  public final static int BRANCH = 0x10000;
  /** In a compound tree, say it could be end of a word (but also could continue) */
  public final static int LEAF = 0x20000;
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
      while (csv.readRow()) {
        Chain cell0 = csv.row().get(0);
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
      while (csv.readRow()) {
        Chain orth = csv.row().get(0);
        if (orth.isEmpty() || orth.charAt(0) == '#') continue;
        if (WORD.containsKey(orth)) continue;
        CharsAtt key = new CharsAtt(orth);
        WORD.put(key, new LexEntry(csv.row().get(1), csv.row().get(2), csv.row().get(5)));
      }
      // nouns, put persons after places (Molière is also a village, but not very common)
      String[] files = {"commune.csv", "france.csv", "forename.csv", "place.csv", "author.csv", "name.csv"};
      for (String f : files) {
        res = f;
        reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
        csv = new CsvReader(reader, 3);
        csv.readRow();
        while (csv.readRow()) {
          Chain key = csv.row().get(0);
          if (key.isEmpty() || key.charAt(0) == '#') continue;
          NAME.put(new CharsAtt(key), new NameEntry(csv.row().get(1), csv.row().get(2)));
        }
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
    tree("compound.csv", COMPOUND);
  }

  private static void load(String res, HashMap<CharsAtt, CharsAtt> map)
  {
    CsvReader csv = null;
    try {
      Reader reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 2);
      csv.readRow(); // skip first line
      while (csv.readRow()) {
        Chain key = csv.row().get(0);
        if (key.isEmpty() || key.charAt(0) == '#') continue;
        Chain value = csv.row().get(1);
        // if (value.isEmpty()) continue; // a value maybe empty
        map.put(new CharsAtt(key), new CharsAtt(value));
      }
    }
    catch (Exception e) {
      System.out.println("Dictionary parse error in file "+res);
      if (csv != null) System.out.println(" line "+csv.line());
      else System.out.println();
      e.printStackTrace();
    }
  }
  
  public static void tree(String res, HashMap<CharsAtt, Integer> map)
  {
    CsvReader csv = null;
    try {
      Reader reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 2);
      csv.readRow(); // skip first line
      while (csv.readRow()) {
        Chain word = csv.row().get(0);
        if (word.isEmpty() || word.charAt(0) == '#') continue;
        int len = word.length();
        for(int i = 0; i < len; i++) {
          char c = word.charAt(i);
          if (c != '\'' && c != '’' && c != ' ') continue;
          CharsAtt key = null;
          if (c == '’') word.setCharAt(i, '\'');
          if (c == '\'' || c == '’') key = new CharsAtt(word.array(), 0, i+1);
          else if (c == ' ') key = new CharsAtt(word.array(), 0, i);
          Integer value = map.get(key);
          if (value == null) {
            value = BRANCH;
            map.put(key, value);
          }
          else map.put(key, value | BRANCH);
        }
        CharsAtt key = new CharsAtt(word.array(), 0, len);
        Integer value = Tag.code(csv.row().get(1)) | LEAF;
        map.put(key, value);
      }
    }
    catch (Exception e) {
      System.out.print("Dictionary parse error in file "+res);
      if (csv != null) System.out.println(" line "+csv.line());
      else System.out.println();
      e.printStackTrace();
    }
  }
  
  public static LexEntry word(CharsAtt att)
  {
    return WORD.get(att);
  }
  public static NameEntry name(CharsAtt att)
  {
    return NAME.get(att);
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

  public static class NameEntry
  {
    public final CharsAtt orth;
    public final int tag;

    public NameEntry(final Chain tag, final Chain orth)
    {
      int code = Tag.code(tag);
      if (code == 0) this.tag = Tag.NAME;
      else this.tag = code;
      if (!orth.isEmpty()) this.orth = new CharsAtt(orth);
      else this.orth = null;
    }

    @Override
    public String toString()
    {
      return Tag.label(tag);
    }
  }

  public static class LexEntry
  {
    public final int tag;
    public final CharsAtt lem;
    public float freq = 3;

    public LexEntry(final Chain tag, final Chain lem, final Chain freq) throws ParseException
    {
      this.tag = Tag.code(tag);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new CharsAtt(lem);
      if (!freq.isEmpty()) try {
        this.freq = Float.parseFloat(freq.toString());
      }
      catch (NumberFormatException e) {
      }
    }

    @Override
    public String toString()
    {
      if (lem != null) return Tag.label(this.tag)+"_"+lem;
      return Tag.label(this.tag);
    }
  }

}
