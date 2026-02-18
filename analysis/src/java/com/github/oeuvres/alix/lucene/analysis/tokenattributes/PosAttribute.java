package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

/**
 * Part-of-speech (POS) attribute for a token.
 *
 * <p>This attribute is intended to carry a Universal Dependencies "UPOS" tag (or a project-specific
 * POS code) as an integer. It deliberately does <em>not</em> reuse {@link org.apache.lucene.analysis.tokenattributes.FlagsAttribute}
 * so that structural/control flags (sentence boundary, paragraph boundary, synthetic tokens, etc.)
 * remain independent from linguistic tags (UPOS values such as {@code PUNCT}).
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #UNKNOWN} means "unset / unknown / not applicable".</li>
 *   <li>Otherwise the value is an application-defined POS code (typically {@code com.github.oeuvres.alix.common.Upos#code()}).</li>
 * </ul>
 *
 * <h2>Why a dedicated attribute?</h2>
 * <p>Lucene's analysis chain is frequently composed of multiple TokenFilters. Overloading an existing
 * attribute with unrelated semantics tends to create collisions and makes downstream consumption fragile.
 * A dedicated attribute provides an explicit contract for POS tagging.</p>
 */
public interface PosAttribute extends Attribute {

    /**
     * Sentinel value for "unknown / unset / not applicable".
     */
    int UNKNOWN = 0;

    /**
     * Return the POS code for the current token.
     *
     * @return POS code, or {@link #UNKNOWN} if unset/unknown.
     */
    int getPos();

    /**
     * Set the POS code for the current token.
     *
     * @param pos POS code, or {@link #UNKNOWN} to clear.
     */
    void setPos(int pos);
}
