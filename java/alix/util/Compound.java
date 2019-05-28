package alix.util;

/**
 * A mutable String used to merge compunds words.
 * @author fred
 *
 */
public class Compound
{
  public final int size;
  private char[] chars;
  private final int[] offsets;
  private final int pos;
  public Compound(final int size) {
    this.size = size;
  }
  

}
