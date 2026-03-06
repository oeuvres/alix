package com.github.oeuvres.alix.ingest;

import java.io.CharArrayReader;
import java.io.Reader;
import java.util.Objects;

/**
 * Mutable, document-scoped accumulator for SAX-style ingestion.
 *
 * <h2>Core idea</h2>
 * <ul>
 *   <li>All character content for the current logical document is appended into one growable
 *       {@code char[]} buffer in encounter order.</li>
 *   <li>Each {@code <alix:field>} occurrence is represented by an {@link AlixField} containing an
 *       {@code (off,len)} slice into that buffer.</li>
 *   <li>No per-field {@link String} is required unless a consumer explicitly asks for it.</li>
 * </ul>
 *
 * <h2>Lifecycle and invariants</h2>
 * <ul>
 *   <li>Exactly one logical document scope is active between {@link #openDocument(String, String)}
 *       and {@link #closeDocument()}.</li>
 *   <li>At most one field can be open at a time. Calls must be properly nested:
 *       {@code openField → (fieldChars|fieldText)* → closeField}.</li>
 *   <li>After {@link #closeDocument()}, consumers may iterate over fields via
 *       {@link #fieldCount()} / {@link #fieldAt(int)}.</li>
 * </ul>
 *
 * <h2>Re-use and object ownership</h2>
 * <p>
 * {@link AlixField} instances are <b>reused</b> across documents to reduce allocation.
 * Any reference to an {@code AlixField} is only valid until the next {@link #openDocument(String, String)}
 * (or the next {@link #openField(String, FieldType, String, String, String)} that reuses that slot).
 * If you must retain data beyond the current document, copy out what you need (e.g. {@code name/type}
 * and {@code getValueAsString()} or a defensive copy of the slice).
 * </p>
 *
 * <h2>Thread-safety</h2>
 * <p>
 * Not thread-safe. Intended for single-threaded SAX parsing / ingestion.
 * </p>
 *
 * <h2>Lucene agnostic</h2>
 * <p>
 * This class only accumulates characters and field metadata. Indexing, source resolution,
 * error policy, logging, and emission of Lucene {@code Document}s happen outside (typically
 * after {@link #closeDocument()}).
 * </p>
 */
public final class AlixDocument
{
    /**
     * Canonical field types used by the schema (e.g. {@code alix.rng}).
     * <p>
     * This enum is intentionally small and stable; any additional behavior belongs outside
     * of {@link AlixDocument}.
     * </p>
     */
    public enum FieldType
    {
        STORE, INT, CATEGORY, FACET, TEXT;

        /**
         * Parse an XML attribute value into a {@link FieldType}.
         *
         * @param s XML value (expected: {@code store|int|category|facet|meta|text})
         * @return the corresponding {@link FieldType}
         * @throws NullPointerException if {@code s} is null
         * @throws IllegalArgumentException if {@code s} is not a known type
         */
        public static FieldType fromXml(String s)
        {
            if (s == null)
                throw new NullPointerException("field type is null");
            switch (s) {
                case "store":
                    return STORE;
                case "int":
                    return INT;
                case "category":
                    return CATEGORY;
                case "facet":
                    return FACET;
                case "text":
                    return TEXT;
                default:
                    throw new IllegalArgumentException("Unknown field type: " + s);
            }
        }
    }

    /**
     * One occurrence of {@code <alix:field>}.
     *
     * <h2>Value representation</h2>
     * <p>
     * The value is always stored as {@code (off,len)} into the owning {@link AlixDocument}'s
     * internal buffer (no mandatory {@link String} allocation).
     * </p>
     *
     * <h2>Derived fields</h2>
     * <p>
     * For derived fields ({@link #source} != null), the field may legitimately have {@code len == 0}
     * because no inline content is provided; the consumer resolves the final value from the source
     * field at document close.
     * </p>
     *
     * <h2>Reuse warning</h2>
     * <p>
     * Instances are reused by {@link AlixDocument}. Do not retain references across documents unless
     * you copy out the data you need.
     * </p>
     *
     * <h2>Field openness</h2>
     * <p>
     * Accessors that expose the value ({@link #getValueAsString()}, {@link #openReader()},
     * {@link #getCharSequence()}) require the field to be closed (i.e. after {@link AlixDocument#closeField()}).
     * </p>
     */
    public static final class AlixField
    {
        /** Owning document accumulator. Set by {@link AlixDocument#openField}. */
        public AlixDocument doc;

        /** Field name (XML attribute). Never null for an active field. */
        public String name;

        /** Field type (XML attribute). Never null for an active field. */
        public FieldType type;

        /** Start offset (inclusive) into {@link AlixDocument#buffer}. */
        public int off;

        /** Slice length into {@link AlixDocument#buffer}. */
        public int len;

        /** Optional source field name for derived fields; otherwise null. */
        public String source;

        /**
         * Internal: start offset while accumulating.
         * <ul>
         *   <li>{@code tmpOff >= 0} means "field currently open"</li>
         *   <li>{@code tmpOff == -1} means "field closed or reset"</li>
         * </ul>
         */
        private int tmpOff;

        /** Cached String materialization (optional, computed on demand after close). */
        private String cache;

        private void reset()
        {
            doc = null;
            name = null;
            type = null;
            off = -1;
            len = -1;
            source = null;
            tmpOff = -1;
            cache = null;
        }

        /**
         * Return this field value as a {@link String}. The string is cached per field occurrence.
         *
         * @return materialized field value (may be empty, never null)
         * @throws IllegalStateException if the field is not closed yet
         */
        public String getValueAsString()
        {
            requireClosed();
            if (cache == null)
                cache = new String(doc.buffer, off, len);
            return cache;
        }

        /**
         * Open a zero-copy {@link Reader} over this field slice.
         *
         * @return a reader over the internal buffer slice
         * @throws IllegalStateException if the field is not closed yet
         */
        public Reader openReader()
        {
            requireClosed();
            return new CharArrayReader(doc.buffer, off, len);
        }

        /**
         * Return a zero-copy {@link CharSequence} view of the field slice.
         * <p>
         * The returned object allocates only the wrapper; it does not allocate a {@link String}
         * unless {@link CharSequence#toString()} is called.
         * </p>
         *
         * @return a {@link CharSequence} view over the internal buffer slice
         * @throws IllegalStateException if the field is not closed yet
         */
        public CharSequence getCharSequence()
        {
            requireClosed();
            return new CharSlice(doc.buffer, off, len);
        }

        /**
         * @return true if this field has been closed via {@link AlixDocument#closeField()}
         */
        public boolean isClosed()
        {
            return tmpOff == -1 && doc != null;
        }

        private void requireClosed()
        {
            if (doc == null)
                throw new IllegalStateException("Detached field (not associated with any document)");
            if (tmpOff != -1)
                throw new IllegalStateException("Field is still open: " + name);
            if (off < 0 || len < 0)
                throw new IllegalStateException("Field has not been initialized/reset correctly: " + name);
        }

        @Override
        public String toString()
        {
            return "AlixField{name=" + name + ", type=" + type + ", off=" + off + ", len=" + len +
                (source != null ? ", source=" + source : "") +
                "}";
        }
    }

    // -------------------------
    // State
    // -------------------------

    private String docType;
    private String docId;

    /** Internal content buffer; valid content is {@code buffer[0..length)}. */
    private char[] buffer = new char[16 * 1024];

    /** Current length (exclusive end) of valid content in {@link #buffer}. */
    private int length = 0;

    /** Reusable slots for field occurrences. */
    private AlixField[] fields = new AlixField[32];

    /** Number of active field occurrences in the current document scope. */
    private int fieldCount = 0;

    /** Currently open field, or null if no field is open. */
    private AlixField current = null;

    // Optional per-ingest / per-document metadata (caller-managed policy).
    private long ingestEpochMillis = 0L;
    private int chapterOrdinal = -1; // -1 = unknown

    /**
     * Set an ingestion timestamp in epoch milliseconds.
     * <p>
     * This class does not enforce whether this is per-run or per-document; the caller defines the policy.
     * </p>
     *
     * @param ms epoch milliseconds
     */
    public void setIngestEpochMillis(long ms)
    {
        this.ingestEpochMillis = ms;
    }

    /**
     * @return ingestion timestamp (epoch milliseconds), default 0
     */
    public long getIngestEpochMillis()
    {
        return ingestEpochMillis;
    }

    /**
     * Set a chapter ordinal for the current logical document.
     *
     * @param ord chapter ordinal, or -1 if unknown
     */
    public void setChapterOrdinal(int ord)
    {
        this.chapterOrdinal = ord;
    }

    /**
     * @return chapter ordinal, or -1 if unknown
     */
    public int getChapterOrdinal()
    {
        return chapterOrdinal;
    }

    /**
     * Create a new accumulator with pre-allocated reusable {@link AlixField} slots.
     */
    public AlixDocument()
    {
        for (int i = 0; i < fields.length; i++)
            fields[i] = new AlixField();
    }

    // -------------------------
    // Lifecycle (handler callbacks)
    // -------------------------

    /**
     * Open a new logical document scope (typically at {@code <alix:chapter>} start).
     * <p>
     * Resets internal buffer length and field count; does not shrink arrays.
     * </p>
     *
     * @param type required document type (e.g. "chapter")
     * @param id optional document id (may be null)
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalStateException if a field is currently open
     */
    public void openDocument(String type, String id)
    {
        if (current != null)
            throw new IllegalStateException("openDocument() while a field is open: " + current.name);

        this.docType = Objects.requireNonNull(type, "document type");
        this.docId = id; // may be null
        this.length = 0;
        this.fieldCount = 0;

        // Reasonable default: per-document ordinal should not leak across documents.
        this.chapterOrdinal = -1;
    }

    /**
     * Close the current logical document scope (typically at {@code </alix:chapter>} end).
     * <p>
     * This method validates internal consistency only.
     * Consumers typically act after calling it.
     * </p>
     *
     * @throws IllegalStateException if a field is still open
     */
    public void closeDocument()
    {
        if (current != null)
            throw new IllegalStateException("closeDocument() with an open field: " + current.name);
    }

    /**
     * Open a field occurrence with no optional parameters.
     *
     * @param name field name (non-null)
     * @param type field type (non-null)
     * @throws NullPointerException if {@code name} or {@code type} is null
     * @throws IllegalStateException if another field is already open
     */
    public void openField(String name, FieldType type)
    {
        openField(name, type, null);
    }

    /**
     * Open a field occurrence with optional parameters.
     * <p>
     * Once opened, the caller must append content via {@link #fieldChars(char[], int, int)} and/or
     * {@link #fieldText(CharSequence)}, then finalize with {@link #closeField()}.
     * </p>
     *
     * @param name field name (non-null)
     * @param type field type (non-null)
     * @param source optional source field name for derived fields (nullable)
     * @throws NullPointerException if {@code name} or {@code type} is null
     * @throws IllegalStateException if another field is already open
     */
    public void openField(String name, FieldType type, String source)
    {
        if (current != null)
            throw new IllegalStateException("Nested openField(): " + current.name);

        if (fieldCount == fields.length)
            growFields();

        final AlixField f = fields[fieldCount++];
        f.reset();

        f.doc = this;
        f.name = Objects.requireNonNull(name, "field name");
        f.type = Objects.requireNonNull(type, "field type");
        f.source = source;

        f.tmpOff = length; // mark as open
        f.off = length;
        f.len = 0;

        current = f;
    }

    /**
     * Append a chunk of characters to the currently open field (SAX-style).
     * <p>
     * Characters are appended verbatim; this method does not insert separators or normalize whitespace.
     * </p>
     *
     * @param ch input buffer
     * @param off offset in {@code ch}
     * @param len number of characters to append
     * @throws IllegalStateException if no field is open
     * @throws NullPointerException if {@code ch} is null
     * @throws OutOfMemoryError if growth would overflow
     */
    public void fieldChars(char[] ch, int off, int len)
    {
        if (current == null)
            throw new IllegalStateException("fieldChars() without an open field");
        Objects.requireNonNull(ch, "ch");
        if (len <= 0)
            return;

        final int needed = safeAdd(length, len);
        ensureBufferCapacity(needed);
        System.arraycopy(ch, off, buffer, length, len);
        length += len;
    }

    /**
     * Append an entire scalar value to the currently open field (JSON/CSV-style).
     * <p>
     * The content is appended verbatim; this method does not insert separators or normalize whitespace.
     * </p>
     *
     * @param cs character sequence to append (nullable; null is treated as no-op)
     * @throws IllegalStateException if no field is open
     * @throws OutOfMemoryError if growth would overflow
     */
    public void fieldText(CharSequence cs)
    {
        if (current == null)
            throw new IllegalStateException("fieldText() without an open field");
        if (cs == null)
            return;

        final int n = cs.length();
        if (n == 0)
            return;

        final int needed = safeAdd(length, n);
        ensureBufferCapacity(needed);
        for (int i = 0; i < n; i++)
            buffer[length++] = cs.charAt(i);
    }

    /**
     * Close the current field and finalize its {@code (off,len)} slice into the document buffer.
     *
     * @throws IllegalStateException if no field is open
     */
    public void closeField()
    {
        if (current == null)
            throw new IllegalStateException("closeField() without an open field");

        final AlixField f = current;
        f.off = f.tmpOff;
        f.len = length - f.tmpOff;
        f.tmpOff = -1; // mark as closed
        current = null;
    }

    // -------------------------
    // Accessors for consumers
    // -------------------------

    /**
     * @return current document type (non-null between openDocument/closeDocument)
     */
    public String docType()
    {
        return docType;
    }

    /**
     * @return current document id (may be null)
     */
    public String docId()
    {
        return docId;
    }

    /**
     * @return number of field occurrences accumulated in the current document scope
     */
    public int fieldCount()
    {
        return fieldCount;
    }

    /**
     * Return the {@code i}-th field occurrence in encounter order.
     *
     * @param i index in {@code [0, fieldCount())}
     * @return reusable {@link AlixField} instance for that occurrence
     * @throws IndexOutOfBoundsException if {@code i} is out of range
     */
    public AlixField fieldAt(int i)
    {
        if (i < 0 || i >= fieldCount)
            throw new IndexOutOfBoundsException("i=" + i + ", fieldCount=" + fieldCount);
        return fields[i];
    }

    /**
     * Internal {@link CharSequence} view of a {@code char[]} slice.
     * <p>
     * This is a zero-copy wrapper. {@link #toString()} materializes a new {@link String}.
     * </p>
     */
    private static final class CharSlice implements CharSequence
    {
        private final char[] buf;
        private final int off, len;

        CharSlice(char[] buf, int off, int len)
        {
            this.buf = buf;
            this.off = off;
            this.len = len;
        }

        @Override
        public int length()
        {
            return len;
        }

        @Override
        public char charAt(int index)
        {
            if (index < 0 || index >= len)
                throw new IndexOutOfBoundsException();
            return buf[off + index];
        }

        @Override
        public CharSequence subSequence(int start, int end)
        {
            if (start < 0 || end < start || end > len)
                throw new IndexOutOfBoundsException();
            return new CharSlice(buf, off + start, end - start);
        }

        @Override
        public String toString()
        {
            return new String(buf, off, len);
        }
    }

    // -------------------------
    // Internal growth helpers
    // -------------------------

    private void ensureBufferCapacity(int needed)
    {
        if (needed <= buffer.length)
            return;

        int cap = buffer.length;
        while (cap < needed) {
            final int next = cap + (cap >> 1) + 1; // ~1.5x growth
            if (next <= cap) { // overflow protection
                cap = needed;
                break;
            }
            cap = next;
        }

        final char[] n = new char[cap];
        System.arraycopy(buffer, 0, n, 0, length);
        buffer = n;
    }

    private void growFields()
    {
        final int old = fields.length;
        final int neu = safeAdd(old, (old >> 1) + 1);

        final AlixField[] n = new AlixField[neu];
        System.arraycopy(fields, 0, n, 0, old);
        for (int i = old; i < neu; i++)
            n[i] = new AlixField();
        fields = n;
    }

    private static int safeAdd(int a, int b)
    {
        final int r = a + b;
        if (((a ^ r) & (b ^ r)) < 0)
            throw new OutOfMemoryError("Integer overflow while growing internal buffers");
        return r;
    }
}