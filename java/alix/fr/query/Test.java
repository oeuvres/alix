package alix.fr.query;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.util.Occ;

public abstract class Test
{
  /** Next test */
  private Test next = null;
  /** Prev test */
  private Test prev = null;

  /** Factory, build a test with a string */
  public static Test create(String term)
  {
    if (term.equals("*")) {
      return new TestTrue();
    }
    if (term.equals("**")) {
      return new TestGap();
    }
    if (term.equals("##")) {
      return new TestStop();
    }
    boolean quotes = false;
    // quotes, maybe an orth or an exact tag
    if (term.charAt(0) == '"') {
      quotes = true;
      int endIndex = term.length();
      if (term.charAt(endIndex - 1) == '"')
        endIndex--;
      term = term.substring(1, endIndex);
    }
    // a known tag ?
    int tag;
    if ((tag = Tag.code(term)) != Tag.UNKNOWN) {
      if (quotes)
        return new TestTag(tag);
      if (Tag.prefix(tag) == tag)
        return new TestTagPrefix(tag);
      return new TestTag(tag);
    }
    // a known lemma ?
    else if (!quotes && term.equals(Lexik.lem(term))) {
      return new TestLem(term);
    }
    // default
    else {
      return new TestOrth(term);
    }
  }

  /** Set a next Test after */
  public Test next(Test test)
  {
    this.next = test;
    return this;
  }

  /** get next Test */
  public Test next()
  {
    return next;
  }

  /** Set a prev Test */
  public Test prev(Test test)
  {
    this.prev = test;
    return this;
  }

  /** get next Test */
  public Test prev()
  {
    return prev;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(label());
    if (next != null) {
      sb.append(" ");
      sb.append(next);
    }
    return sb.toString();
  }

  /** A string view for the Test */
  abstract public String label();

  /**
   * @param occ
   *          test present occurrence
   * @return null if test failed, the next Test if in a chain, a TestEnd
   */
  abstract public boolean test(Occ occ);

  /**
   * No reason to use in cli, for testing only
   */
  public static void main(String[] args)
  {
    System.out.println(Test.create("NAME"));
  }

}
