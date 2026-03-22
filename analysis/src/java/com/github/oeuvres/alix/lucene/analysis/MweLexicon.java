/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.util.CharsDic;
import com.github.oeuvres.alix.util.IntAutomaton;

/**
 * Incremental lexicon of multi-word expressions (MWEs), backed by a single
 * {@link CharsDic} and an {@link IntAutomaton}.
 *
 * <p>The {@link CharsDic} holds both component tokens (automaton arc labels, by ordinal)
 * and canonical MWE forms (automaton accept values, also ordinals). A caller that receives
 * an accept ordinal from {@link #accept(int)} can copy the canonical form into a
 * {@link CharTermAttribute} via {@link #formToAttribute(int, CharTermAttribute)} without allocation.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>Construct with the {@link Analyzer} matching the index-time pipeline.</li>
 *   <li>Call {@link #addExpression(CharSequence)} for each canonical MWE string,
 *       or use {@link #fromPath(Path, Analyzer, String)} to load from a file.</li>
 *   <li>Call {@link #freeze()} once; further {@link #addExpression} calls throw.</li>
 *   <li>Use {@link #step(int, char[], int)} and {@link #accept(int)} in the token filter.</li>
 * </ol>
 *
 * <p>Thread-safety: not thread-safe during building; immutable and safe for concurrent
 * read after {@link #freeze()}.</p>
 */
public final class MweLexicon
{
    /** Shared vocabulary: component tokens and canonical forms, identified by ordinal. */
    private final CharsDic vocab;

    /** Automaton over component-token ordinal sequences; accept value = canonical form ordinal. */
    private final IntAutomaton auto;

    /** Analyzer used to split canonical forms into component tokens. */
    private final Analyzer analyzer;

    /** Field name passed to the analyzer (may be a dummy value). */
    private final String fieldName;

    /**
     * Reusable buffer accumulating token ids during {@link #addExpression}.
     * Nulled at {@link #freeze()} as the frozen-state sentinel.
     */
    private int[] idsBuf;

    /**
     * Constructs an empty, mutable lexicon.
     *
     * @param analyzer     analyzer whose tokenization matches the index-time pipeline
     * @param fieldName    field name passed to the analyzer
     * @param expectedSize estimate of the number of MWEs; used only for initial sizing
     */
    public MweLexicon(final Analyzer analyzer, final String fieldName, final int expectedSize)
    {
        if (analyzer == null)  throw new IllegalArgumentException("analyzer");
        if (fieldName == null) throw new IllegalArgumentException("fieldName");
        this.analyzer  = analyzer;
        this.fieldName = fieldName;
        this.vocab     = new CharsDic(Math.max(8, expectedSize * 3));
        this.auto      = new IntAutomaton();
        this.idsBuf    = new int[8];
    }

    /**
     * Returns the {@link CharsDic} ordinal of the canonical form if {@code state} is
     * accepting, or -1 if non-accepting.
     * Pass the result to {@link #formToAttribute(int, CharTermAttribute)} to write the form into a term attribute.
     *
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public int accept(final int state)
    {
        if (idsBuf != null) throw new IllegalStateException("not frozen");
        return auto.accept(state);
    }

    /**
     * Tokenizes {@code expression} with the analyzer, registers each component token
     * in the vocabulary, and adds the token-id sequence to the automaton with the
     * canonical form ordinal as accept value.
     *
     * <p>Expressions that yield fewer than two tokens are silently skipped.
     * If the same token sequence is added more than once, the last canonical form wins.</p>
     *
     * @param expression canonical MWE string (e.g. {@code "machine learning"})
     * @throws IOException           if the analyzer throws during tokenization
     * @throws IllegalStateException if {@link #freeze()} has already been called
     */
    public void addExpression(final CharSequence expression) 
    {
        if (idsBuf == null) throw new IllegalStateException("frozen");
        if (expression == null || expression.length() == 0) return;

        // Tokenize first: only register in vocab if the sequence is a valid MWE (>= 2 tokens).
        int len = 0;
        try (TokenStream ts = analyzer.tokenStream(fieldName, expression.toString())) {
            final CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                // CharTermAttribute implements CharSequence: no toString() copy needed.
                final int tokOrd = ord(vocab.add(termAtt));
                if (len == idsBuf.length) idsBuf = java.util.Arrays.copyOf(idsBuf, len * 2);
                idsBuf[len++] = tokOrd;
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (len < 2) return;

        // Register canonical form only after confirming the expression is valid.
        final int formOrd = ord(vocab.add(expression));
        auto.add(idsBuf, len, formOrd);
    }

    /**
     * Check that {@link #freeze()} has been called or send an exception
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public void checkFrozen() {
        if (!isFrozen()) throw new IllegalStateException("not frozen");
    }

    /**
     * Returns the canonical form identified by {@code acceptOrd} as a new String.
     * Prefers {@link #formToAttribute(int,CharTermAttribute)} in a {@link TokenFilter}.
     * @param acceptOrd
     * @return form of contained expression
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public String formAsString(final int acceptOrd)
    {
        checkFrozen();
        return vocab.getAsString(acceptOrd);
    }

    /**
     * Copies the canonical form identified by {@code acceptOrd} into {@code termAtt},
     * resizing its buffer as needed. No allocation beyond the buffer resize when needed.
     *
     * @param acceptOrd ordinal returned by {@link #accept(int)}
     * @param termAtt   term attribute to fill
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public void formToAttribute(final int acceptOrd, final CharTermAttribute termAtt)
    {
        checkFrozen();
        final int    len = vocab.termLength(acceptOrd);
        final char[] buf = termAtt.resizeBuffer(len);
        vocab.get(acceptOrd, buf, 0);
        termAtt.setLength(len);
    }

    /**
     * Freezes the vocabulary and packs the automaton into primitive arrays.
     * Must be called before any runtime method. Idempotent.
     */
    public void freeze()
    {
        if (idsBuf == null) return; // already frozen
        vocab.trimToSize();
        auto.freeze(false);
        idsBuf = null;
    }
    
    /**
     * Verify that {@link #freeze()} has been called
     * @return true if frozen
     */
    public boolean isFrozen() {
        return (idsBuf == null);
    }

    /**
     * Upper bound on MWE length in tokens.
     * Use to size the token filter's lookahead buffer ({@code maxLen() + 1}).
     *
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public int maxLen()
    {
        if (idsBuf != null) throw new IllegalStateException("not frozen");
        return auto.maxLen();
    }

    /**
     * Root state; pass as the initial state to the first {@link #step} call per position.
     *
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public int root()
    {
        checkFrozen();
        return auto.root();
    }

    /**
     * Advances the automaton by one token.
     *
     * <p>Tokens absent from the vocabulary return -1 immediately without touching
     * the automaton — fast-fail for the common case.</p>
     *
     * @param state current automaton state
     * @param buf   token character buffer (e.g. {@link CharTermAttribute#buffer()})
     * @param len   number of valid chars in {@code buf}
     * @return next state, or -1 if no transition exists
     * @throws IllegalStateException if {@link #freeze()} has not been called
     */
    public int step(final int state, final char[] buf, final int len)
    {
        if (idsBuf != null) throw new IllegalStateException("not frozen");
        final int tokOrd = vocab.find(buf, 0, len);
        if (tokOrd < 0) return -1;
        return auto.step(state, tokOrd);
    }

    /**
     * Give access to the underlying dictionary.
     * @return {@link #vocab}
     */
    protected CharsDic vocab()
    {
        return vocab;
    }

    /** Recovers a {@link CharsDic} ordinal from the raw return value of {@code add()}. */
    static int ord(final int raw)
    {
        return raw >= 0 ? raw : -raw - 1;
    }
}
