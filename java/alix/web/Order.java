package alix.web;

import alix.lucene.search.FormEnum.Sorter;

public enum Order implements Option 
{
  score("Score", null, Sorter.score),
  freq("Occurrences", null, Sorter.freq),
  hits("Textes", null, Sorter.hits),
  occs("Total occurrences", null, Sorter.occs),
  docs("Total textes", null, Sorter.docs),
  alpha("Alphabétique", null, Sorter.alpha),
  ;
  private Order(final String label, final String hint, Sorter sorter) {  
    this.label = label;
    this.hint = hint;
    this.sorter = sorter;
  }
  final public String label;
  final public String hint;
  final public Sorter sorter;
  public String label() { return label; }
  public String hint() { return hint; }
  public Sorter sorter() { return sorter; }

}
