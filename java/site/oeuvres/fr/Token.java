package site.oeuvres.fr;

import site.oeuvres.util.Term;

/**
 * A Token in a text flow with different properties.
 * A token should allow to write a nice concordance.
 * @author glorieux
 *
 */
public class Token
{
  /** Char index in the source file of the first char of the token */
  private int start;
  /** Char index in the source file (-1) like for String.substring(start, end) */
  private int end;
  /** Graphical form like encountered, caps/min, ellisions, could be used for a correct concordancer */
  private Term graph = new Term();
  /** Orthographic form, normalized graphical form */
  private Term orth = new Term();
  /** Grammatical category */
  private short cat;
  /** Lemma form */
  private Term lem = new Term();
  /**
   * Empty constructor
   */
  public Token()
  {
    
  }
  public Token graph( CharSequence cs) 
  {
    graph.replace( cs );
    return this;
  }
  public Token graph( CharSequence cs, int from, int length) 
  {
    graph.replace( cs, from, length );
    return this;
  }
  public Token graph( Term t) 
  {
    graph.replace( t );
    return this;
  }
  public Term graph()
  {
    return graph;
  }
  public Token orth( CharSequence cs) 
  {
    orth.replace( cs );
    return this;
  }
  public Token orth( Term t) 
  {
    orth.replace( t );
    return this;
  }
  public Term orth()
  {
    return orth;
  }
  public short cat()
  {
    return cat;
  }
  public Token cat( final short code )
  {
    cat = code;
    return this;
  }
  public Token lem( final Term t ) 
  {
    lem.replace( t );
    return this;
  }
  public Token lem( final CharSequence cs) 
  {
    lem.replace( cs );
    return this;
  }
  public Term lem()
  {
    return lem;
  }
  public Token start(final int i)
  {
    this.start = i;
    return this;
  }
  public int start()
  {
    return start;
  }
  public Token end(final int i)
  {
    this.end = i;
    return this;
  }
  public int end()
  {
    return end;
  }
  /**
   * Constructor
   */
  public Token( final String graph, final String orth, final short cat, final String lem)
  {
    graph( graph );
    orth( orth );
    cat( cat );
    lem( lem );
  }
  /**
   * Constructor
   */
  public Token( final Term graph, final Term orth, final short cat, final Term lem)
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
