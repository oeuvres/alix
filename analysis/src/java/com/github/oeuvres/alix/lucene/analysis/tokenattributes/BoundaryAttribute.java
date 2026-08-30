/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

/**
 * Carries a structural boundary immediately preceding the current emitted token.
 *
 * <p>The attribute is intended for structural events that must survive a token
 * filter without being emitted as artificial terms. Values are ordered by
 * strength, so when several boundaries occur before the next emitted token the
 * strongest one can be retained with {@link Math#max(int, int)}.</p>
 *
 * <p>A value of {@link #NONE} means that no structural boundary precedes the
 * current token.</p>
 */
public interface BoundaryAttribute extends Attribute
{
    /** No structural boundary. */
    int NONE = 0;

    /** Sentence boundary. */
    int SENTENCE = 1;

    /** Paragraph boundary. */
    int PARAGRAPH = 2;

    /** Section boundary. */
    int SECTION = 3;

    /**
     * Returns the structural boundary preceding the current token.
     *
     * @return one of {@link #NONE}, {@link #SENTENCE}, {@link #PARAGRAPH}, or
     *         {@link #SECTION}
     */
    int getBoundary();

    /**
     * Sets the structural boundary preceding the current token.
     *
     * @param boundary boundary value
     */
    void setBoundary(int boundary);
}
