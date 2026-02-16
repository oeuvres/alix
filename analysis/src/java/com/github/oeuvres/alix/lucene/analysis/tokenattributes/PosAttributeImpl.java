package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

public class PosAttributeImpl extends AttributeImpl implements PosAttribute
{
    private int pos = 0;

    /** Initialize this attribute with no value */
    public PosAttributeImpl() {}

    @Override
    public int getPos() {
      return pos;
    }

    @Override
    public void setPos(final int pos) {
      this.pos = pos;
    }

    @Override
    public void clear() {
      pos = 0;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }

      if (other instanceof PosAttributeImpl) {
        return ((PosAttributeImpl) other).pos == pos;
      }

      return false;
    }

    @Override
    public int hashCode() {
      return pos;
    }

    @Override
    public void copyTo(AttributeImpl target) {
      PosAttribute t = (PosAttribute) target;
      t.setPos(pos);
    }

    @Override
    public void reflectWith(AttributeReflector reflector) {
      reflector.reflect(PosAttribute.class, "pos", pos);
    }
}
