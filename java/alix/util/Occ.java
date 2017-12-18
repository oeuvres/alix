package alix.util;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import alix.fr.Tag;

/**
 * A Token in a text flow with different properties. A token should allow to
 * write a nice concordance.
 * 
 * @author glorieux
 *
 */
public class Occ
{
  /**
   * Graphical form like encountered, caps/min, ellisions, could be used for a
   * correct concordancer
   */
  private Term graph = new Term();
  /** Orthographic form, normalized graphical form */
  private Term orth = new Term();
  /** Grammatical category */
  private Tag tag = new Tag();
  /** Lemma form */
  private Term lem = new Term();
  /** An index */
  private int n = -1;
  /** Char index in the source file of the first char of the token */
  private int start = 0;
  /** Char index in the source file */
  private int end = 0;
  /** Link to next Occurrence if used in a linked list */
  private Occ next;
  /** Link to previous Occurrence if used in a linked list */
  private Occ prev;
  /** Pointer to an OccChain if Occ is inside it */
  protected OccChain chain;

  /**
   * Empty constructor
   */
  public Occ() {

  }

  /**
   * Copy an occurrence
   */
  public Occ(Occ occ) {
    set(occ);
  }

  /**
   * Constructor
   */
  public Occ(final CharSequence graph, final CharSequence orth, final int tag, final CharSequence lem) {
    graph(graph);
    orth(orth);
    tag(tag);
    lem(lem);
  }

  public Occ(final CharSequence graph, final CharSequence orth, final Tag tag, final CharSequence lem) {
    graph(graph);
    orth(orth);
    tag(tag);
    lem(lem);
  }

  /**
   * Constructor
   */
  public Occ(final Term graph, final Term orth, final Tag tag, final Term lem) {
    graph(graph);
    orth(orth);
    tag(tag);
    lem(lem);
  }

  /**
   * Replace occurrence values by another
   * 
   * @param occ
   * @return a handle on the Occurrence object for chaining
   */
  public Occ set(Occ occ)
  {
    graph.set(occ.graph);
    orth.set(occ.orth);
    tag.set(occ.tag);
    lem.set(occ.lem);
    start = occ.start;
    end = occ.end;
    // KEEP prev-next links of source, do not import them from target
    return this;
  }

  /**
   * Append an occurrence to make a compound word
   * 
   * @return
   */
  public Occ apend(Occ occ)
  {
    char c = 0;

    if (!graph.isEmpty())
      c = graph.last();
    if (c == '\'' || c == '’') {
      graph.append(occ.graph);
      // de abord > d’abord
      if (!orth.isEmpty() && orth.last() != '\'' && orth.last() != '’')
        orth.last('\'');
      orth.append(occ.orth);
      if (!lem.isEmpty() && lem.last() != '\'' && lem.last() != '’')
        lem.last('\'');
      if (!occ.lem().isEmpty())
        lem.append(occ.lem);
      // c’est-à-dire
      else
        lem.append(occ.orth);
    }
    else if (c == '-' || occ.graph.first() == '-') {
      graph.append(occ.graph);
      orth.append(occ.orth);
      lem.append(occ.lem);
    }
    else {
      graph.append(' ').append(occ.graph);
      orth.append(' ').append(occ.orth);
      lem.append(' ').append(occ.lem);
    }
    // no way to guess how cat will be changed
    if (tag.equals(0))
      tag(occ.tag);
    // if occurrence was empty, take the index value of new Occ
    if (start < 0)
      start = occ.start;
    end = occ.end;
    return this;
  }

  /**
   * Clear Occurrence of all information
   * 
   * @return a handle on the Occurrence object for chaining
   */
  public Occ clear()
  {
    graph.reset();
    orth.reset();
    lem.reset();
    tag.set(0);
    start = -1;
    end = -1;
    // DO NOT modify linking for OccChain
    return this;
  }

  /**
   * Is Occurrence with at least graph value set ?
   * 
   * @return
   */
  public boolean isEmpty()
  {
    return (graph.isEmpty() & orth.isEmpty());
  }

  /**
   * Returns the graph value
   * 
   * @return
   */
  public Term graph()
  {
    return graph;
  }

  /**
   * Set graph value by a String (or a mutable String)
   * 
   * @param cs
   * @return a handle on the occurrence object
   */
  public Occ graph(CharSequence cs)
  {
    graph.replace(cs);
    return this;
  }

  /**
   * Set graph value by a String (or a mutable String), with index
   * 
   * @param cs
   * @param from
   *          start index in the CharSequence
   * @param length
   *          number of chars from start
   * @return a handle on the occurrence
   */
  public Occ graph(CharSequence cs, int from, int length)
  {
    graph.replace(cs, from, length);
    return this;
  }

  /**
   * Set graph value by copy of a term
   * 
   * @param t
   * @return a handle on the occurrence
   */
  public Occ graph(Term t)
  {
    graph.set(t);
    return this;
  }

  /**
   * Returns the orth value
   * 
   * @return
   */
  public Term orth()
  {
    return orth;
  }

  /**
   * Set orth value by a String (or a mutable String)
   * 
   * @param cs
   * @return a handle on the occurrence
   */
  public Occ orth(CharSequence cs)
  {
    orth.replace(cs);
    return this;
  }

  /**
   * Set orth value by a String (or a mutable String), with index
   * 
   * @param cs
   * @param from
   *          start index in the CharSequence
   * @param length
   *          number of chars from start
   * @return a handle on the occurrence object
   */
  public Occ orth(CharSequence cs, int from, int length)
  {
    orth.replace(cs, from, length);
    return this;
  }

  /**
   * Set orth value by copy of a term
   * 
   * @param t
   * @return a handle on the occurrence object
   */
  public Occ orth(Term t)
  {
    orth.set(t);
    return this;
  }

  /**
   * Returns the tag value
   * 
   * @return
   */
  public Tag tag()
  {
    return tag;
  }

  /**
   * Set a grammar category code
   * 
   * @return a handle on the occurrence object for chaining
   */
  public Occ tag(final int code)
  {
    tag.set(code);
    return this;
  }

  /**
   * Set a grammar category code
   * 
   * @param tag
   * @return
   */
  public Occ tag(final Tag tag)
  {
    this.tag.set(tag);
    return this;
  }

  /**
   * Set a grammar category by label
   * 
   * @param tag
   * @return
   */
  public Occ tag(final String tag)
  {
    this.tag.set(tag);
    return this;
  }

  /**
   * Returns the lem value
   * 
   * @return
   */
  public Term lem()
  {
    return lem;
  }

  /**
   * Set lem value by copy of a term
   * 
   * @param t
   * @return a handle on the occurrence object
   */
  public Occ lem(final Term t)
  {
    lem.set(t);
    return this;
  }

  /**
   * Set lem value by copy of a String
   * 
   * @param t
   * @return a handle on the occurrence object
   */
  public Occ lem(final CharSequence cs)
  {
    lem.replace(cs);
    return this;
  }

  /**
   * Returns an order index
   * 
   * @return
   */
  public int n()
  {
    return n;
  }

  /**
   * Set a start pointer for the occurrence
   * 
   * @param i
   *          pointer, for example a char index in a String
   * @return
   */
  public Occ n(final int i)
  {
    this.n = i;
    return this;
  }

  /**
   * Returns the start index
   * 
   * @return
   */
  public int start()
  {
    return start;
  }

  /**
   * Set a start pointer for the occurrence
   * 
   * @param i
   *          pointer, for example a char index in a String
   * @return
   */
  public Occ start(final int i)
  {
    this.start = i;
    return this;
  }

  /**
   * Returns the start index
   * 
   * @return
   */
  public int end()
  {
    return end;
  }

  /**
   * Set an end pointer for the occurrence (last char + 1)
   * 
   * @param i
   *          pointer, for example a char index in a String
   * @return
   */
  public Occ end(final int i)
  {
    this.end = i;
    return this;
  }

  /**
   * Return a next Occ pointer if user have set one
   * 
   * @return
   */
  public Occ next()
  {
    return this.next;
  }

  /**
   * Set a next Occ pointer. Nothing returns, no relevant chaining.
   * 
   * @param occ
   */
  public void next(Occ occ)
  {
    this.next = occ;
  }

  /**
   * Return previous Occ if user have set one
   * 
   * @return
   */
  public Occ prev()
  {
    return this.prev;
  }

  /**
   * Set a previous occurrence
   * 
   * @param occ
   */
  public void prev(Occ occ)
  {
    this.prev = occ;
  }

  /**
   * A kind of equals, especially useful to test tag only
   */
  public boolean match(Occ occ)
  {

    // test Prefix only, is it dangerous ?
    if (tag.isPrefix() && lem.isEmpty() && orth.isEmpty() && tag.equals(occ.tag.prefix()))
      return true;
    if (!tag.isEmpty() && !tag.equals(occ.tag))
      return false;
    if (!lem.isEmpty() && !lem.equals(occ.lem))
      return false;
    if (!orth.isEmpty() && !orth.equals(occ.orth))
      return false;
    return true;
  }

  /**
   * Default String display
   */
  @Override
  public String toString()
  {
    return graph + "\t" + orth + "\t" + tag.label() + "\t" + lem + "\t" + start + "\t" + end;
  }

  /**
   * Test the Class
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String args[]) throws IOException
  {
    Occ test1 = new Occ(null, null, Tag.DET, null);
    Occ test2 = new Occ(null, null, Tag.DETart, null);
    String text = "Son:DETposs amant:SUB l':PRO emmène:VERB un:DETart jour:SUB ,:PUN O:NAME se:PRO promener:VERB"
        + " dans:CONJ un:DETart quartier:SUB où:PRO ?:PUNsent "
        + " remarquons:VERB -le:PRO ils:PROpers ne:ADVneg vont:VERB jamais:ADVneg se:PRO promener:VERB .:PUNsent";
    Occ occ = new Occ();
    PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, "UTF-8"));
    String[] parts;
    for (String tok : text.split(" ")) {
      if (tok.trim().isEmpty())
        continue;
      parts = tok.split(":");
      occ.graph(parts[0]).tag(parts[1]);
      System.out.println(occ);
      if (test1.match(occ))
        System.out.println(" —— fit( " + test1.tag + " )");
      if (test2.match(occ))
        System.out.println(" —— fit( " + test2.tag + " )");
    }
    out.close();
  }

}
