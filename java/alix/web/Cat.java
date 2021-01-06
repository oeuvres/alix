package alix.web;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;

/**
 * Options for filters by grammatical types
 */
public enum Cat implements Option {
  
  ALL("Tout", null),
  NOSTOP("Mots pleins", new TagFilter().setAll().noStop(true)), 
  SUB("Substantifs", new TagFilter().setGroup(Tag.SUB)), 
  NAME("Noms propres", new TagFilter().setGroup(Tag.NAME)),
  VERB("Verbes", new TagFilter().setGroup(Tag.VERB)),
  ADJ("Adjectifs", new TagFilter().setGroup(Tag.ADJ)),
  ADV("Adverbes", new TagFilter().setGroup(Tag.ADV)),
  STOP("Mots vides", new TagFilter().setAll().clearGroup(Tag.SUB).clearGroup(Tag.NAME).clearGroup(Tag.VERB).clearGroup(Tag.ADJ).clear(0)), 
  NULL("Mots inconnus", new TagFilter().set(0)), 
  ;
  final public String label;
  final public TagFilter tags;
  private Cat(final String label, final TagFilter tags) {  
    this.label = label ;
    this.tags = tags;
  }
  public TagFilter tags(){ return tags; }
  public String label() { return label; }
  public String hint() { return null; }
}
