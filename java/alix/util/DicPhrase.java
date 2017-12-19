package alix.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;

/**
 * A data structure to store multi words expression.
 * 
 * @author glorieux-f
 *
 */
public class DicPhrase
{
  /** Count of nodes */
  private long occs = 1;
  /** Access by phrase */
  private HashMap<IntTuple, Ref> tupleDic = new HashMap<IntTuple, Ref>();
  /**
   * A local mutable Phrase for testing in the Map of phrases, efficient but not
   * thread safe
   */
  private IntSeries buffer = new IntSeries();
  /** A local mutable String for locution insertion, not thread safe */
  private Term token = new Term();

  public boolean add(final DicFreq words, String compound)
  {
    return add(words, compound, -1);
  }

  /**
   * Add a compound to the dictionary of compounds
   * 
   * @param compound
   *          space separated words
   */
  public boolean add(final DicFreq words, String compound, final int senselevel)
  {
    if (compound == null)
      return false;
    compound = compound.trim();
    if (compound.startsWith("#"))
      return false;
    // parse the term, split on space and apos
    final int lim = compound.length();
    if (lim == 0)
      return false;
    int code;
    IntSeries buffer = this.buffer; // take a ref to avoid a lookup
    buffer.reset();
    Term token = this.token.reset(); // a temp mutable string
    for (int i = 0; i < lim; i++) {
      char c = compound.charAt(i);
      if (c == '’')
        c = '\'';
      // split on apos or hyphen
      if (c == '’' || c == '\'' || Char.isSpace(c) || c == '-' || c == '\t' || c == ';' || i == lim - 1) {
        if (c == '’' || c == '\'' || i == lim - 1)
          token.append(c);
        code = words.inc(token);
        if (code > senselevel)
          buffer.push(code);
        token.reset();
        if (c == '\t' || c == ';')
          break;
      }
      else {
        token.append(c);
      }
    }
    // ?? allow simple words ?
    if (buffer.size() < 1)
      return false;
    // Add phrase to dictionary
    inc(buffer);
    // here we could add more info on the compound
    return true;
  }

  public long occs()
  {
    return occs;
  }

  public long size()
  {
    return tupleDic.size();
  }

  public int inc(final IntSeries key)
  {
    Ref ref = tupleDic.get(key);
    occs++;
    if (ref == null) {
      ref = new Ref(1);
      IntTuple tuple = new IntTuple(key);
      tupleDic.put(tuple, ref);
      return 1;
    }
    return ref.inc();
  }

  public int inc(final IntRoller key)
  {
    Ref ref = tupleDic.get(key);
    occs++;
    if (ref == null) {
      ref = new Ref(1);
      IntTuple tuple = new IntTuple(key);
      tupleDic.put(tuple, ref);
      return 1;
    }
    return ref.inc();
  }

  /**
   * 
   * @param buffer
   * @return ???
   */
  public void label(final IntSeries key, final String label)
  {
    Ref ref = tupleDic.get(key);
    if (ref == null)
      return; // create it ?
    ref.label = label;
  }

  public void label(final IntRoller key, final String label)
  {
    Ref ref = tupleDic.get(key);
    if (ref == null)
      return; // create it ?
    ref.label = label;
  }

  public boolean contains(final IntSeries key)
  {
    Ref ref = tupleDic.get(key);
    if (ref == null)
      return false;
    return true;
  }

  public boolean contains(final IntRoller win)
  {
    Ref ref = tupleDic.get(win);
    if (ref == null)
      return false;
    return true;
  }

  public Iterator<Map.Entry<IntTuple, Ref>> freqlist()
  {
    List<Map.Entry<IntTuple, Ref>> list = new LinkedList<Map.Entry<IntTuple, Ref>>(tupleDic.entrySet());
    Collections.sort(list, new Comparator<Map.Entry<IntTuple, Ref>>() {
      @Override
      public int compare(Map.Entry<IntTuple, Ref> o1, Map.Entry<IntTuple, Ref> o2)
      {
        return (o2.getValue().count - o1.getValue().count);
      }
    });
    return list.iterator();
  }

  public void html(final Writer writer, final int limit, final DicFreq words) throws IOException
  {
    print(writer, limit, words, true);
  }

  public void print(final Writer writer, final int limit, final DicFreq words) throws IOException
  {
    print(writer, limit, words, false);
  }

  public void print(final Writer writer, int limit, final DicFreq words, boolean html) throws IOException
  {
    Iterator<Entry<IntTuple, Ref>> it = freqlist();
    Map.Entry<IntTuple, Ref> entry;
    String label;
    while (it.hasNext()) {
      entry = it.next();
      label = entry.getValue().label;
      writer.write("\n");
      if (label != null)
        writer.write(label);
      else
        writer.write(entry.getKey().toString());
      writer.write(" (" + entry.getValue().count() + ")");
      if (html)
        writer.write("<br/>");
      if (--limit == 0)
        break;
    }
    writer.flush();
  }

  /**
   * References for a phrase We can more things like file byte
   * 
   * @author user
   *
   */
  public class Ref
  {
    private int count;
    private String label;

    public Ref(final String label) {
      this.label = label;
    }

    public Ref(final int count) {
      this.count = count;
    }

    public int inc()
    {
      return ++count;
    }

    public int count()
    {
      return count;
    }
  }

  public static void main(String[] args) throws IOException
  {
    final String dir = "../alix-demo/WEB-INF/textes/";
    // final String dir="../textes/";
    // final Pattern filematch = Pattern.compile("millet_vie-sexuelle.xml");
    // final Pattern filematch = Pattern.compile("proust_recherche.xml");
    // final Pattern filematch = Pattern.compile("dumas.xml");
    // final Pattern filematch = Pattern.compile("galland_1001nuits.xml");
    final Pattern filematch = Pattern.compile("larsson_millenium.xml");
    // final Pattern filematch = Pattern.compile("zola.*.xml");
    // final Pattern filematch = Pattern.compile("james-el_50-nuances.xml");
    final int size = 2; // taille des expressions
    boolean locs = true;

    IntRoller gram = new IntRoller(0, size - 1); // collocation wheel
    IntRoller wordmarks = new IntRoller(0, size - 1); // positions of words recorded in the collocation key

    DicFreq dic = new DicFreq();
    DicPhrase phrases = new DicPhrase();

    int NAME = dic.inc("NOM");
    int NUM = dic.inc("NUM");
    BufferedReader buf = new BufferedReader(
        new InputStreamReader(Lexik.class.getResourceAsStream("dic/stop.csv"), StandardCharsets.UTF_8));
    String l;
    // define a "sense level" in the dictionary, by inserting a stoplist at first
    int senselevel = -1;
    while ((l = buf.readLine()) != null) {
      int code = dic.inc(l.trim());
      if (code > senselevel)
        senselevel = code;
    }
    buf.close();
    // add some more words to the stoplits
    for (String w : new String[] { "chère", "dire", "dis", "dit", "jeune", "jeunes", "yeux" }) {
      int code = dic.inc(w);
      if (code > senselevel)
        senselevel = code;
    }

    IntRoller wordflow = new IntRoller(-15, 0);
    int code;
    int exit = 1000;
    StringBuffer label = new StringBuffer();
    for (File src : new File(dir).listFiles()) {
      if (src.isDirectory())
        continue;
      if (src.getName().startsWith("."))
        continue;
      if (!filematch.matcher(src.getName()).matches())
        continue;
      if (!src.getName().endsWith(".txt") && !src.getName().endsWith(".xml"))
        continue;
      System.out.println(src);
      String xml = new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8);
      int pos = xml.indexOf("</teiHeader>");
      if (pos < 0)
        pos = 0;
      Occ occ = new Occ(); // pointer on current occurrence in the tokenizer flow
      Tokenizer toks = new Tokenizer(xml);
      while (true) {
        if (locs) {
          occ = toks.word();
          if (occ == null)
            break;
        }
        else {
          if (!toks.token(occ))
            break;
        }
        // clear after sentences
        if (occ.tag().equals(Tag.PUNsent)) {
          wordflow.clear();
          gram.clear();
          wordmarks.clear();
          continue;
        }

        if (occ.tag().isPun())
          continue; // do not record punctuation
        else if (occ.tag().isName())
          code = NAME; // simplify names
        else if (occ.tag().isNum())
          code = NUM; // simplify names
        else if (occ.tag().isVerb())
          code = dic.inc(occ.lem());
        else
          code = dic.inc(occ.orth());
        // clear to avoid repetitions
        // « Voulez vous sortir, grand pied de grue, grand pied de grue, grand pied de
        // grue »
        if (code == wordflow.first()) {
          wordflow.clear();
          gram.clear();
          wordmarks.clear();
          continue;
        }

        wordflow.push(code); // add this token to the word flow
        wordmarks.dec(); // decrement positions of the recorded plain words
        if (wordflow.get(0) <= senselevel)
          continue; // do not record empty words
        wordmarks.push(0); // record a new position of full word
        gram.push(wordflow.get(0)); // store a signficant word as a collocation key
        if (gram.get(0) == 0)
          continue; // the collocation key is not complete

        int count = phrases.inc(gram);
        // new value, add a label to the collocation
        if (count == 1) {
          label.setLength(0);
          for (int i = wordmarks.get(0); i <= 0; i++) {
            label.append(dic.label(wordflow.get(i)));
            if (i != 0 && label.charAt(label.length() - 1) != '\'')
              label.append(" ");
          }
          // System.out.println( label );
          phrases.label(gram, label.toString());
        }
        // if ( --exit < 0 ) System.exit( 1 );
      }
    }
    System.out.println("Parsé");
    System.out.println(phrases.tupleDic.size() + " ngrams");
    phrases.print(new PrintWriter(System.out), 1000, dic);
  }
}
