package alix.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum Distance implements Select
{
  none("Aucune", new None()),
  jaccard("Jaccard", new Jaccard()),
  dice("Dice", new Jaccard())
  ;
  public final String label;
  public final Scorer scorer;
  private Distance(final String label, final Scorer scorer)
  {
    this.label = label;
    this.scorer = scorer;
  }
  
  public double score(final double m11, final double m10, final double m01, final double m00)
  {
    return scorer.score(m11, m10, m01, m00);
  }
  
  static public interface Scorer
  {
    public double score(final double m11, final double m10, final double m01, final double m00);
  }
  
  static public class None implements Scorer
  {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return m11;
    }
  }
  static public class Jaccard implements Scorer
  {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return m11 / (m01 + m10 + m11);
    }
  }
  static public class Dice implements Scorer
  {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return 2 * m11 / (m10 * m10 + m01 * m01);
    }
  }
  
  // sadly repeating myself because enum can’t inherit from an abstract class (an
  // Enum already extends a class).

  @Override
  public String label()
  {
    return label;
  }

  @Override
  public List<Select> list()
  {
    return list;
  }

  public static List<Select> list;
  static {
    list = Collections.unmodifiableList(Arrays.asList((Select[]) values()));
  }
}