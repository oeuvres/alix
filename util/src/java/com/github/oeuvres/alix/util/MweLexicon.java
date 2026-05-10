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
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Incremental lexicon of multi-word expressions backed by a {@link CharsDic}
 * and an {@link IntAutomaton}.
 *
 * <p>
 * The lexicon is tokenizer-agnostic. Callers must tokenize expressions before
 * adding them. The lexicon copies each token immediately into its internal
 * vocabulary and stores only integer ordinals in the automaton.
 * </p>
 *
 * <p>
 * The {@link CharsDic} holds both component tokens and canonical multi-word
 * expression forms. Component-token ordinals are used as automaton arc labels.
 * Canonical-form ordinals are used as automaton accept values.
 * </p>
 *
 * <p>
 * Lifecycle:
 * </p>
 *
 * <ol>
 * <li>Construct a mutable lexicon.</li>
 * <li>Add already-tokenized expressions with
 * {@link #addExpression(List, CharSequence)}.</li>
 * <li>Call {@link #freeze()}.</li>
 * <li>Use {@link #root()}, {@link #step(int, char[], int)}, and
 * {@link #accept(int)} at runtime.</li>
 * </ol>
 *
 * <p>
 * Thread-safety: not thread-safe while building; immutable and safe for
 * concurrent read after {@link #freeze()}.
 * </p>
 */
public final class MweLexicon
{
    /** Automaton over component-token ordinal sequences; accept value is canonical form ordinal. */
    private final IntAutomaton auto;

    /**
     * Reusable buffer accumulating token ids during {@link #addExpression(List, CharSequence)}.
     * Nulled by {@link #freeze()} and also used as the frozen-state sentinel.
     */
    private int[] idsBuf;

    /** Shared vocabulary: component tokens and canonical forms, identified by ordinal. */
    private final CharsDic vocab;

    /**
     * Constructs an empty mutable lexicon.
     *
     * @param expectedSize estimated number of multi-word expressions
     */
    public MweLexicon(final int expectedSize)
    {
        this.vocab = new CharsDic(Math.max(8, expectedSize * 3));
        this.auto = new IntAutomaton();
        this.idsBuf = new int[8];
    }

    /**
     * Returns the canonical-form ordinal accepted by an automaton state.
     *
     * @param state the automaton state
     * @return the canonical-form ordinal, or -1 if the state is not accepting
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public int accept(final int state)
    {
        checkFrozen();
        return auto.accept(state);
    }

    /**
     * Adds a tokenized expression with an explicit canonical form.
     *
     * <p>
     * Expressions yielding fewer than two non-empty tokens are silently skipped.
     * If the same token sequence is added more than once, the last canonical form
     * wins.
     * </p>
     *
     * @param expression the already-tokenized multi-word expression
     * @param canonical  the canonical form to emit when the expression is matched
     * @throws IllegalStateException if the lexicon has already been frozen
     */
    public void addExpression(final List<? extends CharSequence> expression, final CharSequence canonical)
    {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(canonical, "canonical");

        checkMutable();

        final int count = countTokens(expression);
        if (count < 2) {
            return;
        }

        ensureIdsCapacity(count);

        int len = 0;
        for (CharSequence token : expression) {
            if (token == null || token.length() == 0) {
                continue;
            }
            idsBuf[len++] = vocab.add(token);
        }

        final int formOrd = vocab.add(canonical);
        auto.add(idsBuf, len, formOrd);
    }

    /**
     * Returns the canonical form identified by an accept ordinal.
     *
     * @param ord the ordinal returned by {@link #accept(int)}
     * @return the canonical form
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public String asString(final int ord)
    {
        checkFrozen();
        return vocab.asString(ord);
    }

    /**
     * Checks that the lexicon has been frozen.
     *
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public void checkFrozen()
    {
        if (!isFrozen()) {
            throw new IllegalStateException("not frozen");
        }
    }

    /**
     * Copies a canonical form into a destination character buffer.
     *
     * @param ord the ordinal returned by {@link #accept(int)}
     * @param dst the destination buffer
     * @param off the destination offset
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public void copy(final int ord, final char[] dst, final int off)
    {
        checkFrozen();
        vocab.copy(ord, dst, off);
    }

    /**
     * Returns the length of a canonical form.
     *
     * @param ord the ordinal returned by {@link #accept(int)}
     * @return the canonical-form length in UTF-16 code units
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public int formLength(final int ord)
    {
        checkFrozen();
        return vocab.termLength(ord);
    }

    /**
     * Freezes the vocabulary and packs the automaton into primitive arrays.
     * Idempotent. Must be called before runtime matching.
     */
    public void freeze()
    {
        if (idsBuf == null) {
            return;
        }
        vocab.trimToSize();
        auto.freeze(false);
        idsBuf = null;
    }

    /**
     * Returns whether the lexicon is frozen.
     *
     * @return true if the lexicon is frozen
     */
    public boolean isFrozen()
    {
        return idsBuf == null;
    }

    /**
     * Returns the upper bound on multi-word expression length in tokens.
     *
     * @return the maximum token length of registered expressions
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public int maxLen()
    {
        checkFrozen();
        return auto.maxLen();
    }

    /**
     * Returns the root state of the automaton.
     *
     * @return the root state
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public int root()
    {
        checkFrozen();
        return auto.root();
    }

    /**
     * Advances the automaton by one token.
     *
     * <p>
     * The token must occupy {@code buf[0..len)}, matching the convention of
     * {@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute#buffer()}.
     * Tokens absent from the vocabulary return -1 immediately without touching
     * the automaton.
     * </p>
     *
     * @param state the current automaton state
     * @param buf   the token character buffer
     * @param len   the number of valid characters in {@code buf}
     * @return the next state, or -1 if no transition exists
     * @throws IllegalStateException if the lexicon has not been frozen
     */
    public int step(final int state, final char[] buf, final int len)
    {
        checkFrozen();

        final int tokOrd = vocab.ord(buf, 0, len);
        if (tokOrd < 0) {
            return -1;
        }

        return auto.step(state, tokOrd);
    }

    /**
     * Returns the underlying vocabulary. Treat as read-only; mutating it
     * directly while the lexicon is mutable would corrupt automaton arcs.
     *
     * @return the underlying vocabulary
     */
    public CharsDic vocab()
    {
        return vocab;
    }

    /**
     * Checks that the lexicon is still mutable.
     *
     * @throws IllegalStateException if the lexicon is frozen
     */
    private void checkMutable()
    {
        if (idsBuf == null) {
            throw new IllegalStateException("frozen");
        }
    }

    /**
     * Counts non-empty tokens in an expression.
     *
     * @param expression the tokenized expression
     * @return the number of non-empty tokens
     */
    private static int countTokens(final List<? extends CharSequence> expression)
    {
        int count = 0;
        for (CharSequence token : expression) {
            if (token != null && token.length() > 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Ensures that the reusable id buffer has the requested capacity.
     *
     * @param capacity the requested capacity
     */
    private void ensureIdsCapacity(final int capacity)
    {
        if (capacity <= idsBuf.length) {
            return;
        }
        int newLength = idsBuf.length;
        while (newLength < capacity) {
            newLength <<= 1;
        }
        idsBuf = Arrays.copyOf(idsBuf, newLength);
    }
}
