package com.github.oeuvres.alix.lucene.output;

import java.util.Objects;

import org.apache.lucene.util.FixedBitSet;

/**
 * Multi-channel aggregation indexed by a dense integer key.
 *
 * <p>
 * A {@code NumHisto} fixes a coordinate system &mdash; an inclusive value range
 * {@code [min, max]} and a per-document lookup from Lucene doc id to raw value
 * &mdash; and exposes named arrays of {@link #length()} bins for the
 * aggregations the application accumulates: {@link #valueDocs},
 * {@link #valueSpans}, {@link #valueTokens}, {@link #valueScore}.
 * </p>
 *
 * <p>
 * Channel arrays are attached by reference. The producer of an aggregation
 * passes its own buffer in through the matching setter, or allocates a fresh
 * one via the matching {@code ensure} method. All arrays are addressed by bin
 * index, not by raw value; convert raw values with {@link #bin(int, int)}.
 * </p>
 *
 * <h2>Ownership</h2>
 *
 * <p>
 * The constructor receives {@code docValues} and {@code docHasValue} by
 * reference and never writes through them. Channel arrays are similarly
 * shared by reference once attached: callers must treat foreign channels as
 * read-only. A 100&#x202F;000-bin channel is several hundred kilobytes; copying
 * defensively on every request is not affordable.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Not thread-safe. A {@code NumHisto} is a per-request assembly: build it on
 * one thread, hand it to the listener and the renderers on the same thread,
 * discard it at end of request. Cached source arrays held by {@code FlucNum}
 * and {@code FlucText} are the shared, immutable state; this class is the
 * mutable working copy.
 * </p>
 */
public final class HistoNum
{
    /** Documents that have a value for the coordinate field. Shared by reference; not mutated. */
    public final FixedBitSet docHasValue;

    /** Document id &rarr; raw value. Shared by reference; not mutated. */
    public final int[] docValues;

    /** Number of bins; length of every attached channel array. */
    public final int length;

    /** Inclusive lower bound of the value range; offset to subtract from a raw value to obtain its bin index. */
    public final int min;

    /** Document count per bin. */
    private int[] valueDocs;

    /** Mean (or other floating-point aggregate) per bin. */
    private double[] valueScore;

    /** Span count per bin. */
    private long[] valueSpans;

    /** Token count per bin. */
    private long[] valueTokens;

    /**
     * Creates a histogram bound to a coordinate system. No channels attached.
     *
     * @param min          inclusive lower bound of the value range
     * @param max          inclusive upper bound; must be {@code >= min}
     * @param docValues    doc id &rarr; raw value, shared by reference
     * @param docHasValue  presence bitset, shared by reference
     * @throws NullPointerException     if {@code docValues} or {@code docHasValue} is {@code null}
     * @throws IllegalArgumentException if {@code max < min}
     */
    public HistoNum(
        final int min,
        final int max,
        final int[] docValues,
        final FixedBitSet docHasValue
    ) {
        if (max < min) {
            throw new IllegalArgumentException("max (" + max + ") < min (" + min + ")");
        }
        this.min = min;
        this.length = max - min + 1;
        this.docValues = Objects.requireNonNull(docValues, "docValues");
        this.docHasValue = Objects.requireNonNull(docHasValue, "docHasValue");
    }

    /**
     * Bin index for a document, or {@code noValue} if the document has no value
     * or its value is outside {@code [min, max]}.
     *
     * @param docId    global Lucene document id
     * @param noValue  sentinel returned for absent or out-of-range values
     * @return bin index in {@code [0, length())}, or {@code noValue}
     */
    public int bin(final int docId, final int noValue)
    {
        if (!docHasValue.get(docId)) {
            return noValue;
        }
        final int b = docValues[docId] - min;
        if (b < 0 || b >= length) {
            return noValue;
        }
        return b;
    }

    /**
     * Returns {@link #valueDocs}, allocating a fresh zero-filled array of
     * length {@link #length()} if absent.
     *
     * @return the channel array, owned by this histogram
     */
    public int[] ensureValueDocs()
    {
        if (valueDocs == null) {
            valueDocs = new int[length];
        }
        return valueDocs;
    }

    /**
     * Returns {@link #valueScore}, allocating a fresh zero-filled array of
     * length {@link #length()} if absent.
     *
     * @return the channel array, owned by this histogram
     */
    public double[] ensureValueScore()
    {
        if (valueScore == null) {
            valueScore = new double[length];
        }
        return valueScore;
    }

    /**
     * Returns {@link #valueSpans}, allocating a fresh zero-filled array of
     * length {@link #length()} if absent.
     *
     * @return the channel array, owned by this histogram
     */
    public long[] ensureValueSpans()
    {
        if (valueSpans == null) {
            valueSpans = new long[length];
        }
        return valueSpans;
    }

    /**
     * Returns {@link #valueTokens}, allocating a fresh zero-filled array of
     * length {@link #length()} if absent.
     *
     * @return the channel array, owned by this histogram
     */
    public long[] ensureValueTokens()
    {
        if (valueTokens == null) {
            valueTokens = new long[length];
        }
        return valueTokens;
    }

    /**
     * Inclusive upper bound of the value range.
     *
     * @return {@code min + length - 1}
     */
    public int max()
    {
        return min + length - 1;
    }

    /**
     * Attaches a document-count channel by reference.
     *
     * @param values array of exactly {@link #length()} entries
     * @throws NullPointerException     if {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code values.length != length()}
     */
    public void setValueDocs(final int[] values)
    {
        Objects.requireNonNull(values, "values");
        if (values.length != length) {
            throw new IllegalArgumentException(
                "channel length " + values.length + " != " + length);
        }
        this.valueDocs = values;
    }

    /**
     * Attaches a score channel by reference.
     *
     * @param values array of exactly {@link #length()} entries
     * @throws NullPointerException     if {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code values.length != length()}
     */
    public void setValueScore(final double[] values)
    {
        Objects.requireNonNull(values, "values");
        if (values.length != length) {
            throw new IllegalArgumentException(
                "channel length " + values.length + " != " + length);
        }
        this.valueScore = values;
    }

    /**
     * Attaches a span-count channel by reference.
     *
     * @param values array of exactly {@link #length()} entries
     * @throws NullPointerException     if {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code values.length != length()}
     */
    public void setValueSpans(final long[] values)
    {
        Objects.requireNonNull(values, "values");
        if (values.length != length) {
            throw new IllegalArgumentException(
                "channel length " + values.length + " != " + length);
        }
        this.valueSpans = values;
    }

    /**
     * Attaches a token-count channel by reference.
     *
     * @param values array of exactly {@link #length()} entries
     * @throws NullPointerException     if {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code values.length != length()}
     */
    public void setValueTokens(final long[] values)
    {
        Objects.requireNonNull(values, "values");
        if (values.length != length) {
            throw new IllegalArgumentException(
                "channel length " + values.length + " != " + length);
        }
        this.valueTokens = values;
    }

    /**
     * Document-count channel, or {@code null} if not attached.
     *
     * @return the channel array, or {@code null}
     */
    public int[] valueDocs()
    {
        return valueDocs;
    }

    /**
     * Score channel, or {@code null} if not attached.
     *
     * @return the channel array, or {@code null}
     */
    public double[] valueScore()
    {
        return valueScore;
    }

    /**
     * Span-count channel, or {@code null} if not attached.
     *
     * @return the channel array, or {@code null}
     */
    public long[] valueSpans()
    {
        return valueSpans;
    }

    /**
     * Token-count channel, or {@code null} if not attached.
     *
     * @return the channel array, or {@code null}
     */
    public long[] valueTokens()
    {
        return valueTokens;
    }
}
