package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeReflector;

public final class LemmaAttributeImpl extends AbstractCharSlotAttributeImpl implements LemmaAttribute {
  @Override
  public void reflectWith(AttributeReflector reflector) {
    reflector.reflect(LemmaAttribute.class, "lemma", value());
  }
}