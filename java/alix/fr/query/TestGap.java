package alix.fr.query;

import alix.util.Occ;

/**
 * Zero or n words, except sentence punctuation
 * 
 * @author user
 *
 */
public class TestGap extends Test
{
  /** Default size of gap */
  public final int DEFAULT = 5;
  /** Initial size of gap, maybe used for a reset */
  public final int initial;
  /** Max size of gap */
  private int gap;

  /** Default constructor, size of gap is DEFAULT */
  public TestGap() {
    this.initial = this.gap = DEFAULT;
  }

  /**
   * Constructor with parameter
   * 
   * @param gap
   */
  public TestGap(int gap) {
    this.initial = this.gap = gap;
  }

  /** @return the current gap size */
  public int gap()
  {
    return gap;
  }

  /**
   * @param gap,
   *          set gap to a new value, may be used in a kind of query
   */
  public void gap(int gap)
  {
    this.gap = gap;
  }

  /**
   * Decrement of gap is controled by user
   * 
   * @return
   */
  public int dec()
  {
    return --gap;
  }

  @Override
  public boolean test(Occ occ)
  {
    return (gap > 0);
  }

  @Override
  public String label()
  {
    return "**";
  }

}
