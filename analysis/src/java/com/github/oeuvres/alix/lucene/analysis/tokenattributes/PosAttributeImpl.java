package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

/**
 * Default implementation for {@link PosAttribute}.
 *
 * <p>Lucene locates attribute implementations by naming convention: the interface
 * {@code XyzAttribute} is implemented by {@code XyzAttributeImpl} in the same classpath.
 *
 * <p>Implementation stores a single {@code int} POS code. This keeps the attribute compact,
 * fast to copy, and friendly to {@link com.github.oeuvres.alix.lucene.analysis.TokenStateQueue}
 * snapshots (which rely on {@code AttributeSource.copyTo}).
 */
public final class PosAttributeImpl extends AttributeImpl implements PosAttribute {
    private int pos = UNKNOWN;

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
        pos = UNKNOWN;
    }

    @Override
    public void copyTo(final AttributeImpl target) {
        ((PosAttribute) target).setPos(pos);
    }

    @Override
    public void reflectWith(final AttributeReflector reflector) {
        reflector.reflect(PosAttribute.class, "pos", pos);
    }
}
