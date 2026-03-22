package com.github.oeuvres.alix.lucene.analysis;

import java.io.IOException;

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
 * an accept ordinal can retrieve the canonical char data from {@link #vocab()} without
 * allocation.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>Construct with the {@link Analyzer} that matches the index-time pipeline.</li>
 *   <li>Call {@link #addExpression(CharSequence)} for each canonical MWE string.</li>
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

    /** Reusable buffer for token-id sequences during addExpression. */
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
        this.analyzer   = analyzer;
        this.fieldName  = fieldName;
        this.vocab      = new CharsDic(Math.max(8, expectedSize * 3));
        this.auto       = new IntAutomaton();
        this.idsBuf     = new int[8];
    }

    /**
     * Returns the {@link CharsDic} ordinal of the canonical form if {@code state} is
     * accepting, or -1 if non-accepting.
     * Pass the result to {@link #vocab()} to retrieve char data without allocation.
     */
    public int accept(final int state)
    {
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
    public void addExpression(final CharSequence expression) throws IOException
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
        }

        if (len < 2) return;

        // Register canonical form only after confirming the expression is valid.
        final int formOrd = ord(vocab.add(expression));
        auto.add(idsBuf, len, formOrd);
    }

    /**
     * Freezes the vocabulary and packs the automaton into primitive arrays.
     * Must be called before any runtime method. Idempotent.
     */
    public void freeze()
    {
        vocab.trimToSize();
        auto.freeze(false);
        idsBuf = null;
    }

    /**
     * Upper bound on MWE length in tokens.
     * Use to size the token filter's lookahead buffer ({@code maxLen() + 1}).
     */
    public int maxLen()
    {
        return auto.maxLen();
    }

    /** Root state; pass as the initial state to the first {@link #step} call per position. */
    public int root()
    {
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
     */
    public int step(final int state, final char[] buf, final int len)
    {
        if (idsBuf != null) throw new IllegalStateException("not frozen");
        final int tokOrd = vocab.find(buf, 0, len);
        if (tokOrd < 0) return -1;
        return auto.step(state, tokOrd);
    }

    /**
     * Shared vocabulary; use ordinals from {@link #accept(int)} to retrieve canonical forms.
     */
    public CharsDic vocab()
    {
        return vocab;
    }

    /** Recovers a {@link CharsDic} ordinal from the raw return value of {@code add()}. */
    private static int ord(final int raw)
    {
        return raw >= 0 ? raw : -raw - 1;
    }
}
