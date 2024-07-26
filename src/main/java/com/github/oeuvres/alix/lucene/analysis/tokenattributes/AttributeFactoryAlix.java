package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.AttributeImpl;

/**
 * An attribute factory to use a {@link CharsAttImpl} for a {@link CharTermAttribute}.
 * Other attributes will have a default class name, AttributeName → AttributeNameImpl.
 */
final public class AttributeFactoryAlix extends AttributeFactory
{
    private final AttributeFactory delegate;

    /**
     * Constructor with the default {@link AttributeFactory}.
     * @param delegate default implementations.
     */
    public AttributeFactoryAlix(AttributeFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public AttributeImpl createAttributeInstance(Class<? extends Attribute> attClass)
    {
        // for chars attributes, return a CharsAttImpl with advanced char 
        if (attClass == CharTermAttribute.class) {
            return new CharsAttImpl();
        }
        else {
            return delegate.createAttributeInstance(attClass);
        }
    }
}