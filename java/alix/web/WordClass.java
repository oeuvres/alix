package alix.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum WordClass implements Select {
  NOSTOP("Mots pleins"), 
  SUB("Substantifs"), 
  NAME("Noms propres"),
  VERB("Verbes"),
  ADJ("Adjectifs"),
  ADV("Adverbes"),
  ALL("Tout"),
  ;
  public final String label;
  private WordClass(final String label) {  
    this.label = label ;
  }
  
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
    list = Collections.unmodifiableList(Arrays.asList((Select[])values()));
  }
}
