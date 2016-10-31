package site.oeuvres.fr;

import site.oeuvres.util.Term;

/**
 * A Token in a text flow with different properties.
 * A token should allow to write a nice concordance.
 * @author glorieux
 *
 */
public class Occ
{
  /** Graphical form like encountered, caps/min, ellisions, could be used for a correct concordancer */
  private Term graph = new Term();
  /** Orthographic form, normalized graphical form */
  private Term orth = new Term();
  /** Grammatical category */
  private short cat;
  /** Lemma form */
  private Term lem = new Term();
  /** Char index in the source file of the first char of the token */
  private int start = -1;
  /** Char index in the source file (-1) like for String.substring(start, end) */
  private int end = -1;
  /**
   * Empty constructor
   */
  public Occ()
  {
    
  }
  /**
   * Copy an occurrence
   */
  public Occ( Occ occ )
  {
    replace( occ );
  }
  /**
   * Replace occurrence values by another
   * @param occ
   * @return a handle on the Occurrence object for chaining
   */
  public Occ replace( Occ occ )
  {
    graph.replace( occ.graph );
    orth.replace( occ.orth );
    cat = occ.cat;
    lem.replace( occ.lem );
    start = occ.start;
    end = occ.end;
    return this;
  }
  /**
   * Append an occurrence to make a compound word
   * @return
   */
  public Occ apend( Occ occ )
  {
    char c;
    if ( !graph.isEmpty() ) {
      c = graph.last();
      if ( c != '\'' && c != '’')
        graph.append( ' ' );
    }
    if ( !orth.isEmpty() ) {
      c = orth.last();
      if ( c != '\'' && c != '’')
        orth.append( ' ' );
    }
    if ( !lem.isEmpty() ) {
      c = lem.last();
      if ( c != '\'' && c != '’')
        lem.append( ' ' );
    }
    graph.append( occ.graph );
    orth.append( occ.orth );
    lem.append( occ.lem );
    // no way to guess how cat will be changed
    if ( cat == 0 ) cat = occ.cat;
    // if occurrence was empty, take the index value of new Occ
    if ( start < 0 ) start = occ.start;
    end = occ.end;
    return this;
  }
  /**
   * Clear Occurrence of all information
   * @return a handle on the Occurrence object for chaining
   */
  public Occ clear()
  {
    graph.clear();
    orth.clear();
    lem.clear();
    cat = 0;
    start = -1;
    end = -1;
    return this;
  }
  /**
   * Is Occurrence with at least graph value set ?
   * @return
   */
  public boolean isEmpty()
  {
    return graph.isEmpty();
  }
  
  /**
   * Set graph value by a String (or a mutable String)
   * @param cs
   * @return a handle on the occurrence object
   */
  public Occ graph( CharSequence cs) 
  {
    graph.replace( cs );
    return this;
  }
  /**
   * Set graph value by a String (or a mutable String), with index
   * @param cs
   * @param from start index in the CharSequence
   * @param length number of chars from start
   * @return a handle on the occurrence
   */
  public Occ graph( CharSequence cs, int from, int length) 
  {
    graph.replace( cs, from, length );
    return this;
  }
  /**
   * Set graph value by copy of a term
   * @param t
   * @return a handle on the occurrence
   */
  public Occ graph( Term t) 
  {
    graph.replace( t );
    return this;
  }
  /**
   * Get a handle on the graph object
   * @return access to the mutable String
   */
  public Term graph()
  {
    return graph;
  }
  /**
   * Set orth value by a String (or a mutable String)
   * @param cs
   * @return a handle on the occurrence
   */
  public Occ orth( CharSequence cs) 
  {
    orth.replace( cs );
    return this;
  }
  /**
   * Set orth value by a String (or a mutable String), with index
   * @param cs
   * @param from start index in the CharSequence
   * @param length number of chars from start
   * @return a handle on the occurrence object
   */
  public Occ orth( CharSequence cs, int from, int length) 
  {
    orth.replace( cs, from, length );
    return this;
  }
  /**
   * Set orth value by copy of a term
   * @param t
   * @return a handle on the occurrence object
   */
  public Occ orth( Term t) 
  {
    orth.replace( t );
    return this;
  }
  /**
   * Get a handle on the orthographic form as a mutable String object
   * @return access to the mutable String
   */
  public Term orth()
  {
    return orth;
  }
  /**
   * Get a grammar category code
   * @return a cat code like set
   */
  public short cat()
  {
    return cat;
  }
  /**
   * Set a grammar category code
   * @return a handle on the occurrence object
   */
  public Occ cat( final short code )
  {
    cat = code;
    return this;
  }
  /**
   * Set lem value by copy of a term
   * @param t
   * @return a handle on the occurrence object
   */
  public Occ lem( final Term t ) 
  {
    lem.replace( t );
    return this;
  }
  public Occ lem( final CharSequence cs) 
  {
    lem.replace( cs );
    return this;
  }
  /**
   * Get a handle on the lemma form as a mutable String object
   * @return access to the mutable String
   */
  public Term lem()
  {
    return lem;
  }
  /**
   * Set a start pointer for the occurrence
   * @param i pointer, for example a char index in a String
   * @return
   */
  public Occ start(final int i)
  {
    this.start = i;
    return this;
  }
  /**
   * Get the start pointer for the occurrence
   * @return
   */
  public int start()
  {
    return start;
  }
  /**
   * Set an end pointer for the occurrence (last char + 1)
   * @param i pointer, for example a char index in a String
   * @return
   */
  public Occ end(final int i)
  {
    this.end = i;
    return this;
  }
  /**
   * Get the start pointer for the occurrence
   * @return
   */
  public int end()
  {
    return end;
  }
  /**
   * Constructor
   */
  public Occ( final CharSequence graph, final CharSequence orth, final short cat, final CharSequence lem)
  {
    graph( graph );
    orth( orth );
    cat( cat );
    lem( lem );
  }
  /**
   * Constructor
   */
  public Occ( final Term graph, final Term orth, final short cat, final Term lem)
  {
    graph( graph );
    orth( orth );
    cat( cat );
    lem( lem );
  }
  /** 
   * Default String display 
   */
  public String toString()
  {
    return graph+"\t"+orth+"\t"+Cat.label( cat )+"\t"+lem+"\t"+start;
  }
}
