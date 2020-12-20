package alix.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum Distance implements Select
{
  none("Occurrences", "m11") {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return m11;
    }
  },
  jaccard("Jaccard", "m11 / (m10 + m01 + m11)") {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return m11 / (m01 + m10 + m11);
    }
  },
  dice("Dice", "2*m11 / (m10² + m01²)") {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return 2 * m11 / (m10 * m10 + m01 * m01);
    }
  }
  ;
  private Distance(final String label, final String hint)
  {
    this.label = label;
    this.hint = hint;
  }
  
  abstract public double score(final double m11, final double m10, final double m01, final double m00);
  
  // sadly repeating myself because enum can’t inherit from an abstract class 
  final public String label;
  public String label() { return label; }
  static final public List<Select> list;
  static { list = Collections.unmodifiableList(Arrays.asList((Select[]) values())); }
  public List<Select> list() { return list; }
  final public String hint;
  public String hint() { return hint; }


}