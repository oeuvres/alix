package com.github.oeuvres.alix.lucene.fluc;

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.Bits;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFieldVisitor;

/**
 * A field with no indexed terms, no doc values, no points —
 * only stored values. Holds no resources.
 *
 * <p>
 * The base {@link Fluc} reports {@code docs() == -1} for stored-only
 * fields because counting them requires a full scan of the stored
 * payload of every live document. {@link #count(IndexReader)} performs
 * that scan on demand for diagnostics; it is not called by
 * {@link Fluc#inferFields(org.apache.lucene.index.DirectoryReader, java.nio.file.Path)}.
 * </p>
 */
final class FlucStored extends Fluc
{
    FlucStored(
        final FieldInfo fi
    ) {
        super(fi, true, -1);
    }

    /**
     * Count live documents that have a value stored for this field.
     *
     * <p>
     * Iterates every live document and asks the stored-fields layer
     * whether this field is present, short-circuiting via a
     * {@link StoredFieldVisitor} so other stored fields are never
     * deserialized. Cost is O(N) document lookups; expect milliseconds
     * for a few thousand docs, seconds for millions. Intended for
     * debugging or admin endpoints, not for hot paths.
     * </p>
     *
     * @param reader index reader to scan
     * @return number of live documents with at least one stored value
     * @throws IOException on stored-field access failure
     */
    public int count(final IndexReader reader) throws IOException
    {
        final String fieldName = name();
        final Bits liveDocs = MultiBits.getLiveDocs(reader);
        final int maxDoc = reader.maxDoc();

        // Visitor stops as soon as the target field is seen in a document,
        // so we never decode unrelated stored fields.
        final FieldPresenceVisitor visitor = new FieldPresenceVisitor(fieldName);

        int count = 0;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (liveDocs != null && !liveDocs.get(docId)) continue;
            visitor.reset();
            reader.storedFields().document(docId, visitor);
            if (visitor.found) count++;
        }
        return count;
    }

    /**
     * Stored-field visitor that returns {@link Status#STOP} as soon as
     * the named field is encountered, and {@link Status#NO} for every
     * other field so its bytes are never read.
     */
    private static final class FieldPresenceVisitor extends StoredFieldVisitor
    {
        private final String target;
        boolean found = false;

        FieldPresenceVisitor(final String target) { this.target = target; }

        void reset() { this.found = false; }

        @Override
        public Status needsField(final FieldInfo fieldInfo) throws IOException
        {
            if (fieldInfo.name.equals(target)) {
                found = true;
                return Status.STOP;
            }
            return Status.NO;
        }
    }
}
