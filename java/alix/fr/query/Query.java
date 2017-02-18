package alix.fr.query;


import alix.fr.Occ;
import alix.fr.OccList;
import alix.util.Char;
/**
 * A query builder of Occ events
 * TODO document query syntax
 * 
 * Possible backtracking silences "A A B B", "A * B" => will not fire 2x
 * Idea, the shortest path size (**=0, OR=shortest)
 * 
 * @author glorieux-f
 */
public class Query
{
  /** The root test, go back to root after a fail */
  private Test first;
  /** The current test to apply in sequence order */
  private Test current;
  /** The last Occ sequence been tested */
  OccList found = new OccList();
  /**
   * Parse a human query to build a test object tree
   * @param q
   */
  public Query( String q )
  {
    first = parse( q );
    current = first;
  }
  int pos = 0;
  static final int OR = 1;
  private Test parse( String q )
  {
    // first pass, normalize query to simplify tests
    if ( pos == 0 ) q = q.replace( "\\s+", " " ).replace( "\\s+,\\s+", ", " ).trim();
    int length = q.length();
    Test orphan = null;
    Test root = null;
    Test next = null;
    StringBuffer sb = new StringBuffer();
    boolean quote = false;
    char c;
    int op = 0;
    while ( true ) {
      if ( pos >= length ) c = 0; // last char
      else c = q.charAt( pos );
      pos++;
      // append char to term ?
      // in quotes, always append char
      if ( quote ) {
        sb.append( c );
        if ( c == '"' ) quote = false;
        continue;
      }
      // quote, open or close quote
      else if ( c == '"' ) {
        sb.append( c );
        quote = true;
        continue;
      }
      // not a space or a special char, append to term
      else if (  !Char.isSpace( c ) && c != 0 && c != ',' && c != '(' && c != ')') {
        sb.append( c );
        continue;
      }
      
      
      // now, the complex work, should be end of term
      // a term is set, build an orphan query
      if ( sb.length() > 0 ) {
        orphan = Test.create( sb.toString() );
        sb.setLength( 0 );
      }
      while ( true ) {
        // an orphan to connect
        if ( orphan != null ) {
          // orphan may children 
          Test child = orphan;
          while ( child.next != null ) child = child.next;
          // root should be OK here
          if ( op == OR ) {
            ((TestOr) root).add( orphan );
            next = child;
            op = 0;
          }
          else if ( root == null ) {
            root = orphan;
            next = child;
          }
          else {
            next.next = orphan;
            next = child;
          }
          orphan = null;
        }
        // another orphan Test to connect
        if ( c == '(' ) {
          orphan = parse(q); // pointer should be after ')' now
          c = ' '; // avoid infinite loop
          continue;
        }
        break;
      }
      // OR test to open
      if ( c == ',' ) {
        if ( root == null ) root = new TestOr();
        else if ( !( root instanceof TestOr) ) {
          Test tmp = root;
          root = new TestOr();
          ((TestOr)root).add( tmp );
        }
        // for next turn
        op = OR;
      }
      // end of parenthesis or query, break
      if ( c == ')' ) {
        break;
      }
      else if ( c == 0) break;
    }
    return root;
    
  }
  /**
   * Returns list of Occs found
   * @return
   */
  public OccList found() {
    return found;
  }
  /**
   * Returns number of Occ found
   * @return
   */
  public int foundSize() {
    return found.size();
  }
  /**
   * Test an Occurrence, return true if current test succeed and if it is last 
   * @return
   */
  public boolean test( Occ occ )
  {
    if ( current == first ) found.reset(); // reset after found
    if ( current instanceof TestGap ) {
      found.add( occ );
      // no next, works like a simple joker
      if ( current.next() == null ) {
        current = first;
        return true;
      }
      // next test success, jump the gap, and works like a test success  
      else if ( current.next().test( occ ) ) {
        current = current.next().next();
        if ( current == null ) {
          current = first;
          return true;
        }
        return false;
      }
      // not yet end of gap, continue
      else if ( ((TestGap) current).dec() > 0 ) {
        return false;
      }
      // end of gap, will work like reset to first below
    }
    // test success, set next
    else if ( current.test( occ ) ) {
      found.add( occ );
      current = current.next();
      if ( current == null ) {
        current = first;
        return true;
      }
      return false;
    }
    // test fail or end of gap
    if ( current == first ); 
    // fail, but may be start of a pattern
    else if ( first.test( occ )) {
      found.reset();
      found.add( occ );
      current = first.next();
    }
    // restart
    else current = first;
    
    return false;
  }
  @Override
  public String toString() {
    return first.toString();
  }
  /**
   * No reason to use in cli, for testing only
   */
  public static void main(String[] args)
  {
    String text = "Je suis en vacances";
    Query q1 = new Query("Je VERB");
    Query q2 = new Query("A * C");
    Query q3 = new Query("A ** C");
    Query q4 = new Query("C");
    Occ occ = new Occ();
    System.out.println( text );
    for (String tok:text.split( " " )) {
      occ.orth( tok );
      if ( q1.test(occ) ) {
        System.out.println( "query 1 : "+q1+" FOUND: "+q1.found() );
      }
      if ( q2.test(occ) ) {
        System.out.println( q2+" FOUND: "+q2.found() );
      }
      if ( q3.test(occ) ) {
        System.out.println( q3+" FOUND: "+q3.found() );
      }
      if ( q4.test(occ) ) {
        System.out.println( q4+" FOUND: "+q4.found() );
      }
    }
  }
}
