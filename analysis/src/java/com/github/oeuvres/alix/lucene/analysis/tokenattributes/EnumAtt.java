package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

/**
 * Lucene attribute carrying one enum value.
 *
 * <p>This interface is intended as a generic base for small, concrete enum
 * attributes such as {@code QueryTokenizerTypeAtt} or {@code IndexMatchAtt}.
 * Concrete attributes should extend this interface with their own enum type so
 * that test code and filters can retrieve a strongly typed value.</p>
 *
 * @param <E> enum type carried by the attribute
 */
public interface EnumAtt<E extends Enum<E>> extends Attribute {
    /**
     * Returns the enum class used by this attribute.
     *
     * @return enum class
     */
    Class<E> enumClass();

    /**
     * Returns the current enum value.
     *
     * @return current enum value
     */
    E get();

    /**
     * Sets the current enum value.
     *
     * @param value enum value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    void set(E value);
}