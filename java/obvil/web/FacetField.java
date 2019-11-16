package obvil.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import alix.util.EnumOption;

public enum FacetField implements EnumOption {
  author("Auteur"), 
  ;
  public final String label;
  private FacetField(final String label) {  
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
