package alix.fr.query;

import alix.util.Char;
import alix.util.Occ;
import alix.util.OccList;

/**
 * A query builder of Occ events TODO document query syntax
 * 
 * Possible backtracking silences "A A B B", "A * B" => will not fire 2x
 * 
 * Idea, the shortest path size (**=0, OR=shortest)
 * 
 * @author glorieux-f
 */
public class Query
{
  /** The root test, go back to root after a fail */
  private final Test first;
  /** The current test to apply in sequence order */
  private Test current;
  /** The pattern found */
  OccList found = new OccList();
  /** position in the query parser */
  private int pos = 0;
  /** a static value for query parsing */
  static final int OR = 1;

  /**
   * Parse a human query to build a test object tree
   * 
   * @param q
   */
  public Query(String q) {
    first = parse(q);
    current = first;
  }

  /**
   * Test an Occurrence, return true if current test succeed and if it is the last
   * one. This method is bottleneck to optimize.
   * 
   * @return
   */
  public boolean test(Occ occ)
  {
    // something found after a restart of the test chain, reset the found buffer
    if (current == first && found.size() > 0)
      found.reset();
    // ## operator, zero or more stopwords
    if (current instanceof TestGap) {
      // no next, works like a stopword suffix
      if (current.next() == null) {
        // end of stop sequence, return true if at least one word found
        if (!current.test(occ)) {
          current = first;
          return (found.size() > 0);
        }
        // stopword, add it to found chain
        found.add(occ);
        // query sequence may continue
        return false;
      }
      // next test success, jump the gap, and works like a test success
      if (current.next().test(occ)) {
        found.add(occ);
        // end of chain
        if (current.next().next() == null) {
          current = first;
          return true;
        }
        else {
          current = current.next().next();
          return false;
        }
      }
      // not yet end of gap, continue
      if (((TestGap) current).dec() > 0) {
        System.out.println(occ);
        return false;
      }
      // end of gap, restart
      current = first;
      found.reset();
      return false;
    }
    // ** operator
    else if (current instanceof TestGap) {
      found.add(occ);
      // no next, works like a simple joker
      if (current.next() == null) {
        current = first;
        return true;
      }
      // next test success, jump the gap, and works like a test success
      if (current.next().test(occ)) {
        // end of chain
        if (current.next().next() == null) {
          current = first;
          return true;
        }
        else {
          current = current.next().next();
          return false;
        }
      }
      // not yet end of gap, continue
      if (((TestGap) current).dec() > 0) {
        System.out.println(occ);
        return false;
      }
      // end of gap, restart
      current = first;
      found.reset();
      return false;
    }
    // unary tests
    else {
      // test success, set next
      if (current.test(occ)) {
        found.add(occ);
        current = current.next();
        if (current == null) {
          current = first;
          return true;
        }
        return false;
      }
      // first fail, go away
      if (current == first)
        return false;
      // fail in the chain, A B C found in A B A B C
      if (first.test(occ)) {
        found.reset();
        found.add(occ);
        current = first.next();
        return false;
      }
      // restart
      current = first;
      found.reset();
      return false;
    }
  }

  /**
   * Parse a query String, return a test object
   * 
   * @param q
   * @return
   */
  private Test parse(String q)
  {
    // first pass, normalize query String to simplify tests
    if (pos == 0)
      q = q.replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ", ").replaceAll("([^\\s])\\(", "$1 (").trim();
    int length = q.length();
    Test orphan = null;
    Test root = null;
    Test next = null;
    StringBuffer term = new StringBuffer();
    boolean quote = false;
    char c;
    int op = 0;
    while (true) {
      if (pos >= length)
        c = 0; // last char
      else
        c = q.charAt(pos);
      pos++;
      // append char to term ?
      // in quotes, always append char
      if (quote) {
        term.append(c);
        // close quote
        if (c == '"')
          quote = false;
        continue;
      }
      // open quote
      else if (c == '"') {
        term.append(c);
        quote = true;
        continue;
      }
      // not a space or a special char, append to term
      else if (!Char.isSpace(c) && c != 0 && c != ',' && c != '(' && c != ')') {
        term.append(c);
        continue;
      }

      // now, the complex work, should be end of term
      // a term is set, build an orphan query
      if (term.length() > 0) {
        orphan = Test.create(term.toString());
        term.setLength(0); // reset term buffer
      }
      // another orphan Test to connect
      if (c == '(') {
        if (orphan != null)
          System.out.println("Error of the program");
        orphan = parse(q); // pointer should be after ')' now
      }
      // an orphan to connect
      if (orphan != null) {
        // if coming from a () expression, orphan may have descendants, take the last
        // descendant
        Test child = orphan;
        while (child.next() != null)
          child = child.next();
        // root should have been set
        if (op == OR) {
          ((TestOr) root).add(orphan);
          next = child;
          op = 0;
        }
        // new test
        else if (root == null) {
          root = orphan;
          next = child;
        }
        // append to last
        else {
          next.next(orphan);
          next = child;
        }
        orphan = null;
      }
      // resolve OR test after orphan connection
      if (c == ',') {
        if (root == null)
          root = new TestOr();
        else if (!(root instanceof TestOr)) {
          Test tmp = root;
          root = new TestOr();
          ((TestOr) root).add(tmp);
        }
        // for next turn
        op = OR;
      }
      // end of parenthesis or end of query, break after Test parsing
      if (c == ')') {
        break;
      }
      else if (c == 0)
        break;
    }
    return root;

  }

  /**
   * Returns list of Occs found
   * 
   * @return
   */
  public OccList found()
  {
    return found;
  }

  /**
   * Returns number of Occ found
   * 
   * @return
   */
  public int foundSize()
  {
    return found.size();
  }

  @Override
  public String toString()
  {
    return first.toString();
  }

  /**
   * No reason to use in cli, for testing only
   */
  public static void main(String[] args)
  {
    String text = "A B A B C A B C C A C A D D D C";
    Query q1 = new Query("A B(A ,C)");
    Query q2 = new Query("A * C");
    Query q3 = new Query("A ** C");
    Query q4 = new Query("C");
    Query q5 = new Query("A B C");
    Occ occ = new Occ();
    System.out.println(text);
    for (String tok : text.split(" ")) {
      occ.orth(tok);
      if (q1.test(occ)) {
        System.out.println(q1 + " FOUND: " + q1.found());
      }
      if (q2.test(occ)) {
        System.out.println(q2 + " FOUND: " + q2.found());
      }
      if (q3.test(occ)) {
        System.out.println(q3 + " FOUND: " + q3.found());
      }
      if (q4.test(occ)) {
        System.out.println(q4 + " FOUND: " + q4.found());
      }
      if (q5.test(occ)) {
        System.out.println(q5 + " FOUND: " + q5.found());
      }
    }
  }
}
