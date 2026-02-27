package com.github.oeuvres.alix.ingest;

import java.io.CharArrayReader;
import java.io.Reader;
import java.util.Objects;

/**
 * Document-scoped accumulator:
 * <ul>
 * <li>One growable {@code char[]} buffer holding all field contents appended in encounter order.</li>
 * <li>A growable array of reusable {@link AlixField} objects, one per {@code <alix:field>} occurrence.</li>
 * <li>Each field value is represented as {@code (off,len)} into the document buffer (no per-field String required).</li>
 * </ul>
 *
 * <p>
 * <b>Direct consumption by AlixSaxHandler</b>:
 * this class exposes a minimal callback-style API ({@code open/closeDocument}, {@code open/closeField},
 * {@code fieldChars/fieldText}) so a SAX handler can write directly into an {@code AlixDocument} instance
 * without any intermediate sink layer.
 * </p>
 *
 * <p>
 * <b>Lucene-agnostic</b>: this class only accumulates characters and field metadata. Indexing, @source
 * resolution, error policy, logging, and emission of Lucene {@code Document}s happen outside, typically
 * at {@code closeDocument()} (meaning: end of chapter/document unit, not SAX endDocument()).
 * </p>
 *
 * <p>
 * <b>Naming</b>: {@code open/closeDocument} intentionally avoids collision with SAX
 * {@code startDocument()/endDocument()}.
 * </p>
 */
public final class AlixDocument
{
    
    /** Scope boundary for one Lucene document (typically one {@code alix:chapter}). */
    public enum DocumentType
    {
        BOOK, CHAPTER, DOCUMENT
    }
    
    /** Canonical type set used in alix.rng: store, int, category, facet, meta, text. */
    public enum FieldType
    {
        STORE, INT, CATEGORY, FACET, META, TEXT;
        
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
                case "meta":
                    return META;
                case "text":
                    return TEXT;
                default:
                    throw new IllegalArgumentException("Unknown field type: " + s);
            }
        }
        
        /** Your chosen canonical processing order. */
        public static final FieldType[] CANONICAL_ORDER = {
                STORE, INT, CATEGORY, FACET, META, TEXT
        };
    }
    
    /**
     * One occurrence of {@code <alix:field>}.
     *
     * <p>
     * Value is always {@code (off,len)} into the owning {@link AlixDocument}'s buffer.
     * </p>
     *
     * <p>
     * For derived fields ({@code source != null}), the field may have {@code len==0} because no
     * content is provided inline; the consumer resolves from the source field at document close.
     * </p>
     */
    public static final class AlixField
    {
        public String name;
        public FieldType type;
        
        public int off;
        public int len;
        
        // Optional parameters (null for most fields)
        public String source;
        public String include;
        public String exclude;
        
        // Internal: start offset while accumulating
        private int start;
        
        private void reset()
        {
            name = null;
            type = null;
            off = 0;
            len = 0;
            source = null;
            include = null;
            exclude = null;
            start = 0;
        }
        
        /** Zero-copy reader over this field slice. */
        public Reader openReader(AlixDocument doc)
        {
            return doc.openReader(off, len);
        }
        
        @Override
        public String toString()
        {
            return "AlixField{name=" + name + ", type=" + type + ", off=" + off + ", len=" + len +
                    (source != null ? ", source=" + source : "") +
                    (include != null ? ", include=" + include : "") +
                    (exclude != null ? ", exclude=" + exclude : "") +
                    "}";
        }
    }
    
    // -------------------------
    // State
    // -------------------------
    
    private DocumentType docType;
    private String docId;
    
    // Lucene-style naming: "buffer" + "size"
    private char[] buffer = new char[16 * 1024];
    private int size = 0;
    
    private AlixField[] fields = new AlixField[32];
    private int fieldCount = 0;
    
    private AlixField current = null;
    
    public AlixDocument()
    {
        // pre-allocate reusable field slots
        for (int i = 0; i < fields.length; i++)
            fields[i] = new AlixField();
    }
    
    // -------------------------
    // Lifecycle (handler callbacks)
    // -------------------------
    
    /**
     * Open a new logical document scope (typically at {@code <alix:chapter>} start).
     * Resets buffer size and field count; does not shrink arrays.
     */
    public void openDocument(DocumentType type, String id)
    {
        this.docType = Objects.requireNonNull(type, "document type");
        this.docId = id; // may be null
        this.size = 0;
        this.fieldCount = 0;
        this.current = null;
    }
    
    /**
     * Close the current logical document scope (typically at {@code </alix:chapter>} end).
     * This method only checks internal consistency; consumers usually act after calling it.
     */
    public void closeDocument()
    {
        if (current != null)
            throw new IllegalStateException("closeDocument() with an open field: " + current.name);
    }
    
    /** Open a field occurrence; following calls must append content using {@link #fieldChars} / {@link #fieldText}. */
    public void openField(String name, FieldType type)
    {
        openField(name, type, null, null, null);
    }
    
    /** Open a field occurrence with optional parameters. */
    public void openField(String name, FieldType type, String source, String include, String exclude)
    {
        if (current != null)
            throw new IllegalStateException("Nested openField(): " + current.name);
        if (fieldCount == fields.length)
            growFields();
        
        final AlixField f = fields[fieldCount++];
        f.reset();
        
        f.name = Objects.requireNonNull(name, "field name");
        f.type = Objects.requireNonNull(type, "field type");
        f.source = source;
        f.include = include;
        f.exclude = exclude;
        
        f.start = size;
        f.off = size;
        f.len = 0;
        
        current = f;
    }
    
    /** Append a chunk of characters to the currently open field (SAX-style). */
    public void fieldChars(char[] ch, int off, int len)
    {
        if (current == null)
            throw new IllegalStateException("fieldChars() without an open field");
        if (len <= 0)
            return;
        ensureBufferCapacity(size + len);
        System.arraycopy(ch, off, buffer, size, len);
        size += len;
    }
    
    /** Append a whole scalar value (JSON/CSV-style) to the currently open field. */
    public void fieldText(CharSequence cs)
    {
        if (current == null)
            throw new IllegalStateException("fieldText() without an open field");
        if (cs == null)
            return;
        final int n = cs.length();
        if (n == 0)
            return;
        ensureBufferCapacity(size + n);
        for (int i = 0; i < n; i++)
            buffer[size++] = cs.charAt(i);
    }
    
    /** Close the current field and finalize its {@code (off,len)} slice into the buffer. */
    public void closeField()
    {
        if (current == null)
            throw new IllegalStateException("closeField() without an open field");
        current.off = current.start;
        current.len = size - current.start;
        current = null;
    }
    
    // -------------------------
    // Accessors for consumers
    // -------------------------
    
    public DocumentType documentType()
    {
        return docType;
    }
    
    public String documentId()
    {
        return docId;
    }
    
    public int fieldCount()
    {
        return fieldCount;
    }
    
    public AlixField fieldAt(int i)
    {
        if (i < 0 || i >= fieldCount)
            throw new IndexOutOfBoundsException();
        return fields[i];
    }
    
    /** Zero-copy reader over a buffer slice (for analyzers). */
    public Reader openReader(int off, int len)
    {
        if (off < 0 || len < 0 || off + len > size)
            throw new IndexOutOfBoundsException();
        return new CharArrayReader(buffer, off, len);
    }
    
    /** Copy a buffer slice into a String (for Lucene stored fields, logging, etc.). */
    public String sliceToString(int off, int len)
    {
        if (off < 0 || len < 0 || off + len > size)
            throw new IndexOutOfBoundsException();
        return new String(buffer, off, len);
    }
    
    // -------------------------
    // Internal growth helpers
    // -------------------------
    
    private void ensureBufferCapacity(int needed)
    {
        if (needed <= buffer.length)
            return;
        int cap = buffer.length;
        while (cap < needed)
            cap = cap + (cap >> 1) + 1; // ~1.5x growth
        final char[] n = new char[cap];
        System.arraycopy(buffer, 0, n, 0, size);
        buffer = n;
    }
    
    private void growFields()
    {
        final int old = fields.length;
        final int neu = old + (old >> 1) + 1;
        final AlixField[] n = new AlixField[neu];
        System.arraycopy(fields, 0, n, 0, old);
        for (int i = old; i < neu; i++)
            n[i] = new AlixField();
        fields = n;
    }
}