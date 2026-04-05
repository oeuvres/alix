package com.github.oeuvres.alix.lucene;

import org.apache.lucene.index.FieldInfo;

/**
 * A field with no indexed terms, no doc values, no points —
 * only stored values. Holds no resources.
 */
final class FlucStored extends Fluc
{
    FlucStored(
        final FieldInfo fi
    ) {
        super(fi, true, -1);
    }
}