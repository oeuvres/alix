package obvil.web;

import java.util.List;

import alix.util.EnumOption;

public class TestSortFacet
{
  static public void lookup(EnumOption option) {
    System.out.println("Option name="+option+ " label="+option.label());
    
    for (EnumOption o: option.list()) {
      System.out.println(o);
    }
    List<EnumOption> options = option.list();
    try {
      options.remove(0);
    }
    catch(UnsupportedOperationException e) {
      System.out.println("Try to remove first element");
      e.printStackTrace();
    }
    System.out.println("---");
  }
  static public void main(String[] args) 
  {
    lookup(FacetSort.score);
    lookup(Cat.ADJ);
  }
}
