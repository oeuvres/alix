package alix.frdo;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.LocutionFilter;
import alix.lucene.analysis.FrDics;
import alix.lucene.analysis.FrLemFilter;
import alix.lucene.analysis.FrPersnameFilter;
import alix.lucene.analysis.FrTokenizer;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;
import alix.util.Dir;
import alix.util.IntPair;
import alix.util.Top;

/**
 * Extract all “expressions” (bien aller, une fois pour toutes) from a set of files,
 * used to find 
 * 
 * @author glorieux-f
 *
 */
public class Expressions
{
  /** Data structure to record expression by a pair of ints */
  private HashMap<IntPair, Counter> locutions = new HashMap<IntPair, Counter>();
  /** Auto increment id */
  private int autoid = 0;
  /** Data structure to get an int id for a term */
  private HashMap<CharsAtt,Integer> dicByChars = new HashMap<CharsAtt,Integer>();
  /** Data structure to get back a term by id */
  private ArrayList<CharsAtt> dicById = new ArrayList<CharsAtt>();
  /** The best Alix analyzer, with names and compounds */
  Analyzer analyzer = new MyAnalyzer();
  /** Testing tags ? */
  TagFilter tagfilter = new TagFilter()
    .setGroup(Tag.SUB).setGroup(Tag.ADJ)
    .setGroup(Tag.VERB).clear(Tag.VERBaux).clear(Tag.VERBsup)
    .setGroup(Tag.NAME).set(Tag.NULL)
  ;
  
  public class MyAnalyzer extends Analyzer
  {
    /** 
     * Force creation of a new token stream pipeline, for multi threads 
     * indexing.
     */
    
    @Override
    public TokenStreamComponents createComponents(String field)
    {
      final Tokenizer source = new FrTokenizer(); // segment words
      TokenStream result = new FrLemFilter(source); // provide lemma+pos
      result = new FrPersnameFilter(result); // link names: V. Hugo
      result = new LocutionFilter(result); // compounds: parce que
      return new TokenStreamComponents(source, result);
    }
  }
  
  class Counter
  {
    int count = 1;
    public int inc()
    {
      return ++count;
    }
  }
  
  class TokPair
  {
    /** id */
    int id = -1;
    /** chached pair */
    IntPair pair = new IntPair();

    public void reset()
    {
      id = -1;
    }
    public void add(final CharsAtt chars) {
      // last token set, add a pair
      if (id >= 0) {
        // keep lastid
        int lastid = this.id;
        // set newid
        this.id = id(chars);
        pair.set(lastid, this.id);
        Counter counter = locutions.get(pair);
        if (counter == null) {
          locutions.put(new IntPair(pair), new Counter());
        }
        else {
          counter.inc();
        }
          
      }
      else {
        this.id = id(chars);
      }
    }
    /** Set word */
    private int id(final CharsAtt chars)
    {
      final int id;
      Integer o = dicByChars.get(chars);
      // new node
      if (o == null) {
        id = autoid++;
        CharsAtt key = new CharsAtt(chars);
        dicByChars.put(key, id);
        // 
        if (dicById.size() == id) dicById.add(key);
        else {
          System.out.println(id+" Should never arrive");
          dicById.add(id, key);
        }
      }
      else {
        id = o.intValue();
      }
      return id;
    }

  }

  public void parse(final String text) throws IOException
  {
    parse(text, false);
  }

  public void parse(final String text, final boolean debug) throws IOException
  {
    TokenStream stream = analyzer.tokenStream("expressions", new StringReader(text));

    // get the CharTermAttribute from the TokenStream
    CharsAtt term = (CharsAtt)stream.addAttribute(CharTermAttribute.class);
    CharsAtt orth = (CharsAtt)stream.addAttribute(CharsOrthAtt.class);
    CharsAtt lem = (CharsAtt)stream.addAttribute(CharsLemAtt.class);
    FlagsAttribute flags = stream.addAttribute(FlagsAttribute.class);
    OffsetAttribute offsets = stream.addAttribute(OffsetAttribute.class);
    TokPair pair = new TokPair();
    stream.reset();
    while (stream.incrementToken()) {
      if (debug) {
        System.out.println(
          // "<li>"
          term 
          + "\t" + orth  
          + "\t" + Tag.label(flags.getFlags())
          + "\t" + lem  
          + " |" + text.substring(offsets.startOffset(), offsets.endOffset()) + "|"
          // + "</li>"
        );
      }

      int tag = flags.getFlags();
      if (Tag.isPun(tag)) {
        pair.reset();
        continue;
      }
      if (FrDics.isStop(orth)) continue;
      // if (Tag.isVerb(tag)) continue;
      
      
      // if (!tagfilter.accept(tag)) continue;
      if (Tag.isVerb(tag) && lem.length() > 0) pair.add(lem);
      else if (Tag.isVerb(tag)) { // infinitif
        pair.add(orth);
      }
      // else if (lem.length() > 0) pair.add(lem);
      else if (orth.length() > 0) pair.add(orth);
      else pair.add(term);
    }
    stream.close();
    
  }
  
  public void glob(String glob) throws IOException
  {
    List<File> ls = Dir.ls(glob);
    for (File file : ls) {
      System.out.println(file);
      String text = Files.readString(file.toPath());
      parse(text);
    }
  }
  
  public String top(final int limit) throws IOException
  {
    StringBuilder sb = new StringBuilder();
    Top<IntPair> top= new Top<IntPair>(limit);
    for (Map.Entry<IntPair, Counter> entry: locutions.entrySet()) {
      top.push(entry.getValue().count, entry.getKey());
    }
    for (Top.Entry<IntPair> entry: top) {
      IntPair pair = entry.value();
      sb
        .append(dicById.get(pair.x()))
        .append(" ")
        .append(dicById.get(pair.y()))
        .append(" (")
        .append((int)entry.score())
        .append(")\n")
      ;
    }
    return sb.toString();
  }
  
  
  public static void main(String[] args) throws IOException
  {
    Expressions collocs = new Expressions();

    collocs.parse("Le xx<sup>e</sup> siècle.", true);
    System.out.println(collocs.top(10));
    
    // collocs.glob("/var/www/html/ddr-livres/*.xml");
    // System.out.println(collocs.top(100));
  }
  
}
