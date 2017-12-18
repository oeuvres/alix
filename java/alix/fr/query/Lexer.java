package alix.fr.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.util.Occ;

/**
 * Rules to correct an occurrence chain according to context. TODO : send an
 * event to get some occurrence more ?
 * 
 * @author user
 *
 */
public class Lexer
{
  /** Book of rules, stored in order of reading */
  private Rule[] rulebook = new Rule[0];
  /** Index of rules by form */
  private HashMap<String, int[]> byform = new HashMap<String, int[]>();
  /** Index of rules by tag */
  private HashMap<Integer, int[]> bytag = new HashMap<Integer, int[]>();
  /** max left context needed for rules */
  private int maxleft = 0;
  /** max right context needed for rules */
  private int maxright = 0;
  /** Cell separator */
  private String sep = ";";

  /** Change separator */
  public void Lexer(final String sep)
  {
    this.sep = sep;
  }

  /**
   * Load rules from a String
   * 
   * @throws IOException
   */
  public void loadString(String content) throws IOException
  {
    load(new BufferedReader(new StringReader(content)));
  }

  /**
   * Load rules from a jar resource
   * 
   * @throws IOException
   */
  public void loadRes(String res) throws IOException
  {
    load(new BufferedReader(new InputStreamReader(Lexik.class.getResourceAsStream(res), StandardCharsets.UTF_8)));
  }

  /**
   * Incremental loading ?
   * 
   * @throws IOException
   */
  public void load(BufferedReader buf) throws IOException
  {
    LinkedList<Rule> rulerec = new LinkedList<Rule>(Arrays.asList(rulebook));
    int no = rulerec.size();
    String line = null;
    while ((line = buf.readLine()) != null) {
      if (line.trim().isEmpty())
        continue;
      if (line.trim().charAt(0) == '#')
        continue;
      String[] cells = line.split(sep);
      if (cells.length < 4)
        continue;
      // no center to the context ? Send an error ?
      if (cells[1].trim().isEmpty())
        continue;

      // replacement, should be a tag
      int tag;
      // strip quotes ?
      cells[3] = cells[3].trim().replaceAll("[\":]", "");
      tag = Tag.code(cells[3]);
      // tag unknown, continue ?
      Test current;
      Test last = null;
      Test center;
      String[] terms;
      // left context
      if (!cells[0].trim().isEmpty()) {
        terms = cells[0].trim().split(" ");
        int left = 0;
        for (int i = 0; i < terms.length; i++) {
          if (terms[i].trim().isEmpty())
            continue;
          current = test(terms[i]);
          if (last != null) {
            last.next(current);
            current.prev(last);
          }
          last = current;
          left++;
        }
        if (left > maxleft)
          maxleft = left;
      }
      // center
      center = test(cells[1]);
      if (last != null) {
        last.next(center);
        center.prev(last);
      }
      last = center;
      // right context
      if (!cells[2].trim().isEmpty()) {
        terms = cells[2].trim().split(" ");
        int right = 0;
        for (int i = 0; i < terms.length; i++) {
          if (terms[i].trim().isEmpty())
            continue;
          current = test(terms[i]);
          last.next(current);
          current.prev(last);
          last = current;
          right++;
        }
        if (right > maxright)
          maxright = right;
      }
      rulerec.add(new Rule(center, tag));
      if (center instanceof TestTerm) {
        String term = ((TestTerm) center).term.toString();
        int[] nos = byform.get(term);
        if (nos == null)
          byform.put(term, new int[] { no });
        else {
          int oldlength = nos.length;
          nos = Arrays.copyOf(nos, oldlength + 1);
          nos[oldlength] = no;
          byform.put(term, nos);
        }
      }
      else if (center instanceof TestTag || center instanceof TestTagPrefix) {
        int key = 0;
        if (center instanceof TestTag)
          key = Tag.prefix(((TestTag) center).tag);
        else if (center instanceof TestTagPrefix)
          key = ((TestTagPrefix) center).prefix;
        int[] nos = bytag.get(key);
        if (nos == null)
          bytag.put(key, new int[] { no });
        else {
          int oldlength = nos.length;
          nos = Arrays.copyOf(nos, oldlength + 1);
          nos[oldlength] = no;
          bytag.put(key, nos);
        }
      }
      else { // ??
        System.err.println(" ?? Lexer " + line + " — " + center.getClass());
      }
      no++;
    }
    rulebook = rulerec.toArray(new Rule[0]);
  }

  /**
   * Apply relevant rules to an occurrence
   * 
   * @param occ
   */
  public boolean apply(Occ occ)
  {
    int[] rules = null;
    int[] rules1 = byform.get(occ.orth());
    int[] rules2 = bytag.get(occ.tag().prefix());
    if (rules1 == null && rules2 == null)
      return false;
    else if (rules1 != null)
      rules = rules1;
    else if (rules2 != null)
      rules = rules2;
    // compact and sort rules
    else {
      rules = new int[rules1.length + rules2.length];
      int i = 0;
      for (int no : rules1) {
        rules[i] = no;
        i++;
      }
      for (int no : rules2) {
        rules[i] = no;
        i++;
      }
      Arrays.sort(rules);
    }
    for (int no : rules) {
      // exit at first success ?
      if (rulebook[no].apply(occ))
        return true;
    }
    return false;
  }

  /**
   * Build a Test from a String
   * 
   * @param term
   * @return
   */
  static public Test test(String term)
  {
    term = term.trim();
    if (term.isEmpty())
      return null;
    // quotes, maybe an orth or an exact tag
    boolean quotes = false;
    int start = 0;
    int end = term.length();
    if (term.charAt(0) == '"') {
      start = 1;
      end--;
    }
    int pos = term.indexOf(':');
    int tag;
    if (pos < 0) {
      tag = Tag.code(term.substring(start, end));
      if (tag == Tag.UNKNOWN)
        return new TestOrth(term.substring(start, end));
      if (Tag.isPrefix(tag) && !quotes)
        return new TestTagPrefix(tag);
      return new TestTag(tag);
    }
    else {
      tag = Tag.code(term.substring(pos + 1, end));
      if (Tag.isPrefix(tag) && !quotes)
        return new TestOrthTagPrefix(term.substring(start, pos), tag);
      return new TestOrthTag(term.substring(start, pos), tag);
    }
  }

  /**
   * A rule for the lexer, a test chain and a tag for replacement
   * 
   * @author glorieux-f
   */
  private class Rule
  {
    public Test center;
    public int tag;

    public Rule(final Test center, final int tag) {
      this.center = center;
      this.tag = tag;
    }

    /**
     * Test if rule is relevant to an occurrence and its context TODO: how to get an
     * occurrence next if needed to test rule?
     * 
     * @param center
     * @return false if rule do not apply
     */
    public boolean match(final Occ occ)
    {
      // return false on each fail
      if (!center.test(occ))
        return false;
      // test left context
      Occ context = occ;
      Test test = center;
      while (test.prev() != null) {
        test = test.prev();
        if (context.prev() == null)
          return false;
        context = context.prev();
        if (!test.test(context))
          return false;
      }
      // test right context
      context = occ;
      test = center;
      while (test.next() != null) {
        test = test.next();
        if (context.next() == null)
          return false;
        context = context.next();
        if (!test.test(context))
          return false;
      }
      return true;
    }

    /**
     * If rule is relevant to occurrence and context, apply tag modification
     * 
     * @param occ
     *          An occurrence in chain whith prev and next occurrence
     * @return true if rule is applied, and tag has been modified
     */
    public boolean apply(final Occ occ)
    {
      if (!match(occ))
        return false;
      // rule match, apply tag correction
      occ.tag(tag);
      return true;
    }
  }

  /**
   * Default String display
   */
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    int length = rulebook.length;
    boolean first;
    for (int i = 0; i < length; i++) {
      Rule rule = rulebook[i];
      Test test = rule.center;
      // get the first occ from chain
      while (test.prev() != null)
        test = test.prev();
      // print the chain from start
      first = true;
      while (test != null) {
        if (test == rule.center)
          sb.append(sep);
        else if (first)
          first = false;
        else
          sb.append(' ');
        sb.append(test.label());
        if (test == rule.center) {
          sb.append(sep);
          first = true;
        }
        test = test.next();
      }
      sb.append(sep);
      sb.append(Tag.label(rule.tag));
      sb.append("\n");
    }
    return sb.toString();
  }

  /**
   * Larger right context needed for a rule
   * 
   * @return
   */
  public int maxright()
  {
    return maxright;
  }

  /**
   * Larger left context needed for a rule
   * 
   * @return
   */
  public int maxleft()
  {
    return maxleft;
  }

  /**
   * For testing
   */
  public static void main(String[] args) throws IOException
  {
    Lexer lexer = new Lexer();
    lexer.loadRes("/alix/fr/dic/rules.csv");
    String text = "Tu:PRO le:DET prends:VERB bien:ADV ?:PUN";
    Occ first = new Occ();
    Occ occ = first;
    Occ last = null;
    for (String w : text.split(" ")) {
      String[] cells = w.split(":");
      occ.orth(cells[0]);
      occ.tag(Tag.code(cells[1]));
      if (last != null) {
        last.next(occ);
        occ.prev(last);
      }
      last = occ;
      occ = new Occ();
    }
    occ = first;
    while (occ != null) {
      lexer.apply(occ);
      System.out.println(occ);
      occ = occ.next();
    }
    System.out.println("maxleft=" + lexer.maxleft + " maxright=" + lexer.maxright);
    System.out.println(lexer);
  }
}
