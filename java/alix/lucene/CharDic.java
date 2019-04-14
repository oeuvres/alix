package alix.lucene;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import alix.fr.dic.Tag;
import alix.lucene.CharDic.LexEntry;
import alix.util.Chain;
import alix.util.CsvReader;
import alix.util.Occ;
import alix.util.StemTrie;


/**
 * Preloaded word List
 * 
 * @author glorieux-f
 *
 */
public class CharDic
{
  /** French stopwords */
  public final static HashSet<CharAtt> STOP = new HashSet<CharAtt>((int) (700 * 0.75));
  /** 130 000 types French lexicon seems not too bad for memory */
  public final static HashMap<CharAtt, LexEntry> WORD = new HashMap<CharAtt, LexEntry>((int) (150000 * 0.75));
  /** French names on which keep Capitalization */
  public final static HashMap<CharAtt, NameEntry> NAME = new HashMap<CharAtt, NameEntry>((int) (50000 * 0.75));
  /** Graphic normalization (replacement) */
  public final static HashMap<CharAtt, CharAtt> NORM = new HashMap<CharAtt, CharAtt>((int) (100 * 0.75));
  /** Ellisions, for tokenization and normalisation */
  public final static HashMap<CharAtt, CharAtt> ELLISION = new HashMap<CharAtt, CharAtt>((int) (30 * 0.75));
  /** Abbreviations with a final dot */
  // protected static HashMap<String, String> BREVIDOT = new HashMap<String, String>((int) (100 * 0.75));
  /* Load dictionaries */
  static {
    String file = null;
    CsvReader csv = null;
    try {
      Reader reader;
      // unmodifiable map with jdk10 Map.copyOf is not faster
      STOP.add(new CharAtt(";"));
      file = "stop.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 1);
      csv.readRow(); // pass first line
      while (csv.readRow()) {
        Chain cell0 = csv.row().get(0);
        if (cell0.isEmpty() || cell0.charAt(0) == '#') continue;
        STOP.add(new CharAtt(cell0));
      }
      
      file = "word.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 3);
      csv.readRow(); // pass first line
      while (csv.readRow()) {
        Chain orth = csv.row().get(0);
        if (orth.isEmpty() || orth.charAt(0) == '#') continue;
        if (WORD.containsKey(orth)) continue;
        CharAtt key = new CharAtt(orth);
        WORD.put(key, new LexEntry(csv.row().get(1), csv.row().get(2)));
      }
      // nouns, put persons after places (Molière is also a village, but not very common)
      String[] files = {"commune.csv", "france.csv", "forename.csv", "place.csv", "author.csv", "name.csv"};
      CharAtt alain = new CharAtt("Alain");
      for (String f : files) {
        file = f;
        reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
        csv = new CsvReader(reader, 3);
        csv.readRow();
        while (csv.readRow()) {
          Chain cell = csv.row().get(0);
          if (cell.isEmpty() || cell.charAt(0) == '#') continue;
          NAME.put(new CharAtt(cell), new NameEntry(csv.row().get(1), csv.row().get(2)));
        }
      }
      String[] list = {"caps.csv", "orth.csv"};
      for (String f : list) {
        file = f;
        reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
        csv = new CsvReader(reader, 2);
        csv.readRow();
        while (csv.readRow()) {
          Chain cell = csv.row().get(0);
          if (cell.isEmpty() || cell.charAt(0) == '#') continue;
          NORM.put(new CharAtt(cell), new CharAtt(csv.row().get(1)));
        }
      }
      file = "ellision.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 2);
      csv.readRow();
      while (csv.readRow()) {
        Chain cell = csv.row().get(0);
        if (cell.isEmpty() || cell.charAt(0) == '#') continue;
        ELLISION.put(new CharAtt(cell), new CharAtt(csv.row().get(1)));
      }
    }
    // output errors at start
    catch (Exception e) {
      System.out.println("Dictionary parse error in file "+file+" line "+csv.line());
      e.printStackTrace();
    }
  }
  public static LexEntry word(CharAtt att)
  {
    return WORD.get(att);
  }
  public static NameEntry name(CharAtt att)
  {
    return NAME.get(att);
  }

  /*
  public static boolean norm(CharAtt att)
  {
    String val = NORM.get(att);
    if (val == null) return false;
    att.setEmpty().append(val);
  }
  */

  public static class NameEntry
  {
    public final CharAtt orth;
    public final short tag;

    public NameEntry(final Chain tag, final Chain orth)
    {
      short code = Tag.code(tag);
      if (code == 0) this.tag = Tag.NAME;
      else this.tag = code;
      if (!orth.isEmpty()) this.orth = new CharAtt(orth);
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
    public final short tag;
    public final CharAtt lem;

    public LexEntry(final Chain tag, final Chain lem) throws ParseException
    {
      this.tag = Tag.code(tag);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new CharAtt(lem);
    }

    public LexEntry(final String tag, final String lem)
    {
      this.tag = Tag.code(tag);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new CharAtt(lem);
    }

    @Override
    public String toString()
    {
      if (lem != null) return Tag.label(this.tag)+"_"+lem;
      return Tag.label(this.tag);
    }
  }

  /**
   * For testing
   * 
   * @throws ParseException
   * @throws URISyntaxException
   */
  public static void main(String[] args) throws IOException, ParseException, URISyntaxException
  {
    // Load wor dic
  }
}
