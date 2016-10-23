package site.oeuvres.fr;

import site.oeuvres.util.Term;

/**
 * A Token in a text flow with different properties.
 * A token should allow to write a nice concordance.
 * @author glorieux
 *
 */
public class Tok
{
  /** Graphical form like encountered, caps/min, ellisions, could be used for a correct concordancer */
  private Term graph = new Term();
  /** Orthographic form, normalized graphical form */
  private Term orth = new Term();
  /** Grammatical category */
  private int cat;
  /** Lemma form */
  private Term lem = new Term();
  /**
   * Empty constructor
   */
  public Tok()
  {
    
  }
  public void graph( CharSequence cs) 
  {
    graph.replace( cs );
  }
  public void graph( CharSequence cs, int from, int length) 
  {
    graph.replace( cs, from, length );
  }
  public void graph( Term t) 
  {
    graph.replace( t );
  }
  public Term graph()
  {
    return graph;
  }
  public void orth( CharSequence cs) 
  {
    orth.replace( cs );
  }
  public void orth( Term t) 
  {
    orth.replace( t );
  }
  public Term orth()
  {
    return orth;
  }
  public int cat()
  {
    return cat;
  }
  public void cat( final int code )
  {
    cat = code;
  }
  public void lem( CharSequence cs) 
  {
    lem.replace( cs );
  }
  public Term lem()
  {
    return lem;
  }
  /**
   * Constructor
   */
  public Tok( final String graph, final String orth, final short cat, final String lem)
  {
    graph( graph );
    orth( orth );
    cat( cat );
    lem( lem );
  }
  /**
   * Constructor
   */
  public Tok( final Term graph, final Term orth, final short cat, final Term lem)
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
    return graph+"\t"+orth+"\t"+Cat.label( cat )+"\t"+lem;
  }
}
