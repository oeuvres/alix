package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

/**
 * Default implementation for {@link BoundaryAttribute}.
 *
 * <p>Lucene resolves this implementation from the
 * {@code BoundaryAttribute}/{@code BoundaryAttributeImpl} naming convention.
 * The value is copied with token-state snapshots in the same way as the other
 * Alix custom attributes.</p>
 */
public final class BoundaryAttributeImpl extends AttributeImpl implements BoundaryAttribute
{
    /** Current boundary value. */
    private int boundary = NONE;

    /**
     * Clears the boundary for the next token.
     */
    @Override
    public void clear()
    {
        boundary = NONE;
    }

    /**
     * Copies this attribute to another attribute instance.
     *
     * @param target target attribute implementation
     */
    @Override
    public void copyTo(final AttributeImpl target)
    {
        ((BoundaryAttribute) target).setBoundary(boundary);
    }

    /**
     * Returns the current boundary.
     *
     * @return boundary value
     */
    @Override
    public int getBoundary()
    {
        return boundary;
    }

    /**
     * Exposes this attribute to Lucene's reflection mechanism.
     *
     * @param reflector attribute reflector
     */
    @Override
    public void reflectWith(final AttributeReflector reflector)
    {
        reflector.reflect(BoundaryAttribute.class, "boundary", boundary);
    }

    /**
     * Sets the current boundary.
     *
     * @param boundary boundary value
     */
    @Override
    public void setBoundary(final int boundary)
    {
        this.boundary = boundary;
    }
}
