package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

/**
 * Default implementation for {@link ElementAttribute}.
 *
 * Copies both the char buffer (local-name) and the event byte.
 */
public final class ElementAttributeImpl extends AbstractCharSlotAttributeImpl implements ElementAttribute
{
    private byte event = NONE;

    @Override
    public byte getEvent() {
        return event;
    }

    @Override
    public void setEvent(byte event) {
        this.event = event;
    }

    @Override
    public void clear() {
        super.clear();
        event = NONE;
    }

    @Override
    public void copyTo(AttributeImpl target) {
        super.copyTo(target);
        ((ElementAttribute) target).setEvent(event);
    }

    @Override
    public void reflectWith(AttributeReflector reflector) {
        reflector.reflect(ElementAttribute.class, "element", value());
        reflector.reflect(ElementAttribute.class, "event", event);
    }
}