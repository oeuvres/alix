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
package alix.deprecated;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;

import alix.fr.Tag;
import alix.util.Chain;
import alix.util.Char;

/**
 * 
 * A Trie of words to store compounds expressions with different properties
 */
public class StemTrie
{
  /** Root node */
  final Stem root = new Stem();
  /** French, « j’aime », break apostrophe after those words */
  public static final HashSet<String> ELLISION = new HashSet<String>();
  static {
    for (String w : new String[] { "c'", "C'", "d'", "D'", "j'", "J'", "jusqu'", "Jusqu'", "l'", "L'", "lorsqu'",
        "Lorsqu'", "m'", "M'", "n'", "N'", "puisqu'", "Puisqu'", "qu'", "Qu'", "quoiqu'", "Quoiqu'", "s'", "S'", "t'",
        "-t'", "T'" })
      ELLISION.add(w);
  }

  /**
   * Empty constructor
   */
  public StemTrie()
  {
  }

  public void loadFile(final String path, final String separator) throws IOException
  {
    InputStream stream = new FileInputStream(path);
    load(stream, separator);
  }

  public void loadRes(final String path, final String separator) throws IOException
  {
    InputStream stream = Tokenizer.class.getResourceAsStream(path);
    load(stream, separator);
  }

  /**
   * Load a list of compounds from a stream
   * 
   * @param stream
   * @param separator
   *          a character (efficient) or maybe a Regexp
   * @throws IOException
   */
  public void load(InputStream stream, String separator) throws IOException
  {
    String line;
    BufferedReader buf = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    buf.readLine(); // first line is labels and should give number of cells to find
    String[] cells;
    while ((line = buf.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.charAt(0) == '#') continue;
      cells = line.split(separator);
    }
    buf.close();
  }

  /**
   * Load a line of a csv file in format {"a compound expression", "TAG"}
   * 
   * @param cells
   * @return
   */
  public boolean add(String[] cells)
  {
    int cat;
    if (cells == null) return false;
    if (cells.length == 0) return false;
    if (cells[0] == null || cells[0].isEmpty()) return false;
    if (cells.length == 1) {
      add(cells[0], Tag.UNKNOWN);
      return true;
    }
    cat = Tag.code(cells[1]);
    if (cells.length == 2) {
      add(cells[0], cat);
      return true;
    }
    if (cells[2] == null || cells[2].isEmpty()) add(cells[0], cat);
    else add(cells[0], cat, cells[2]);
    return true;
  }

  /**
   * Add a chain to the dictionary of compounds
   * 
   * @param term
   *          space separated words
   */
  public void add(final String term)
  {
    add(term, Tag.UNKNOWN, null);
  }

  /**
   * Add a chain to the dictionary of compounds
   * 
   * @param term
   *          space separated words
   */
  public void add(final String term, final int cat)
  {
    add(term, cat, null);
  }

  /**
   * Add a chain to the dictionary of compounds
   * 
   * @param term
   *          space separated words
   * @param cat
   *          grammatical category code
   */
  public void add(String term, int cat, String orth)
  {
    Stem node = getRoot();
    // parse the chain, split on space and apos
    char[] chars = term.toCharArray();
    int lim = chars.length;
    char c;
    int offset = 0;
    String token;
    for (int i = 0; i < lim; i++) {
      c = chars[i];
      // split on apos ?
      if (c == '’' || c == '\'') {
        chars[i] = '\'';
        token = new String(chars, offset, i - offset + 1);
        if (!ELLISION.contains(token)) continue;
        node = node.append(token);
        offset = i + 1;
      }
      // and space (be nice on double spaces, do not create empty words)
      if (Char.isSpace(c)) {
        token = new String(chars, offset, i - offset);
        if (offset != i) node = node.append(token);
        offset = i + 1;
      }
    }
    // last word, trim spaces
    if (offset != lim) {
      token = new String(chars, offset, lim - offset);
      node = node.append(token);
    }
    node.inc(); // update counter (this structure may be used as chain counter)
    node.tag(cat); // a category
    if (orth != null) node.orth(orth);
    // else node.orth( chain ); // Bad for names NAME NAME
  }
  /**
   * Populate dictionary with a list of multi-words search
   * 
   * @param lexicon
   */
  /*
   * public TermTrie(Chain[] lexicon) { /* char c; TermNode node; for (String word
   * : lexicon) { node = root; for (int i = 0; i < word.length(); i++) { c =
   * word.charAt( i ); node = node.add( c ); } node.incWord(); } }
   */
  /**
   * Test if dictionary contains a chain
   * 
   * @param chain
   * @return
   */
  /*
   * public boolean contains(Chain chain) { char c; node = root; for (int i = 0; i
   * < chain.length(); i++) { c = chain.charAt( i ); node = node.test( c ); if
   * (node == null) return false; } if (node == null) return false; else if
   * (node.wc < 1) return false; else return true;
   * 
   * }
   */

  /**
   * Give Root to allow free exploration of tree
   */
  public Stem getRoot()
  {
    return root;
  }

  @Override
  public String toString()
  {
    return root.toString();
  }

  public class Stem
  {
    /** List of children */
    private HashMap<String, Stem> children;
    /** Word count, incremented each time the same compound is added */
    private int count;
    /** Grammatical category */
    private short tag;
    /** A correct graphical form for this token sequence */
    private String orth;

    /** Constructor */
    public Stem()
    {
      // this.word = word;
    }

    /**
     * Increment the word count
     * 
     * @return actual count
     */
    public int inc()
    {
      return ++this.count;
    }

    /**
     * Increment word count
     * 
     * @param add
     * @return actual count
     */
    public int inc(final int add)
    {
      return this.count += add;
    }

    /**
     * Set a grammatical category for this node
     * 
     * @param cat
     *          a grammatical category code
     */
    public Stem tag(final int cat)
    {
      this.tag = (short) cat;
      return this;
    }

    /**
     * Give a grammatical category for this node
     * 
     * @return the category
     */
    public short tag()
    {
      return this.tag;
    }

    /**
     * Set a normalized graphical version for the chain
     * 
     * @return the category
     */
    public Stem orth(final String orth)
    {
      this.orth = orth;
      return this;
    }

    /**
     * Give a normalized graphical version for the chain
     * 
     * @return the category
     */
    public String orth()
    {
      return this.orth;
    }

    /**
     * Append a token to this one
     * 
     * @param form
     */
    public Stem append(String form)
    {
      // String map = mapper.get( form );
      // if ( map != null ) form = map;
      Stem child = null;
      if (children == null) children = new HashMap<String, Stem>();
      else child = children.get(form);
      if (child == null) {
        child = new Stem();
        children.put(form, child);
      }
      return child;
    }

    /**
     * Have an handle on next token
     * 
     * @param form
     * @return
     */
    public Stem get(String form)
    {
      // String map = mapper.get( form );
      // if ( map != null ) form = map;
      if (children == null) return null;
      return children.get(form);
    }

    @SuppressWarnings("unlikely-arg-type")
    public Stem get(Chain form)
    {
      // String map = mapper.get( form );
      // if ( map != null ) form.replace( map );
      if (children == null) return null;
      return children.get(form);
    }

    /**
     * Recursive toString
     */
    @Override
    public String toString()
    {
      StringBuffer sb = new StringBuffer();
      if (tag != 0) sb.append("<" + Tag.label(tag) + ">");
      if (orth != null) sb.append(orth);
      if (count > 1) sb.append('(').append(count).append(')');
      if (children == null) return sb.toString();
      sb.append(" { ");
      boolean first = true;
      for (String w : children.keySet()) {
        if (first) first = false;
        else sb.append(", ");
        sb.append(w);
        sb.append(children.get(w));
      }
      sb.append(" }");
      return sb.toString();
    }
  }


}
