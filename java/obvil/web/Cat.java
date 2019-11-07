package obvil.web;

public enum Cat {
  NOSTOP("Mots pleins"), 
  SUB("Substantifs"), 
  NAME("Noms propres"),
  VERB("Verbes"),
  ADJ("Adjectifs"),
  ADV("Adverbes"),
  ALL("Tout"),
  ;
  public final String label;
  private Cat(final String label) {  
    this.label = label ;
  }
    
}
