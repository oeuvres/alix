package com.github.oeuvres.alix.common;

import java.util.HashMap;
import java.util.Map;

/**
 * Contract and tool for linguistic tool.
 */
public interface Tag
{

    /**
     * Dual lookup for enums implementing Tag: - code -> enum constant (array, O(1))
     * - name -> enum constant (HashMap, O(1) avg)
     * 
     * HashMap is optimized for Upos lookup from a PosTagger.
     *
     * Build-time checks: duplicate code or duplicate name =>
     * IllegalArgumentException.
     */
    final class Lookup<E extends Enum<E> & Tag>
    {
        private final int minCode;
        private final Object[] byCode; // stores E
        private final Map<String, E> byName; // immutable view

        private Lookup(final E[] values)
        {
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("Empty enum values");
            }

            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (E e : values) {
                final int c = e.code();
                if (c < min)
                    min = c;
                if (c > max)
                    max = c;
            }

            this.minCode = min;
            this.byCode = new Object[max - min + 1];

            final HashMap<String, E> m = new HashMap<>(values.length * 2);

            for (E e : values) {
                // code -> e
                final int idx = e.code() - min;
                if (byCode[idx] != null) {
                    throw new IllegalArgumentException(
                            "Duplicate code=" + e.code() + " for " + e + " and " + byCode[idx]);
                }
                byCode[idx] = e;

                // name -> e
                final String n = e.name();
                final E prev = m.putIfAbsent(n, e);
                if (prev != null && prev != e) {
                    throw new IllegalArgumentException("Duplicate name=\"" + n + "\" for " + e + " and " + prev);
                }
            }

            this.byName = Map.copyOf(m);
        }

        public static <E extends Enum<E> & Tag> Lookup<E> of(final Class<E> enumClass)
        {
            return new Lookup<>(enumClass.getEnumConstants());
        }

        /** Alternative builder to avoid `.class` from inside the enum. */
        public static <E extends Enum<E> & Tag> Lookup<E> of(final E[] values)
        {
            return new Lookup<>(values);
        }

        public E get(final int code)
        {
            final int idx = code - minCode;
            if (idx < 0 || idx >= byCode.length)
                return null;
            @SuppressWarnings("unchecked")
            final E e = (E) byCode[idx];
            return e;
        }

        public E get(final String name)
        {
            if (name == null)
                return null;
            return byName.get(name);
        }
    }

    public String name();

    public int code();
}
