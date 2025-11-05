package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.Objects;

/**
 * A growable, contiguous character store (“slab”) for building read-only dictionaries.
 * <p>
 * Typical usage:
 * <pre>{@code
 * CharSlab slab = new CharSlab();                // build phase (single-threaded)
 * int off = slab.append("lemmatisation");        // returns start offset
 * int len = "lemmatisation".length();
 * // Store (off,len) in your entry; later, write back with term.copyBuffer(slab.array(), off, len)
 * slab.freeze();                                 // optional: trims storage, marks read-only
 * }</pre>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Amortized doubling growth on append; offsets remain stable.</li>
 *   <li>Write once during a build phase; after {@link #freeze()} the slab is immutable and
 *       safe for concurrent reads.</li>
 *   <li>No per-string allocation: you store only (offset,length) pairs into this slab.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <ul>
 *   <li><b>Not</b> thread-safe for mutation. Append from a single thread.</li>
 *   <li>After {@link #freeze()}, concurrent reads are safe.</li>
 * </ul>
 *
 * <h2>API notes</h2>
 * <ul>
 *   <li>{@link #append(char[], int, int)} and {@link #append(CharSequence)} return the start offset.</li>
 *   <li>{@link #writeTo(int, int, org.apache.lucene.analysis.tokenattributes.CharTermAttribute)}
 *       copies a slice directly into a Lucene {@code CharTermAttribute}.</li>
 *   <li>{@link #array()} exposes the backing array for zero-copy integrations
 *       that can accept (array,offset,length). The reference is stable after {@link #freeze()}.</li>
 * </ul>
 */
public final class CharSlab {
    /** Default initial capacity (characters). Tuned to avoid tiny resizes. */
    private static final int DEFAULT_CAPACITY = 4096;

    /** Backing store (may be replaced during growth; trimmed on {@link #freeze()}). */
    private char[] buf;

    /** Number of valid characters in {@link #buf}. Also the next append position. */
    private int size;

    /** When true, no further mutation is allowed. */
    private boolean frozen;

    /**
     * Constructs a slab with default initial capacity.
     */
    public CharSlab() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Constructs a slab with a caller-specified initial capacity.
     *
     * @param initialCapacity initial number of chars reserved; non-negative
     * @throws IllegalArgumentException if {@code initialCapacity} is negative
     */
    public CharSlab(int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("initialCapacity < 0");
        this.buf = new char[Math.max(initialCapacity, 1)];
        this.size = 0;
        this.frozen = false;
    }

    /**
     * Appends a slice of a char array.
     *
     * @param a   source array (non-null)
     * @param off start offset in {@code a} (0 ≤ off ≤ a.length)
     * @param len number of characters to copy (0 ≤ len ≤ a.length - off)
     * @return start offset in this slab where the slice was stored
     * @throws IllegalStateException    if the slab is {@link #freeze() frozen}
     * @throws NullPointerException     if {@code a} is null
     * @throws IndexOutOfBoundsException if the slice is out of bounds
     */
    public int append(char[] a, int off, int len) {
        Objects.requireNonNull(a, "a");
        if (frozen) throw new IllegalStateException("slab is frozen");
        if ((off | len) < 0 || off > a.length - len) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " a.length=" + a.length);
        }
        ensureCapacityExact(size + len);
        System.arraycopy(a, off, buf, size, len);
        int start = size;
        size += len;
        return start;
    }

    /**
     * Appends the content of a {@link CharSequence}.
     * Uses bulk {@code getChars} when possible to avoid per-char loops.
     *
     * @param s sequence to copy (non-null)
     * @return start offset in this slab where the sequence was stored
     * @throws IllegalStateException if the slab is {@link #freeze() frozen}
     * @throws NullPointerException  if {@code s} is null
     */
    public int append(CharSequence s) {
        Objects.requireNonNull(s, "s");
        if (frozen) throw new IllegalStateException("slab is frozen");
        final int len = s.length();
        ensureCapacityExact(size + len);

        if (s instanceof String str) {
            str.getChars(0, len, buf, size);
        } else if (s instanceof StringBuilder sb) {
            sb.getChars(0, len, buf, size);
        } else if (s instanceof StringBuffer sbuf) {
            sbuf.getChars(0, len, buf, size);
        } else {
            for (int i = 0; i < len; i++) buf[size + i] = s.charAt(i);
        }
        int start = size;
        size += len;
        return start;
    }

    /**
     * Returns the backing array. The returned reference becomes stable after {@link #freeze()}.
     * <p><b>Warning:</b> do not modify its contents.</p>
     */
    public char[] array() {
        return buf;
    }

    /**
     * Number of characters currently stored.
     */
    public int size() {
        return size;
    }

    /**
     * Current capacity of the backing array.
     */
    public int capacity() {
        return buf.length;
    }

    /**
     * Trims the backing array to exactly {@link #size()} and marks this slab as read-only.
     * Further append operations will throw {@link IllegalStateException}.
     * Calling {@code freeze()} multiple times is harmless; only the first call may copy.
     */
    public void freeze() {
        if (!frozen) {
            if (size != buf.length) {
                buf = Arrays.copyOf(buf, size);
            }
            frozen = true;
        }
    }

    /**
     * Indicates whether the slab has been frozen.
     */
    public boolean isFrozen() {
        return frozen;
    }

    // --- internals ------------------------------------------------------------

    /**
     * Ensures capacity for exactly {@code need} characters, using geometric growth.
     * Throws {@link OutOfMemoryError} on overflow/allocation failure.
     */
    private void ensureCapacityExact(int need) {
        if (need <= buf.length) return;
        int cap = buf.length;
        // Geometric growth: double until sufficient; handle potential overflow.
        while (cap < need) {
            int next = cap << 1;
            if (next <= 0) { // overflow
                cap = need; // last resort: try exact size
                break;
            }
            cap = next;
        }
        buf = Arrays.copyOf(buf, cap);
    }
}
