package com.github.oeuvres.alix.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable word tokenizer for short character sequences.
 *
 * <p>A tokenizer instance is stateful, reusable, and not thread-safe. It is
 * bound to an input with {@link #reset(CharSequence)}. Calls to {@link #next()}
 * advance through the words of the current input. The value returned by
 * {@link #word()} may be backed by mutable internal buffers and is valid only
 * until the next call to {@link #next()}, {@link #reset(CharSequence)}, or
 * {@link #clear()}.</p>
 *
 * <p>The {@link #tokenize(CharSequence)} convenience method returns stable
 * {@link String} values.</p>
 */
public interface WordTokenizer {
    /**
     * Clears the current input and scan state.
     *
     * <p>Implementations may keep internal buffers allocated for later reuse,
     * but should release references to the current input.</p>
     */
    void clear();

    /**
     * Advances to the next word.
     *
     * @return true if a word is available, false otherwise
     */
    boolean next();

    /**
     * Binds this tokenizer to a new input.
     *
     * <p>Any previous scan state is discarded.</p>
     *
     * @param text the input text
     */
    void reset(CharSequence text);

    /**
     * Tokenizes an input into a stable list of strings.
     *
     * <p>This is the convenience allocation path. It consumes this tokenizer's
     * mutable scan state.</p>
     *
     * @param text the input text
     * @return the tokenized words
     */
    default List<String> tokenize(final CharSequence text) {
        final ArrayList<String> words = new ArrayList<>();

        reset(text);
        try {
            while (next()) {
                words.add(word().toString());
            }
            return words;
        } finally {
            clear();
        }
    }

    /**
     * Returns the current word.
     *
     * <p>The returned value is transient. Callers that need to retain it must
     * copy it, for example with {@link CharSequence#toString()}.</p>
     *
     * @return the current word
     * @throws IllegalStateException if called before {@link #next()} returned true
     */
    CharSequence word();
}