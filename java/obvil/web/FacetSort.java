package obvil.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import alix.util.EnumOption;

public enum FacetSort implements EnumOption {
  alpha("Alphabétique"), 
  freq("Fréquence"), 
  score("Pertinence"),
  ;
  // sadly repeating myself because enum can’t inherit from an abstract class (an Enum already extends a class). 
  public final String label;
  private FacetSort(final String label) {  
    this.label = label ;
  }
  public String label()
  {
    return label;
  }
  @Override
  public List<EnumOption> list()
  {
    return list;
  }
  public static List<EnumOption> list;
  static {
    list = Collections.unmodifiableList(Arrays.asList((EnumOption[])values()));
  }
}
