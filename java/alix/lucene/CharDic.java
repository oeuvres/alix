package alix.lucene;


import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;

import alix.fr.dic.Tag;
import alix.util.Chain;
import alix.util.CsvReader;


/**
 * Preloaded word List for lucene indexation.
 * 
 * @author glorieux-f
 *
 */
public class CharDic
{
  /** French stopwords */
  public final static HashSet<TokenAttChar> STOP = new HashSet<TokenAttChar>((int) (700 * 0.75));
  /** 130 000 types French lexicon seems not too bad for memory */
  public final static HashMap<TokenAttChar, LexEntry> WORD = new HashMap<TokenAttChar, LexEntry>((int) (150000 * 0.75));
  /** French names on which keep Capitalization */
  public final static HashMap<TokenAttChar, NameEntry> NAME = new HashMap<TokenAttChar, NameEntry>((int) (50000 * 0.75));
  /** Graphic normalization (replacement) */
  public final static HashMap<TokenAttChar, TokenAttChar> NORM = new HashMap<TokenAttChar, TokenAttChar>((int) (100 * 0.75));
  /** Ellisions, for tokenization and normalisation */
  public final static HashMap<TokenAttChar, TokenAttChar> ELLISION = new HashMap<TokenAttChar, TokenAttChar>((int) (30 * 0.75));
  /** Abbreviations with a final dot */
  // protected static HashMap<String, String> BREVIDOT = new HashMap<String, String>((int) (100 * 0.75));
  /* Load dictionaries */
  static {
    String file = null;
    CsvReader csv = null;
    try {
      Reader reader;
      // unmodifiable map with jdk10 Map.copyOf is not faster
      STOP.add(new TokenAttChar(";"));
      file = "stop.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 1);
      csv.readRow(); // pass first line
      while (csv.readRow()) {
        Chain cell0 = csv.row().get(0);
        if (cell0.isEmpty() || cell0.charAt(0) == '#') continue;
        STOP.add(new TokenAttChar(cell0));
      }
      
      file = "word.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 3);
      csv.readRow(); // pass first line
      while (csv.readRow()) {
        Chain orth = csv.row().get(0);
        if (orth.isEmpty() || orth.charAt(0) == '#') continue;
        if (WORD.containsKey(orth)) continue;
        TokenAttChar key = new TokenAttChar(orth);
        WORD.put(key, new LexEntry(csv.row().get(1), csv.row().get(2)));
      }
      // nouns, put persons after places (Molière is also a village, but not very common)
      String[] files = {"commune.csv", "france.csv", "forename.csv", "place.csv", "author.csv", "name.csv"};
      TokenAttChar alain = new TokenAttChar("Alain");
      for (String f : files) {
        file = f;
        reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
        csv = new CsvReader(reader, 3);
        csv.readRow();
        while (csv.readRow()) {
          Chain cell = csv.row().get(0);
          if (cell.isEmpty() || cell.charAt(0) == '#') continue;
          NAME.put(new TokenAttChar(cell), new NameEntry(csv.row().get(1), csv.row().get(2)));
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
          NORM.put(new TokenAttChar(cell), new TokenAttChar(csv.row().get(1)));
        }
      }
      file = "ellision.csv";
      reader = new InputStreamReader(Tag.class.getResourceAsStream(file), StandardCharsets.UTF_8);
      csv = new CsvReader(reader, 2);
      csv.readRow();
      while (csv.readRow()) {
        Chain cell = csv.row().get(0);
        if (cell.isEmpty() || cell.charAt(0) == '#') continue;
        ELLISION.put(new TokenAttChar(cell), new TokenAttChar(csv.row().get(1)));
      }
    }
    // output errors at start
    catch (Exception e) {
      System.out.println("Dictionary parse error in file "+file+" line "+csv.line());
      e.printStackTrace();
    }
  }
  public static LexEntry word(TokenAttChar att)
  {
    return WORD.get(att);
  }
  public static NameEntry name(TokenAttChar att)
  {
    return NAME.get(att);
  }

  public static boolean norm(TokenAttChar att)
  {
    TokenAttChar val = NORM.get(att);
    if (val == null) return false;
    att.setEmpty().append(val);
    return true;
  }

  public static class NameEntry
  {
    public final TokenAttChar orth;
    public final short tag;

    public NameEntry(final Chain tag, final Chain orth)
    {
      short code = Tag.code(tag);
      if (code == 0) this.tag = Tag.NAME;
      else this.tag = code;
      if (!orth.isEmpty()) this.orth = new TokenAttChar(orth);
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
    public final TokenAttChar lem;

    public LexEntry(final Chain tag, final Chain lem) throws ParseException
    {
      this.tag = Tag.code(tag);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new TokenAttChar(lem);
    }

    public LexEntry(final String tag, final String lem)
    {
      this.tag = Tag.code(tag);
      if (lem == null || lem.isEmpty()) this.lem = null;
      else this.lem = new TokenAttChar(lem);
    }

    @Override
    public String toString()
    {
      if (lem != null) return Tag.label(this.tag)+"_"+lem;
      return Tag.label(this.tag);
    }
  }

}
