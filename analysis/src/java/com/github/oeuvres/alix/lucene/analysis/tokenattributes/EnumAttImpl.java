package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import java.util.Objects;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

/**
 * Base implementation for enum-valued Lucene attributes.
 *
 * <p>Lucene's default attribute factory instantiates concrete implementations
 * by convention: {@code SomeAtt} is backed by {@code SomeAttImpl}. Therefore
 * this class is abstract. Each concrete enum attribute only needs a tiny final
 * implementation that calls this constructor.</p>
 *
 * @param <E> enum type carried by the attribute
 */
public abstract class EnumAttImpl<E extends Enum<E>> extends AttributeImpl implements EnumAtt<E> {
    private final Class<? extends EnumAtt<?>> attributeClass;
    private final E clearValue;
    private final Class<E> enumClass;
    private E value;

    /**
     * Creates an enum attribute implementation.
     *
     * @param attributeClass concrete Lucene attribute interface
     * @param enumClass enum class
     * @param clearValue value restored by {@link #clear()}
     * @throws NullPointerException if an argument is {@code null}
     */
    protected EnumAttImpl(
        final Class<? extends EnumAtt<?>> attributeClass,
        final Class<E> enumClass,
        final E clearValue
    ) {
        this.attributeClass = Objects.requireNonNull(attributeClass, "attributeClass");
        this.enumClass = Objects.requireNonNull(enumClass, "enumClass");
        this.clearValue = Objects.requireNonNull(clearValue, "clearValue");
        this.value = clearValue;

        if (clearValue.getDeclaringClass() != enumClass) {
            throw new IllegalArgumentException(
                "clearValue " + clearValue + " does not belong to " + enumClass.getName()
            );
        }
    }

    /**
     * Clears this attribute to its configured default value.
     */
    @Override
    public void clear() {
        value = clearValue;
    }

    /**
     * Copies this attribute value to another enum attribute.
     *
     * @param target target Lucene attribute implementation
     * @throws IllegalArgumentException if {@code target} is not an {@link EnumAtt}
     */
    @Override
    public void copyTo(final AttributeImpl target) {
        if (!(target instanceof EnumAtt<?> enumTarget)) {
            throw new IllegalArgumentException("target is not an EnumAtt: " + target.getClass().getName());
        }
        copyToEnum(enumTarget);
    }

    /**
     * Returns the enum class used by this attribute.
     *
     * @return enum class
     */
    @Override
    public Class<E> enumClass() {
        return enumClass;
    }

    /**
     * Returns the current enum value.
     *
     * @return current enum value
     */
    @Override
    public E get() {
        return value;
    }

    /**
     * Reflects this attribute for Lucene diagnostics.
     *
     * @param reflector Lucene attribute reflector
     */
    @Override
    public void reflectWith(final AttributeReflector reflector) {
        reflector.reflect(attributeClass, "enumClass", enumClass.getName());
        reflector.reflect(attributeClass, "value", value.name());
    }

    /**
     * Sets the current enum value.
     *
     * @param value enum value
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} belongs to another enum
     */
    @Override
    public void set(final E value) {
        Objects.requireNonNull(value, "value");
        if (value.getDeclaringClass() != enumClass) {
            throw new IllegalArgumentException(
                "value " + value + " does not belong to " + enumClass.getName()
            );
        }
        this.value = value;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void copyToEnum(final EnumAtt<?> target) {
        final EnumAtt raw = target;
        raw.set(value);
    }
    
    @Override
    public String toString()
    {
        return value.toString();
    }
}